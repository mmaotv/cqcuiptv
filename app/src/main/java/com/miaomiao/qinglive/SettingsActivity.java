package com.miaomiao.qinglive;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

/** 设置页：列表地址 / 自定义 M3U / 缓存周期 / 局域网填源。 */
public class SettingsActivity extends Activity {

    private EditText urlInput;
    private EditText customInput;
    private TextView cacheState;
    private TextView cfgUrl;
    private TextView[] chips;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = new Prefs(this);
        urlInput = (EditText) findViewById(R.id.url_input);
        customInput = (EditText) findViewById(R.id.custom_input);
        cacheState = (TextView) findViewById(R.id.cache_state);
        cfgUrl = (TextView) findViewById(R.id.cfg_url);

        chips = new TextView[]{
                (TextView) findViewById(R.id.ttl_0),
                (TextView) findViewById(R.id.ttl_1),
                (TextView) findViewById(R.id.ttl_2),
                (TextView) findViewById(R.id.ttl_3)
        };
        for (int i = 0; i < chips.length; i++) {
            final int days = Prefs.CACHE_DAYS[i];
            chips[i].setText(Prefs.CACHE_LABELS[i]);
            chips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setCacheDays(days);
                    refreshChips();
                    refreshCacheState();
                }
            });
        }

        urlInput.setText(prefs.url());
        customInput.setText(prefs.custom());

        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setUrl(urlInput.getText().toString().trim());
                prefs.setCustom(customInput.getText().toString());
                done();
            }
        });

        findViewById(R.id.btn_reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setUrl(prefs.defaultUrl());
                prefs.setCustom("");
                prefs.setLastIndex(0);
                Store.clearCache(SettingsActivity.this);
                urlInput.setText(prefs.defaultUrl());
                customInput.setText("");
                refreshCacheState();
                done();
            }
        });

        findViewById(R.id.btn_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });

        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 先把输入框里的改动落盘，再清缓存，回主界面时就会重新拉
                prefs.setUrl(urlInput.getText().toString().trim());
                prefs.setCustom(customInput.getText().toString());
                Store.clearCache(SettingsActivity.this);
                done();
            }
        });

        findViewById(R.id.btn_cfg_toggle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setConfigServer(!prefs.configServerOn());
                ConfigServer.sync(SettingsActivity.this);
                refreshServer();
            }
        });

        if (TextUtils.isEmpty(prefs.custom())) {
            customInput.setHint("例如：把 m3u 内容整段粘贴到这里，优先级高于上面的地址");
        }

        refreshChips();
        refreshCacheState();
        refreshServer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshServer();
    }

    private void refreshChips() {
        int current = prefs.cacheDays();
        for (int i = 0; i < chips.length; i++) {
            chips[i].setSelected(Prefs.CACHE_DAYS[i] == current);
        }
    }

    private void refreshCacheState() {
        Store.Cache cache = Store.readCache(this);
        if (cache == null || cache.isEmpty()) {
            cacheState.setText("当前没有缓存；下次启动会按地址拉一次并存下来");
            return;
        }
        int count = 0;
        try {
            count = Playlist.parseAll(cache.body).channels.size();
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder();
        sb.append("缓存：").append(count).append(" 个频道 · ")
                .append(dayTime(cache.savedAt));
        int days = prefs.cacheDays();
        if (days <= 0) {
            sb.append("　·　不自动更新");
        } else {
            long left = cache.savedAt + days * 86400000L - System.currentTimeMillis();
            if (left > 0) {
                sb.append("　·　").append(left / 86400000L).append(" 天后自动更新");
            } else {
                sb.append("　·　已过期，下次启动会自动更新");
            }
        }
        cacheState.setText(sb.toString());
    }

    private void refreshServer() {
        boolean on = prefs.configServerOn();
        ((android.widget.Button) findViewById(R.id.btn_cfg_toggle))
                .setText(on ? "关闭" : "开启");
        String url = ConfigServer.url();
        if (!on) {
            cfgUrl.setText("未开启");
        } else if (url == null) {
            cfgUrl.setText("开启失败（端口被占用？）");
        } else {
            cfgUrl.setText(url);
        }
    }

    private static String dayTime(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(java.util.Locale.US, "%02d-%02d %02d:%02d",
                c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    private void done() {
        ConfigServer.sync(this);
        setResult(RESULT_OK);
        finish();
    }
}
