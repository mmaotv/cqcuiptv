package com.miaomiaotv.cqcuiptv;

/**
 * 局域网配置页的 HTML（纯 Java，不依赖 Android，方便在电脑上单独预览排版）。
 */
public final class ConfigPage {

    private ConfigPage() {
    }

    public static String render(String status, String custom, String url,
                                String cacheTime, String message, boolean ok) {
        String body = custom == null ? "" : custom;
        StringBuilder b = new StringBuilder(body.length() + 4096);
        b.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        b.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        b.append("<title>轻直播 · 填源</title><style>");
        b.append("*{box-sizing:border-box}"
                + "body{margin:0;padding:20px;background:#0b0d10;color:#edeff2;"
                + "font:15px/1.6 -apple-system,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif}"
                + "h1{font-size:22px;margin:0 0 4px}"
                + "h2{font-size:15px;color:#8a929c;font-weight:400;margin:26px 0 8px}"
                + ".st{color:#8a929c;font-size:13px;margin:0 0 18px}"
                + ".ok{background:#12351f;border:1px solid #1f6b3a;color:#8ee6a5;padding:10px 12px;"
                + "border-radius:8px;margin:0 0 16px;font-size:14px}"
                + ".warn{background:#3a1a1a;border:1px solid #7a2b2b;color:#f3a3a3;padding:10px 12px;"
                + "border-radius:8px;margin:0 0 16px;font-size:14px}"
                + "textarea,input{width:100%;background:#14181d;color:#edeff2;border:1px solid #2a3138;"
                + "border-radius:8px;padding:10px;font:13px/1.5 ui-monospace,Menlo,Consolas,monospace}"
                + "textarea{min-height:230px;resize:vertical}"
                + "button{margin-top:10px;width:100%;padding:13px;border:0;border-radius:8px;"
                + "background:#2f6bff;color:#fff;font-size:15px;font-weight:600}"
                + "button:active{background:#2454cc}"
                + ".tip{color:#6f7883;font-size:12px;margin-top:22px;line-height:1.7}"
                + "</style></head><body>");

        b.append("<h1>轻直播 · 填源</h1>");
        b.append("<p class=\"st\">").append(esc(status));
        if (cacheTime != null && cacheTime.length() > 0) {
            b.append("　·　缓存于 ").append(cacheTime);
        }
        b.append("</p>");

        if (message != null && message.length() > 0) {
            b.append("<p class=\"").append(ok ? "ok" : "warn").append("\">")
                    .append(esc(message)).append("</p>");
        }

        b.append("<form method=\"post\" action=\"/save\">");
        b.append("<h2>粘贴 M3U 内容（整段，以 #EXTM3U 开头）</h2>");
        b.append("<textarea name=\"content\" spellcheck=\"false\" placeholder=\"#EXTM3U&#10;")
                .append("#EXTINF:-1 tvg-id=&quot;CCTV1&quot; group-title=&quot;央视&quot;,CCTV1 综合&#10;")
                .append("rtsp://...\">").append(esc(body)).append("</textarea>");
        b.append("<button type=\"submit\">保存内容并应用</button></form>");

        b.append("<form method=\"post\" action=\"/save\">");
        b.append("<h2>或者填一个播放列表地址</h2>");
        b.append("<input name=\"url\" spellcheck=\"false\" value=\"").append(esc(url))
                .append("\" placeholder=\"https://example.com/my.m3u\">");
        b.append("<button type=\"submit\">保存地址并应用</button></form>");

        b.append("<p class=\"tip\">保存后电视端会自动重新加载，不用重启。"
                + "粘贴内容优先于地址；想让地址 / 内置列表生效，先点下面的「清空」。<br>"
                + "本页面只在局域网内可访问，同网段谁都能改，别把端口映射到公网。</p>");
        b.append("<form method=\"post\" action=\"/clear\">"
                + "<button type=\"submit\" style=\"background:#242a31\">清空自定义内容（改用地址 / 内置列表）"
                + "</button></form>");
        b.append("</body></html>");
        return b.toString();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 32);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    b.append("&amp;");
                    break;
                case '<':
                    b.append("&lt;");
                    break;
                case '>':
                    b.append("&gt;");
                    break;
                case '"':
                    b.append("&quot;");
                    break;
                default:
                    b.append(c);
            }
        }
        return b.toString();
    }
}
