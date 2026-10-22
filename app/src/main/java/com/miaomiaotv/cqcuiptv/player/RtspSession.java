package com.miaomiaotv.cqcuiptv.player;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;

/**
 * 最小 RTSP 客户端：DESCRIBE（含 302 跳转）/ SETUP / PLAY / 保活。
 * <p>
 * 只处理 MPEG-TS 单轨（SDP 里 m=video 0 RTP/AVP 33），这是国内 IPTV 的通用形态。
 * 优先用 TCP 交织（RTP/AVP/TCP;interleaved=0-1）——很多 IPTV 服务器会 461 拒绝 UDP，
 * 只有 TCP 才收；TCP 不行再退回 UDP。
 */
public final class RtspSession {

    private String url;
    private Socket socket;
    private BufferedInputStream in;
    private OutputStream out;
    private int cseq = 1;
    private String session;
    private final String userAgent = "LiveTV/2.0";

    private DatagramSocket udpSocket;
    private boolean tcpMode = true;
    /** PLAY 之后同一条连接上既有 RTP 又有（保活）响应，读线程统一处理 */
    private volatile boolean streaming;

    private String trackUrl;

    public boolean isTcp() {
        return tcpMode;
    }

    public DatagramSocket udpSocket() {
        return udpSocket;
    }

    /** 打开 RTSP 会话并完成 SETUP，返回后即可 PLAY。 */
    public void open(String rawUrl) throws IOException {
        url = rawUrl;
        connect();
        String sdp = describe();
        String control = findTsControl(sdp);
        if (control == null) {
            throw new IOException("不是 MPEG-TS 流");
        }
        trackUrl = resolve(control);
        setup();
    }

    private void connect() throws IOException {
        closeSocket();
        URI u = URI.create(url);
        int port = u.getPort() > 0 ? u.getPort() : 554;
        Socket s = new Socket();
        s.setSoTimeout(8000);
        s.setTcpNoDelay(true);
        s.setReceiveBufferSize(512 * 1024);
        s.connect(new InetSocketAddress(InetAddress.getByName(u.getHost()), port), 8000);
        socket = s;
        in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
        out = s.getOutputStream();
    }

    private String describe() throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            Response r = request("DESCRIBE", url, "Accept: application/sdp\r\n");
            if (r.code == 200) {
                return r.body;
            }
            if (r.code >= 300 && r.code < 400 && r.header("location") != null) {
                url = r.header("location").trim();
                connect();
                continue;
            }
            throw new IOException("DESCRIBE 失败：" + r.code);
        }
        throw new IOException("跳转次数过多");
    }

    /** 从 SDP 里找出 MP2T 轨道的 control。 */
    private String findTsControl(String sdp) {
        String[] lines = sdp.replace("\r", "").split("\n");
        boolean inTs = false;
        String control = null;
        boolean anyTs = false;
        for (String line : lines) {
            String l = line.trim();
            if (l.startsWith("m=")) {
                inTs = l.contains("MP2T") || l.matches("m=video\\s+\\d+\\s+RTP/AVP\\s+33.*");
                if (inTs) {
                    anyTs = true;
                }
                continue;
            }
            if (inTs && l.startsWith("a=control:")) {
                control = l.substring("a=control:".length()).trim();
            }
        }
        if (control == null && anyTs) {
            return "";
        }
        return control;
    }

    private String resolve(String control) {
        if (control == null || control.length() == 0 || control.equals("*")) {
            return url;
        }
        if (control.startsWith("rtsp://")) {
            return control;
        }
        int q = url.indexOf('?');
        String base = q >= 0 ? url.substring(0, q) : url;
        if (control.startsWith("/")) {
            URI u = URI.create(base);
            return "rtsp://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + control;
        }
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        if (control.startsWith("./")) {
            control = control.substring(2);
        }
        return base + control;
    }

    private void setup() throws IOException {
        Response r = request("SETUP", trackUrl,
                "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
        if (r.code == 200) {
            tcpMode = true;
            readSession(r);
            return;
        }
        // 退回 UDP
        Exception last = new IOException("SETUP 失败：" + r.code);
        for (int i = 0; i < 6; i++) {
            int p = 20000 + (int) (Math.random() * 20000);
            DatagramSocket ds;
            try {
                ds = new DatagramSocket(p);
                ds.setSoTimeout(1200);
                ds.setReceiveBufferSize(2 * 1024 * 1024);
            } catch (IOException e) {
                continue;
            }
            Response ur = request("SETUP", trackUrl,
                    "Transport: RTP/AVP/UDP;unicast;client_port=" + p + "-" + (p + 1) + "\r\n");
            if (ur.code == 200) {
                udpSocket = ds;
                tcpMode = false;
                readSession(ur);
                return;
            }
            ds.close();
            last = new IOException("SETUP 失败：" + ur.code);
            if (ur.code != 461 && ur.code != 400) {
                break;
            }
        }
        throw last instanceof IOException ? (IOException) last : new IOException("SETUP 失败");
    }

    private void readSession(Response r) {
        String sess = r.header("session");
        if (sess != null) {
            int semi = sess.indexOf(';');
            session = semi >= 0 ? sess.substring(0, semi) : sess.trim();
        }
    }

    public void play() throws IOException {
        Response r = request("PLAY", url, "Range: npt=0.000-\r\n");
        if (r.code != 200 && r.code != 204) {
            r = request("PLAY", trackUrl, "Range: npt=0.000-\r\n");
        }
        if (r.code != 200 && r.code != 204) {
            throw new IOException("PLAY 失败：" + r.code);
        }
        streaming = true;
    }

    /** PLAY 之后只发不读，响应由读线程顺手丢掉。 */
    public void keepAlive() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("OPTIONS ").append(url).append(" RTSP/1.0\r\n");
            sb.append("CSeq: ").append(cseq++).append("\r\n");
            sb.append("User-Agent: ").append(userAgent).append("\r\n");
            if (session != null) {
                sb.append("Session: ").append(session).append("\r\n");
            }
            sb.append("\r\n");
            synchronized (this) {
                out.write(sb.toString().getBytes("UTF-8"));
                out.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    private byte[] packet = new byte[64 * 1024];

    /**
     * TCP 交织模式下读一个 RTP 包；返回包长度（含 RTP 头），0 表示读到的是控制响应或 RTCP，
     * -1 表示连接结束。
     */
    public int readRtpPacket(byte[] target) throws IOException {
        while (true) {
            int first = in.read();
            if (first < 0) {
                return -1;
            }
            if (first == '$') {
                int channel = in.read();
                int hi = in.read();
                int lo = in.read();
                if (channel < 0 || hi < 0 || lo < 0) {
                    return -1;
                }
                int len = (hi << 8) | lo;
                if (len <= 0) {
                    continue;
                }
                if (len > packet.length) {
                    packet = new byte[len];
                }
                int read = 0;
                while (read < len) {
                    int n = in.read(packet, read, len - read);
                    if (n <= 0) {
                        return -1;
                    }
                    read += n;
                }
                if (channel != 0) {
                    continue;       // RTCP
                }
                int copy = Math.min(len, target.length);
                System.arraycopy(packet, 0, target, 0, copy);
                return copy;
            }
            discardResponse(first);
        }
    }

    /** 读掉一段 RTSP 响应（保活回包），避免和 RTP 数据粘连。 */
    private void discardResponse(int firstByte) throws IOException {
        StringBuilder line = new StringBuilder();
        line.append((char) firstByte);
        int contentLength = 0;
        while (true) {
            String l = readLine(line);
            line.setLength(0);
            if (l == null || l.length() == 0) {
                break;
            }
            int colon = l.indexOf(':');
            if (colon > 0 && "content-length".equalsIgnoreCase(l.substring(0, colon).trim())) {
                try {
                    contentLength = Integer.parseInt(l.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (int i = 0; i < contentLength; i++) {
            if (in.read() < 0) {
                return;
            }
        }
    }

    public void close() {
        if (streaming && session != null) {
            try {
                request("TEARDOWN", url, "");
            } catch (Throwable ignored) {
            }
        }
        streaming = false;
        if (udpSocket != null) {
            try {
                udpSocket.close();
            } catch (Throwable ignored) {
            }
            udpSocket = null;
        }
        closeSocket();
    }

    private void closeSocket() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Throwable ignored) {
        }
        socket = null;
    }

    // ------------------------------------------------------------------ RTSP 层

    private static final class Response {
        int code;
        String body = "";
        final HashMap<String, String> headers = new HashMap<String, String>();

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }
    }

    private Response request(String method, String target, String extraHeaders) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(target).append(" RTSP/1.0\r\n");
        sb.append("CSeq: ").append(cseq++).append("\r\n");
        sb.append("User-Agent: ").append(userAgent).append("\r\n");
        if (session != null) {
            sb.append("Session: ").append(session).append("\r\n");
        }
        sb.append(extraHeaders);
        sb.append("\r\n");
        synchronized (this) {
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
            return readResponse();
        }
    }

    private Response readResponse() throws IOException {
        Response r = new Response();
        String status = readLine(null);
        if (status == null) {
            throw new IOException("连接被关闭");
        }
        String[] parts = status.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("异常响应：" + status);
        }
        r.code = Integer.parseInt(parts[1].trim());
        int contentLength = 0;
        while (true) {
            String line = readLine(null);
            if (line == null || line.length() == 0) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String val = line.substring(colon + 1).trim();
                r.headers.put(key, val);
                if ("content-length".equals(key)) {
                    try {
                        contentLength = Integer.parseInt(val);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (contentLength > 0) {
            byte[] body = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(body, read, contentLength - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            r.body = new String(body, 0, read, "UTF-8");
        }
        return r;
    }

    private String readLine(StringBuilder prefix) throws IOException {
        StringBuilder sb = prefix != null ? prefix : new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) {
                return sb.length() == 0 ? null : sb.toString();
            }
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }
}
