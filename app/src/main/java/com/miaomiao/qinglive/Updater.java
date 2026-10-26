package com.miaomiao.qinglive;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 检查更新：读仓库根目录的 update.json（与播放列表同一个 raw.githubusercontent 通道，
 * 墙内可达性和列表一致），拿到新版本号、更新日志、安装包地址。
 *
 * 是否升级完全由用户决定：这里只做「检查 → 展示日志 → 用户点更新才下载安装」，
 * 不做后台自动下载、不静默安装。
 */
public final class Updater {

    /** 更新清单地址 */
    public static final String MANIFEST =
            "https://raw.githubusercontent.com/mmaotv/cqcuiptv/main/update.json";

    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 15000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 清单内容 + 和本机版本的比较结果 */
    public static final class Info {
        public String versionName = "";
        public int versionCode = 0;
        public String date = "";
        /** 已排版好的更新日志（每行以「· 」开头） */
        public String notes = "";
        public String apkUrl = "";
        public long sizeBytes = 0;
        /** 清单版本比本机新 */
        public boolean newer;
    }

    public interface Callback {
        /** 成功时 error 为 null；失败时 info 为 null */
        void onResult(Info info, String error);
    }

    private Updater() {
    }

    // ------------------------------------------------------------------ 本机版本

    public static String versionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    public static int versionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) {
                return (int) pi.getLongVersionCode();
            }
            //noinspection deprecation
            return pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 检查

    /** 后台线程拉清单，结果回主线程。 */
    public static void check(final Context ctx, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Info info = null;
                String error = null;
                try {
                    info = fetch();
                    info.newer = isNewer(app, info);
                } catch (Throwable t) {
                    error = friendly(t);
                    info = null;
                }
                final Info fi = info;
                final String fe = error;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(fi, fe);
                    }
                });
            }
        }, "update-check").start();
    }

    private static Info fetch() throws Exception {
        // 复用播放列表的下载器：同样的 UA、超时和错误信息
        String body = Playlist.download(MANIFEST);
        JSONObject o = new JSONObject(body);

        Info info = new Info();
        info.versionName = o.optString("versionName", "").trim();
        info.versionCode = o.optInt("versionCode", 0);
        info.date = o.optString("date", "").trim();
        info.apkUrl = o.optString("apk", "").trim();
        info.sizeBytes = o.optLong("size", 0);

        StringBuilder sb = new StringBuilder();
        JSONArray arr = o.optJSONArray("notes");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String line = arr.optString(i, "").trim();
                if (line.length() == 0) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("· ").append(line);
            }
        } else {
            sb.append(o.optString("notes", "").trim());
        }
        info.notes = sb.toString();

        if (TextUtils.isEmpty(info.versionName)) {
            throw new IOException("更新清单格式不对（缺少版本号）");
        }
        if (TextUtils.isEmpty(info.apkUrl)) {
            throw new IOException("更新清单格式不对（缺少安装包地址）");
        }
        return info;
    }

    private static boolean isNewer(Context ctx, Info info) {
        int cur = versionCode(ctx);
        if (info.versionCode > 0 && cur > 0) {
            return info.versionCode > cur;
        }
        return compareVersion(info.versionName, versionName(ctx)) > 0;
    }

    /** "3.0.10" > "3.0.9" 这类按段比较（versionCode 缺失时的兜底）。 */
    private static int compareVersion(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] y = b.replaceAll("[^0-9.]", "").split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? parseInt(x[i]) : 0;
            int yi = i < y.length ? parseInt(y[i]) : 0;
            if (xi != yi) {
                return xi > yi ? 1 : -1;
            }
        }
        return 0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String friendly(Throwable t) {
        String msg = t.getMessage();
        if (TextUtils.isEmpty(msg)) {
            msg = t.getClass().getSimpleName();
        }
        if (msg.contains("HTTP 404")) {
            return "更新清单还没发布（HTTP 404）";
        }
        if (t instanceof java.net.SocketTimeoutException || msg.contains("timed out")
                || msg.contains("连接超时")) {
            return "连接超时，稍后再试";
        }
        if (t instanceof java.net.UnknownHostException) {
            return "连不上更新服务器，检查网络";
        }
        return msg;
    }

    // ------------------------------------------------------------------ 更新日志弹窗

    /**
     * 展示更新日志。allowInstall=true 时带「立即更新」按钮（有新版才给），
     * 否则只有「关闭」——用户只是想看看改了什么。
     */
    public static void showNotes(final Activity act, final Info info, boolean allowInstall) {
        if (act.isFinishing()) {
            return;
        }
        final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_update);

        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView title = (TextView) dialog.findViewById(R.id.dv_title);
        TextView sub = (TextView) dialog.findViewById(R.id.dv_sub);
        TextView notes = (TextView) dialog.findViewById(R.id.dv_notes);
        final ScrollView scroll = (ScrollView) dialog.findViewById(R.id.dv_scroll);
        Button primary = (Button) dialog.findViewById(R.id.btn_dv_primary);
        Button close = (Button) dialog.findViewById(R.id.btn_dv_close);

        if (info.newer) {
            title.setText("发现新版本 " + info.versionName);
            StringBuilder sb = new StringBuilder();
            sb.append("当前 ").append(versionName(act));
            if (info.sizeBytes > 0) {
                sb.append("　·　更新包 ").append(sizeText(info.sizeBytes));
            } else if (!TextUtils.isEmpty(info.date)) {
                sb.append("　·　").append(info.date);
            }
            sub.setText(sb.toString());
        } else {
            title.setText("更新日志 · " + info.versionName);
            sub.setText(TextUtils.isEmpty(info.date)
                    ? "已是最新版本" : ("发布于 " + info.date + "　·　已是最新版本"));
        }

        notes.setText(TextUtils.isEmpty(info.notes) ? "这个版本没有写更新说明。" : info.notes);

        if (allowInstall && info.newer) {
            primary.setText("立即更新");
            primary.setVisibility(View.VISIBLE);
            primary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    confirmInstall(act, info);
                }
            });
        } else {
            primary.setVisibility(View.GONE);
        }

        close.setText(allowInstall && info.newer ? "以后再说" : "关闭");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        dialog.show();

        // 弹窗宽度按屏幕比例收一下，日志太长时限制高度让它在内部滚动
        DisplayMetrics dm = act.getResources().getDisplayMetrics();
        int w = (int) Math.min(dm.widthPixels * 0.62f, 560 * dm.density);
        if (win != null) {
            win.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        final int maxH = (int) (dm.heightPixels * 0.46f);
        scroll.post(new Runnable() {
            @Override
            public void run() {
                if (scroll.getHeight() > maxH) {
                    ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                    lp.height = maxH;
                    scroll.setLayoutParams(lp);
                }
            }
        });

        if (primary.getVisibility() == View.VISIBLE) {
            primary.requestFocus();
        } else {
            close.requestFocus();
        }
    }

    // ------------------------------------------------------------------ 安装

    /** Android 8+ 装 APK 要「安装未知应用」授权，没授权先引导去开。 */
    private static boolean canInstall(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return ctx.getPackageManager().canRequestPackageInstalls();
            } catch (Throwable t) {
                return true;
            }
        }
        return true;
    }

    private static void confirmInstall(final Activity act, final Info info) {
        if (!canInstall(act)) {
            final Dialog d = new Dialog(act);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            d.setContentView(R.layout.dialog_update);
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawableResource(android.R.color.transparent);
            }
            ((TextView) d.findViewById(R.id.dv_title)).setText("需要先允许安装应用");
            ((TextView) d.findViewById(R.id.dv_sub)).setText("系统设置里给「轻直播」打开「安装未知应用」");
            ((TextView) d.findViewById(R.id.dv_notes))
                    .setText("打开后会回到这里，再次点「检查更新」→「立即更新」即可。");
            d.findViewById(R.id.dv_scroll).setVisibility(View.GONE);
            Button primary = (Button) d.findViewById(R.id.btn_dv_primary);
            Button close = (Button) d.findViewById(R.id.btn_dv_close);
            primary.setText("去设置");
            primary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    openInstallSettings(act);
                }
            });
            close.setText("取消");
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                }
            });
            d.show();
            DisplayMetrics dm = act.getResources().getDisplayMetrics();
            if (w != null) {
                w.setLayout((int) Math.min(dm.widthPixels * 0.6f, 520 * dm.density),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            primary.requestFocus();
            return;
        }
        startDownload(act, info);
    }

    private static void openInstallSettings(Activity act) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            i.setData(Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
        } catch (Throwable t) {
            try {
                act.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void startDownload(final Activity act, final Info info) {
        final ProgressDialog pd = new ProgressDialog(act);
        pd.setMessage("正在下载 " + info.versionName + "…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setProgress(0);
        pd.setCancelable(false);
        pd.setCanceledOnTouchOutside(false);
        try {
            pd.show();
        } catch (Throwable ignored) {
            // 页面已经销毁之类，直接放弃这次下载
            return;
        }

        final Context app = act.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File apk = null;
                String error = null;
                try {
                    apk = download(app, info, pd);
                } catch (Throwable t) {
                    error = friendly(t);
                }
                final File f = apk;
                final String e = error;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (Throwable ignored) {
                        }
                        if (f == null) {
                            toast(act, "下载失败：" + e);
                            return;
                        }
                        install(act, f);
                    }
                });
            }
        }, "update-download").start();
    }

    private static File download(Context ctx, Info info, final ProgressDialog pd) throws Exception {
        File dir = new File(ctx.getCacheDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建临时目录");
        }
        // 清掉上次失败留下的残包
        File[] old = dir.listFiles();
        if (old != null) {
            for (File o : old) {
                //noinspection ResultOfMethodCallIgnored
                o.delete();
            }
        }
        File out = new File(dir, "LightLive.apk");

        HttpURLConnection conn = (HttpURLConnection) new URL(info.apkUrl).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "LightLive/" + versionName(ctx));
        try {
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                throw new IOException("HTTP " + code);
            }
            long total = info.sizeBytes > 0 ? info.sizeBytes : conn.getContentLength();
            long done = 0;
            long lastPush = 0;
            InputStream in = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(out);
            try {
                byte[] buf = new byte[32 * 1024];
                int n;
                int first = -1, second = -1;
                while ((n = in.read(buf)) > 0) {
                    if (first < 0 && n > 0) {
                        first = buf[0] & 0xFF;
                        if (n > 1) {
                            second = buf[1] & 0xFF;
                        }
                    }
                    fos.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastPush > 120) {
                        lastPush = now;
                        final int pct = total > 0
                                ? (int) Math.min(100, done * 100 / total) : 0;
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    pd.setProgress(pct);
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                    }
                }
                fos.flush();
                // APK 是 zip，头两字节必为 PK；否则多半是被网络劫持换成了网页
                if (first != 'P' || (second >= 0 && second != 'K')) {
                    throw new IOException("下载到的不是安装包");
                }
            } finally {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } finally {
            conn.disconnect();
        }

        if (out.length() < 60 * 1024) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new IOException("安装包不完整");
        }
        // 让安装器认得这个文件名
        File named = new File(dir, "LightLive-" + info.versionName + ".apk");
        //noinspection ResultOfMethodCallIgnored
        out.renameTo(named);
        return named.exists() ? named : out;
    }

    private static void install(Activity act, File apk) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Uri uri;
            if (Build.VERSION.SDK_INT >= 24) {
                uri = ApkProvider.uriFor(apk);
            } else {
                // Android 6 及以下没有 file:// 外传限制，直接给路径最稳
                uri = Uri.fromFile(apk);
            }
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            act.startActivity(i);
        } catch (Throwable t) {
            toast(act, "无法启动安装程序：" + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static String sizeText(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        return (bytes / 1024) + " KB";
    }

    private static void toast(final Activity act, final String text) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (act.isFinishing()) {
                    return;
                }
                android.widget.Toast.makeText(act, text,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }
}
