package com.miaomiao.qinglive;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/** 设置页：列表地址 / 自定义 M3U / 缓存周期 / 局域网填源 / 检查更新。 */
public class SettingsActivity extends Activity {

    private EditText urlInput;
    private EditText customInput;
    private TextView cacheState;
    private TextView cfgUrl;
    private TextView updateState;
    private Button btnUpdateCheck;
    private Button btnUpdateNotes;
    private TextView[] chips;
    private Prefs prefs;
    /** 正在检查更新：避免连点重复发请求 */
    private boolean checking;

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
            // 让芯片也能被方向键聚焦，并且不进入触摸聚焦态，保证光标高亮始终画得出来
            chips[i].setFocusable(true);
            chips[i].setFocusableInTouchMode(false);
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
        setupUpdate();
    }

    /** 版本更新入口：只检查 + 展示日志，装不装由用户点按钮决定。 */
    private void setupUpdate() {
        updateState = (TextView) findViewById(R.id.update_state);
        ((TextView) findViewById(R.id.ver_current)).setText(
                "当前版本 " + Updater.versionName(this)
                        + "（" + Updater.versionCode(this) + "）");
        btnUpdateCheck = (Button) findViewById(R.id.btn_update_check);
        btnUpdateNotes = (Button) findViewById(R.id.btn_update_notes);
        btnUpdateCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate(true);
            }
        });
        btnUpdateNotes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 只看日志，不对「更新」做任何引导
                checkUpdate(false);
            }
        });
    }

    private void checkUpdate(final boolean allowInstall) {
        if (checking) {
            return;
        }
        checking = true;
        btnUpdateCheck.setEnabled(false);
        btnUpdateNotes.setEnabled(false);
        updateState.setText("正在检查更新…");
        Updater.check(this, new Updater.Callback() {
            @Override
            public void onResult(Updater.Info info, String error) {
                checking = false;
                if (isFinishing()) {
                    return;
                }
                btnUpdateCheck.setEnabled(true);
                btnUpdateNotes.setEnabled(true);
                if (info == null) {
                    updateState.setText("检查失败：" + error
                            + "\n（更新清单和播放列表在同一个地址源，能正常拉列表一般就能检查）");
                    return;
                }
                if (info.newer) {
                    updateState.setText("发现新版本 " + info.versionName
                            + "（当前 " + Updater.versionName(SettingsActivity.this) + "）");
                } else {
                    updateState.setText("已是最新版本 " + info.versionName);
                }
                Updater.showNotes(SettingsActivity.this, info, allowInstall);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshServer();
    }

    /**
     * 设置页内容超过一屏、可滚动。触屏滑动或列表滚动后焦点偶尔会整页丢失，
     * 于是按方向键也找不到光标（用户反馈「重新拉取上面的按钮都没有选中状态」）。
     * 这里在焦点丢失时先把光标放到第一个可聚焦控件上，保证总能看到选中态。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN
                && (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                    || e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                    || e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                    || e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT
                    || e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                    || e.getKeyCode() == KeyEvent.KEYCODE_TAB)
                && getCurrentFocus() == null) {
            View first = findViewById(R.id.btn_cfg_toggle);
            if (first != null) {
                first.requestFocus();
            }
        }
        return super.dispatchKeyEvent(e);
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
        // 与 EPG 一致用北京时间：盒子系统时区可能被设成别处（如 America/New_York），
        // 用设备时区会把缓存时间也带偏。
        java.util.Calendar c = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("GMT+08:00"));
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
