package com.miaomiaotv.cqcuiptv;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

/** 设置页：改播放列表地址，或直接粘贴/编辑一份自己的 M3U。 */
public class SettingsActivity extends Activity {

    private EditText urlInput;
    private EditText customInput;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = new Prefs(this);
        urlInput = (EditText) findViewById(R.id.url_input);
        customInput = (EditText) findViewById(R.id.custom_input);

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
                urlInput.setText(prefs.defaultUrl());
                customInput.setText("");
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

        if (TextUtils.isEmpty(prefs.custom())) {
            customInput.setHint("例如：把 m3u 内容整段粘贴到这里，优先级高于上面的地址");
        }
    }

    private void done() {
        setResult(RESULT_OK);
        finish();
    }
}
