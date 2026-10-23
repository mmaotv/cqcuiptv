package com.miaomiao.qinglive;

import android.content.Context;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 本地文件存储：用户粘贴的自定义 M3U、以及自动拉取下来的播放列表缓存。
 * <p>
 * 缓存文件头三行记录来源地址和拉取时间，正文就是原始 M3U；
 * 头部都以 # 开头，M3U 解析器会当注释忽略，所以要不要头部都不影响播放。
 */
public final class Store {

    private static final String CUSTOM = "custom.m3u";
    private static final String CACHE = "playlist.cache";

    private static final String H_MAGIC = "#LIGHTLIVE-CACHE 1";
    private static final String H_URL = "#url:";
    private static final String H_SAVED = "#saved:";

    private Store() {
    }

    /** 缓存内容 + 元信息。 */
    public static final class Cache {
        public final String url;
        public final long savedAt;
        public final String body;

        Cache(String url, long savedAt, String body) {
            this.url = url == null ? "" : url;
            this.savedAt = savedAt;
            this.body = body == null ? "" : body;
        }

        public boolean isEmpty() {
            return body.length() == 0;
        }
    }

    // -------------------------------------------------------------- 自定义 M3U

    public static File customFile(Context ctx) {
        return new File(ctx.getFilesDir(), CUSTOM);
    }

    public static String readCustom(Context ctx) {
        String text = readText(customFile(ctx));
        return text == null ? "" : text;
    }

    public static void writeCustom(Context ctx, String text) {
        if (TextUtils.isEmpty(text)) {
            customFile(ctx).delete();
            return;
        }
        writeText(customFile(ctx), text);
    }

    // ------------------------------------------------------------------ 缓存

    public static File cacheFile(Context ctx) {
        return new File(ctx.getFilesDir(), CACHE);
    }

    public static void writeCache(Context ctx, String url, String text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        StringBuilder sb = new StringBuilder(text.length() + 160);
        sb.append(H_MAGIC).append('\n');
        sb.append(H_URL).append(url == null ? "" : url.replace('\n', ' ')).append('\n');
        sb.append(H_SAVED).append(System.currentTimeMillis()).append('\n');
        sb.append(text);
        writeText(cacheFile(ctx), sb.toString());
    }

    /** 读缓存；没有或坏了返回 null。 */
    public static Cache readCache(Context ctx) {
        String text = readText(cacheFile(ctx));
        if (TextUtils.isEmpty(text) || !text.startsWith(H_MAGIC)) {
            return null;
        }
        String url = "";
        long savedAt = 0;
        int bodyStart = 0;
        String[] lines = text.split("\n", 4);
        int headerLines = Math.min(3, lines.length);
        for (int i = 0; i < headerLines; i++) {
            String line = lines[i];
            if (line.startsWith(H_URL)) {
                url = line.substring(H_URL.length()).trim();
            } else if (line.startsWith(H_SAVED)) {
                try {
                    savedAt = Long.parseLong(line.substring(H_SAVED.length()).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            bodyStart += line.length() + 1;
        }
        if (savedAt <= 0) {
            savedAt = cacheFile(ctx).lastModified();
        }
        if (bodyStart > text.length()) {
            bodyStart = text.length();
        }
        String body = text.substring(bodyStart);
        return new Cache(url, savedAt, body);
    }

    public static void clearCache(Context ctx) {
        cacheFile(ctx).delete();
    }

    public static boolean hasCache(Context ctx) {
        File f = cacheFile(ctx);
        return f.exists() && f.length() > 16;
    }

    // ------------------------------------------------------------ 文件读写

    private static String readText(File f) {
        if (f == null || !f.exists() || f.length() == 0) {
            return null;
        }
        try {
            InputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length() + 16);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeText(File f, String text) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }
}
