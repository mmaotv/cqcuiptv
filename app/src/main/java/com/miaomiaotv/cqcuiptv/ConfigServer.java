package com.miaomiaotv.cqcuiptv;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 极简 HTTP 服务：局域网里的手机 / 电脑打开 http://盒子IP:端口，就能把自定义 M3U
 * 粘进来、或者填一个列表地址，省得用遥控器一个字母一个字母敲。
 * <p>
 * 没有第三方依赖，单线程 accept + 一次请求一条连接，页面就是一段 HTML。
 * 只监听局域网，不做鉴权（家里网内用，够用就好）。
 */
public final class ConfigServer {

    public static final int DEFAULT_PORT = 8965;
    private static final int PORT_TRIES = 8;
    /** 请求体上限，防止被灌爆内存 */
    private static final int MAX_BODY = 4 * 1024 * 1024;

    private static ConfigServer instance;
    private static volatile Runnable saveListener;
    private static volatile String statusLine = "还没有加载频道列表";

    private final Context ctx;
    private ServerSocket server;
    private Thread thread;
    private volatile boolean running;
    private int port;
    private volatile String ip;

    private ConfigServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ------------------------------------------------------------------ 对外

    /** 保存成功后回调（在后台线程触发，调用方自己切主线程）。 */
    public static void setSaveListener(Runnable r) {
        saveListener = r;
    }

    /** 让页面显示当前状态，例如「151 个频道 · 缓存 22:40」。 */
    public static void setStatus(String text) {
        if (!TextUtils.isEmpty(text)) {
            statusLine = text;
        }
    }

    public static boolean isRunning() {
        ConfigServer s = instance;
        return s != null && s.running;
    }

    public static int port() {
        ConfigServer s = instance;
        return s == null ? 0 : s.port;
    }

    /** 正在监听就返回可访问的网址，否则 null。 */
    public static String url() {
        ConfigServer s = instance;
        if (s == null || !s.running || s.ip == null) {
            return null;
        }
        return "http://" + s.ip + ":" + s.port;
    }

    /** 按设置里的开关起停。 */
    public static synchronized void sync(Context ctx) {
        if (new Prefs(ctx).configServerOn()) {
            start(ctx);
        } else {
            stop();
        }
    }

    public static synchronized void start(Context ctx) {
        if (instance != null && instance.running) {
            return;
        }
        ConfigServer s = new ConfigServer(ctx);
        if (s.open()) {
            instance = s;
            new Prefs(ctx).setConfigPort(s.port);
        }
    }

    public static synchronized void stop() {
        ConfigServer s = instance;
        instance = null;
        if (s != null) {
            s.close();
        }
    }

    // ------------------------------------------------------------- 服务端实现

    private boolean open() {
        running = true;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                bindAndServe(latch);
            }
        }, "cfg-http");
        thread.setDaemon(true);
        thread.start();
        // 建 socket 属于网络操作，不能在主线程做，等它绑好端口（最多 1.2 秒）
        try {
            latch.await(1200, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return port > 0;
    }

    private void bindAndServe(java.util.concurrent.CountDownLatch latch) {
        for (int i = 0; i < PORT_TRIES; i++) {
            int candidate = DEFAULT_PORT + i;
            try {
                ServerSocket ss = new ServerSocket(candidate, 8, InetAddress.getByName("0.0.0.0"));
                ss.setSoTimeout(1500);
                server = ss;
                port = candidate;
                break;
            } catch (Throwable ignored) {
                closeQuietly(server);
                server = null;
            }
        }
        ip = lanIp();
        latch.countDown();
        if (server == null) {
            running = false;
            return;
        }
        loop();
    }

    private void loop() {
        while (running) {
            Socket sock = null;
            try {
                sock = server.accept();
            } catch (SocketTimeoutException e) {
                continue;
            } catch (Throwable t) {
                break;
            }
            try {
                handle(sock);
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(sock);
            }
        }
    }

    private void close() {
        running = false;
        closeQuietly(server);
        server = null;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void handle(Socket sock) throws IOException {
        sock.setSoTimeout(8000);
        InputStream in = new BufferedInputStream(sock.getInputStream());
        String requestLine = readLine(in);
        if (requestLine == null) {
            return;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return;
        }
        String method = parts[0];
        String path = parts[1];
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }

        int contentLength = 0;
        String line;
        while ((line = readLine(in)) != null && line.length() > 0) {
            int c = line.indexOf(':');
            if (c > 0 && "content-length".equalsIgnoreCase(line.substring(0, c).trim())) {
                try {
                    contentLength = Integer.parseInt(line.substring(c + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        String body = "";
        if (contentLength > 0) {
            if (contentLength > MAX_BODY) {
                respond(sock, 200, page("内容太大（超过 4 MB），请改用列表地址", false, null));
                return;
            }
            byte[] buf = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(buf, read, contentLength - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            body = new String(buf, 0, read, "UTF-8");
        }

        if ("POST".equalsIgnoreCase(method) && "/save".equals(path)) {
            respond(sock, 200, save(body));
        } else if ("POST".equalsIgnoreCase(method) && "/clear".equals(path)) {
            respond(sock, 200, clear());
        } else if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            if ("/".equals(path) || "/index.html".equals(path)) {
                respond(sock, 200, page(null, true, null));
            } else {
                respond(sock, 404, page("页面不存在", false, null));
            }
        } else {
            respond(sock, 404, page("页面不存在", false, null));
        }
    }

    // ------------------------------------------------------------------ 保存

    private String save(String body) {
        String content = field(body, "content");
        String urlField = field(body, "url");
        Prefs p = new Prefs(ctx);

        if (!TextUtils.isEmpty(content) && content.trim().length() > 0) {
            String trimmed = content.trim();
            if (trimmed.indexOf('\n') < 0 && isHttp(trimmed)) {
                p.setUrl(trimmed);
                p.setCustom("");
                Store.clearCache(ctx);
                notifySaved();
                return page("已保存列表地址：" + trimmed, true, null);
            }
            if (!trimmed.startsWith("#EXTM3U") && trimmed.indexOf("#EXTINF") < 0) {
                return page("内容看起来不像 M3U（应以 #EXTM3U 开头）", false, content);
            }
            p.setCustom(content);
            Store.clearCache(ctx);
            notifySaved();
            int lines = content.split("\n").length;
            return page("已保存自定义列表（" + lines + " 行），电视上正在重新加载", true, null);
        }

        if (!TextUtils.isEmpty(urlField) && urlField.trim().length() > 0) {
            String u = urlField.trim();
            if (!isHttp(u)) {
                return page("地址要以 http:// 或 https:// 开头", false, null);
            }
            p.setUrl(u);
            p.setCustom("");
            Store.clearCache(ctx);
            notifySaved();
            return page("已保存列表地址：" + u, true, null);
        }

        return page("内容为空，什么都没改", false, null);
    }

    /** 清空自定义内容，回到用地址 / 内置列表。 */
    private String clear() {
        Prefs p = new Prefs(ctx);
        p.setCustom("");
        Store.clearCache(ctx);
        notifySaved();
        return page("已清空自定义内容", true, null);
    }

    private void notifySaved() {
        Runnable r = saveListener;
        if (r != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isHttp(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /** 从 application/x-www-form-urlencoded 里取字段（先按 & 拆，再解码）。 */
    private static String field(String body, String name) {
        if (TextUtils.isEmpty(body)) {
            return "";
        }
        int start = 0;
        while (start <= body.length()) {
            int end = body.indexOf('&', start);
            if (end < 0) {
                end = body.length();
            }
            String pair = body.substring(start, end);
            int eq = pair.indexOf('=');
            if (eq > 0) {
                try {
                    if (name.equals(URLDecoder.decode(pair.substring(0, eq), "UTF-8"))) {
                        return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    }
                } catch (Exception ignored) {
                }
            }
            start = end + 1;
        }
        return "";
    }

    // ------------------------------------------------------------------- 页面

    private String page(String message, boolean ok, String draft) {
        Prefs p = new Prefs(ctx);
        String custom = draft != null ? draft : p.custom();
        String cacheTime = "";
        Store.Cache cache = Store.readCache(ctx);
        if (cache != null && cache.savedAt > 0) {
            cacheTime = Epg.formatClock(cache.savedAt);
        }
        return ConfigPage.render(statusLine, custom, p.url(), cacheTime, message, ok);
    }

    // ------------------------------------------------------------------ 工具

    private static void respond(Socket sock, int code, String html) {
        try {
            byte[] body = html.getBytes("UTF-8");
            OutputStream out = sock.getOutputStream();
            StringBuilder head = new StringBuilder(160);
            head.append("HTTP/1.1 ").append(code).append(code == 200 ? " OK" : " Not Found")
                    .append("\r\n");
            head.append("Content-Type: text/html; charset=utf-8\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("Cache-Control: no-store\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes("UTF-8"));
            out.write(body);
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                return sb.toString();
            }
            if (c != '\r') {
                sb.append((char) c);
            }
            if (sb.length() > 8192) {
                break;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 拿一个局域网 IPv4，优先 192.168.x。 */
    private static String lanIp() {
        try {
            List<String> all = new ArrayList<String>();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        if (ip != null && !all.contains(ip)) {
                            all.add(ip);
                        }
                    }
                }
            }
            for (String ip : all) {
                if (ip.startsWith("192.168.")) {
                    return ip;
                }
            }
            return all.isEmpty() ? null : all.get(0);
        } catch (Throwable t) {
            return null;
        }
    }
}
