package com.miaomiaotv.cqcuiptv;

import android.content.Context;
import android.text.TextUtils;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * EPG：拉取 XMLTV（e.xml），按 tvg-id 建立节目单，并提供“正在播/下一档”查询。
 * 电视盒子上内存有限，解析时只保留本列表内的频道、且只留今天前后一天内的节目。
 */
public final class Epg {

    /** 默认 EPG 源：gcore 节点在国内实测最快，cdn 节点兜底 */
    public static final String DEFAULT_URL =
            "https://gcore.jsdelivr.net/gh/fanmingming/live@main/e.xml";

    public static final String[] DEFAULT_URLS = {
            DEFAULT_URL,
            "https://cdn.jsdelivr.net/gh/fanmingming/live@main/e.xml"
    };

    private static final long CACHE_TTL = 6 * 3600 * 1000L;

    public static final class Programme {
        public final long start;
        public final long stop;
        public final String title;

        Programme(long start, long stop, String title) {
            this.start = start;
            this.stop = stop;
            this.title = title;
        }
    }

    private final Map<String, List<Programme>> map = new HashMap<String, List<Programme>>();

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public boolean has(String id) {
        return id != null && map.containsKey(id);
    }

    /** 当前正在播出的节目。 */
    public Programme now(String id) {
        List<Programme> list = map.get(id);
        if (list == null) {
            return null;
        }
        long t = System.currentTimeMillis();
        for (Programme p : list) {
            if (p.start <= t && t < p.stop) {
                return p;
            }
        }
        return null;
    }

    /** 下一档节目。 */
    public Programme next(String id) {
        List<Programme> list = map.get(id);
        if (list == null) {
            return null;
        }
        long t = System.currentTimeMillis();
        for (Programme p : list) {
            if (p.start > t) {
                return p;
            }
        }
        return null;
    }

    public List<Programme> list(String id) {
        List<Programme> list = map.get(id);
        return list == null ? Collections.<Programme>emptyList() : list;
    }

    // ------------------------------------------------------------------ 加载

    private static File cacheFile(Context ctx) {
        return new File(ctx.getFilesDir(), "epg.xml");
    }

    /** 先读磁盘缓存，命中就立刻可用，不用等网络。 */
    public static Epg fromCache(Context ctx, Set<String> wanted) {
        File f = cacheFile(ctx);
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > CACHE_TTL) {
            return null;
        }
        try {
            return parse(new BufferedInputStream(new java.io.FileInputStream(f)), wanted);
        } catch (Exception e) {
            return null;
        }
    }

    /** 依次尝试多个源，成功就写入缓存。先落盘再解析，避免把 8MB 的 XML 全塞进内存。 */
    public static Epg download(Context ctx, List<String> sources, Set<String> wanted) {
        for (String src : sources) {
            if (TextUtils.isEmpty(src)) {
                continue;
            }
            File tmp = new File(ctx.getFilesDir(), "epg.tmp");
            try {
                if (!fetchTo(src, tmp)) {
                    tmp.delete();
                    continue;
                }
                Epg epg = parse(new BufferedInputStream(new java.io.FileInputStream(tmp)), wanted);
                if (epg != null && !epg.isEmpty()) {
                    File cache = cacheFile(ctx);
                    cache.delete();
                    if (!tmp.renameTo(cache)) {
                        copy(tmp, cache);
                        tmp.delete();
                    }
                    return epg;
                }
                tmp.delete();
            } catch (Throwable ignored) {
                tmp.delete();
            }
        }
        return null;
    }

    /** 下载到文件，成功返回 true。支持 gzip 与跳转。 */
    private static boolean fetchTo(String url, File out) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "LightTV/2.0");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                return false;
            }
            InputStream in = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding()) || url.endsWith(".gz")) {
                in = new GZIPInputStream(in);
            }
            FileOutputStream fos = new FileOutputStream(out);
            try {
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    total += n;
                }
                return total > 1024;
            } finally {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void copy(File from, File to) {
        try {
            InputStream in = new java.io.FileInputStream(from);
            FileOutputStream out = new FileOutputStream(to);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.close();
            in.close();
        } catch (Exception ignored) {
        }
    }

    /** 流式解析 XMLTV，只保留 wanted 里的频道。 */
    public static Epg parse(InputStream in, Set<String> wanted) throws Exception {
        Epg epg = new Epg();
        long now = System.currentTimeMillis();
        long from = now - 12 * 3600 * 1000L;
        long to = now + 36 * 3600 * 1000L;
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            int event = p.getEventType();
            String channel = null;
            long start = 0;
            long stop = 0;
            String title = null;
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = p.getName();
                    if ("programme".equals(tag)) {
                        channel = p.getAttributeValue(null, "channel");
                        start = parseTime(p.getAttributeValue(null, "start"));
                        stop = parseTime(p.getAttributeValue(null, "stop"));
                        title = null;
                    } else if ("title".equals(tag) && channel != null) {
                        title = p.nextText();
                    }
                } else if (event == XmlPullParser.END_TAG && "programme".equals(p.getName())) {
                    if (channel != null && title != null && start > 0
                            && stop > start && stop > from && start < to
                            && (wanted == null || wanted.contains(channel))) {
                        List<Programme> list = epg.map.get(channel);
                        if (list == null) {
                            list = new ArrayList<Programme>();
                            epg.map.put(channel, list);
                        }
                        list.add(new Programme(start, stop, title.trim()));
                    }
                    channel = null;
                    title = null;
                }
                event = p.next();
            }
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        Comparator<Programme> byStart = new Comparator<Programme>() {
            @Override
            public int compare(Programme a, Programme b) {
                return a.start < b.start ? -1 : (a.start > b.start ? 1 : 0);
            }
        };
        for (List<Programme> list : epg.map.values()) {
            Collections.sort(list, byStart);
        }
        return epg;
    }

    /** 20:30 这种格式，供列表与 OSD 用。 */
    public static String formatClock(long time) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(time);
        return String.format(java.util.Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    /**
     * XMLTV 时间：20261022200000 +0800。
     * <p>
     * 时区是**节目时间所在的时区**，不是设备时区。原先把它当成设备时区再减去偏移，
     * 中国时区（+0800）下等于多减了 8 小时，整张节目单全部往后错了 8 小时——
     * 早上 7 点显示的是下午 3 点的节目。这里按冒号后的偏移建 Calendar。
     */
    private static long parseTime(String s) {
        if (s == null || s.length() < 14) {
            return 0;
        }
        try {
            int year = Integer.parseInt(s.substring(0, 4));
            int month = Integer.parseInt(s.substring(4, 6));
            int day = Integer.parseInt(s.substring(6, 8));
            int hour = Integer.parseInt(s.substring(8, 10));
            int minute = Integer.parseInt(s.substring(10, 12));
            int second = Integer.parseInt(s.substring(12, 14));
            java.util.TimeZone tz = java.util.TimeZone.getDefault();
            String tail = s.length() > 14 ? s.substring(14).trim() : "";
            if (tail.length() >= 5 && (tail.charAt(0) == '+' || tail.charAt(0) == '-')) {
                tz = java.util.TimeZone.getTimeZone(
                        "GMT" + tail.substring(0, 3) + ":" + tail.substring(3, 5));
            } else if (tail.startsWith("Z")) {
                tz = java.util.TimeZone.getTimeZone("GMT");
            }
            java.util.Calendar c = java.util.Calendar.getInstance(tz);
            c.clear();
            c.set(year, month - 1, day, hour, minute, second);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 收集需要 EPG 的频道 id（tvg-id 优先，没有就用频道名）。 */
    public static Set<String> wantedIds(List<Channel> channels) {
        Set<String> set = new HashSet<String>();
        for (Channel c : channels) {
            String id = !TextUtils.isEmpty(c.tvgId) ? c.tvgId : c.name;
            set.add(id);
        }
        return set;
    }
}
