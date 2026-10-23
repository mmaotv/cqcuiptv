package com.miaomiao.qinglive;

import android.content.Context;
import android.content.SharedPreferences;

/** 播放列表地址、自定义 M3U、缓存周期、局域网配置服务开关、上次收看的频道号。 */
public final class Prefs {

    private static final String FILE = "qinglive";

    /** 自动拉取周期可选项（天），0 = 不自动更新 */
    public static final int[] CACHE_DAYS = {7, 15, 30, 0};
    public static final String[] CACHE_LABELS = {"7 天", "15 天", "一个月", "不自动"};

    private final SharedPreferences sp;
    private final Context ctx;
    /** 打包时写进 res 的默认地址，指向本仓库为 App 排好序的播放列表（CCTV1 为 1 台）。 */
    private final String defaultUrl;

    public Prefs(Context ctx) {
        this.ctx = ctx.getApplicationContext();
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
        return Store.readCustom(ctx);
    }

    public void setCustom(String text) {
        Store.writeCustom(ctx, text);
    }

    // -------------------------------------------------------------- 缓存周期

    public int cacheDays() {
        int v = sp.getInt("cache_days", 7);
        for (int d : CACHE_DAYS) {
            if (d == v) {
                return v;
            }
        }
        return 7;
    }

    public void setCacheDays(int days) {
        sp.edit().putInt("cache_days", days).apply();
    }

    /** 缓存是否还在有效期内；选「不自动」时只要有缓存就一直用。 */
    public boolean cacheFresh(long savedAt) {
        int days = cacheDays();
        if (savedAt <= 0) {
            return false;
        }
        if (days <= 0) {
            return true;
        }
        return System.currentTimeMillis() - savedAt < days * 86400000L;
    }

    // -------------------------------------------------------- 局域网配置服务

    public boolean configServerOn() {
        return sp.getBoolean("cfg_server", false);
    }

    public void setConfigServer(boolean on) {
        sp.edit().putBoolean("cfg_server", on).apply();
    }

    public int configPort() {
        return sp.getInt("cfg_port", ConfigServer.DEFAULT_PORT);
    }

    public void setConfigPort(int port) {
        sp.edit().putInt("cfg_port", port).apply();
    }

    // ----------------------------------------------------------------- 其它

    public int lastIndex() {
        return sp.getInt("index", 0);
    }

    public void setLastIndex(int index) {
        sp.edit().putInt("index", index).apply();
    }
}
