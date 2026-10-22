package com.miaomiaotv.cqcuiptv;

import android.content.Context;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M3U 播放列表加载与解析。
 * 支持本地 assets、http/https 远程地址，以及用户手写的 M3U 文本。
 */
public final class Playlist {

    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 15000;

    private Playlist() {
    }

    /** 远程地址是 http/https 就下载，其余的（空、内置）回落到打包在 APK 里的列表。 */
    public static List<Channel> load(String url, Context ctx) throws IOException {
        if (!TextUtils.isEmpty(url) && (url.startsWith("http://") || url.startsWith("https://"))) {
            return parseText(download(url));
        }
        InputStream in = ctx.getAssets().open("playlist.m3u");
        try {
            return parseText(readAll(in));
        } finally {
            in.close();
        }
    }

    public static String download(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "CQCU-TV/1.0");
        try {
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                throw new IOException("HTTP " + code);
            }
            return readAll(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    public static List<Channel> parse(InputStream in) throws IOException {
        return parseText(readAll(in));
    }

    /** 解析 #EXTINF 属性与紧随其后的播放地址，忽略 #EXTVLCOPT 等其它指令。 */
    public static List<Channel> parseText(String text) {
        List<Channel> out = new ArrayList<Channel>();
        if (TextUtils.isEmpty(text)) {
            return out;
        }
        String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
        String[] lines = body.replace("\r", "").split("\n");

        String pendingName = null;
        String group = "";
        String tvgId = "";
        String logo = "";

        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0) {
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                int colon = line.indexOf(':');
                String info = colon >= 0 ? line.substring(colon + 1) : line;
                group = "";
                tvgId = "";
                logo = "";
                Matcher m = ATTR.matcher(info);
                while (m.find()) {
                    String key = m.group(1).toLowerCase();
                    String val = m.group(2);
                    if ("group-title".equals(key)) {
                        group = val;
                    } else if ("tvg-id".equals(key)) {
                        tvgId = val;
                    } else if ("tvg-logo".equals(key)) {
                        logo = val;
                    }
                }
                int comma = info.lastIndexOf(',');
                pendingName = comma >= 0 ? info.substring(comma + 1).trim() : "";
                continue;
            }
            if (line.startsWith("#EXTGRP:")) {
                group = line.substring(8).trim();
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            if (!TextUtils.isEmpty(pendingName)) {
                out.add(new Channel(pendingName, line, group, tvgId, logo));
                pendingName = null;
            }
        }
        return out;
    }
}
