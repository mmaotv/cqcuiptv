package com.miaomiaotv.cqcuiptv;

import android.content.Context;
import android.content.SharedPreferences;

/** 播放列表地址、自定义 M3U、上次收看的频道号。 */
public final class Prefs {

    private static final String FILE = "cqcu_tv";

    private final SharedPreferences sp;
    /** 打包时写进 res 的默认地址，指向本仓库为 App 排好序的播放列表（CCTV1 为 1 台）。 */
    private final String defaultUrl;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        defaultUrl = ctx.getString(R.string.default_playlist);
    }

    public String defaultUrl() {
        return defaultUrl;
    }

    public String url() {
        String v = sp.getString("url", null);
        return v == null ? defaultUrl : v;
    }

    public void setUrl(String url) {
        sp.edit().putString("url", url == null ? "" : url.trim()).apply();
    }

    public String custom() {
        return sp.getString("custom", "");
    }

    public void setCustom(String text) {
        sp.edit().putString("custom", text == null ? "" : text).apply();
    }

    public int lastIndex() {
        return sp.getInt("index", 0);
    }

    public void setLastIndex(int index) {
        sp.edit().putInt("index", index).apply();
    }
}
