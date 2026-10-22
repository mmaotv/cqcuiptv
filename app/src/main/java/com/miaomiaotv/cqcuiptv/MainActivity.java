package com.miaomiaotv.cqcuiptv;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.miaomiaotv.cqcuiptv.player.LivePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 单 Activity 直播播放器。
 * <p>
 * rtsp:// 走自带播放引擎（RTSP/TCP 交织 → TS → MediaCodec 硬解 + MP2 软解），
 * 其它协议（http / m3u8 / 自定义列表里的地址）仍交给系统 MediaPlayer。
 * 遥控器：数字键直达台号、↑↓ 换台、OK 分类列表、INFO 节目单、MENU 设置。
 */
public class MainActivity extends Activity implements
        SurfaceHolder.Callback,
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnInfoListener,
        AdapterView.OnItemClickListener,
        AdapterView.OnItemSelectedListener {

    /** 数字键连按的判定窗口（毫秒） */
    private static final long NUMBER_TIMEOUT = 1100L;
    /** 长时间没出画就当失败 */
    private static final long PREPARE_TIMEOUT = 15000L;
    /** OK 键按住多久算长按 */
    private static final long LONG_PRESS = 700L;
    /** 底部默认提示语 */
    private static final String HINT_DEFAULT =
            "数字键 选台　↑↓ 换台　OK 频道列表　INFO 节目单　MENU 设置";
    /** raw.githubusercontent 在国内经常拉不动，兜底走 jsdelivr 镜像 */
    private static final String MIRROR_URL =
            "https://cdn.jsdelivr.net/gh/mmaotv/cqcuiptv@main/cqcu-tv.m3u";
    /** 在线列表的单次超时（DNS 卡住也认栽，别让用户干等） */
    private static final long FETCH_TIMEOUT = 12000L;

    private SurfaceView surfaceView;
    private SurfaceHolder holder;
    private boolean surfaceReady;

    /** 触摸手势层（平板 / 触摸大屏用） */
    private View gestureLayer;
    private GestureDetector gestures;
    private float accX;
    private float accY;
    private float swipeX;
    private float swipeY;
    /** 一次按下只认一次滑动动作，否则滑得远了会连着换好几个台 */
    private boolean gestureDone;

    /** rtsp 源 */
    private LivePlayer livePlayer;
    /** 非 rtsp 源 */
    private MediaPlayer player;
    /** 每次起播 +1，用来丢弃上一个频道残留的回调 */
    private int playGen;

    private boolean playing;

    private View osd;
    private TextView osdNum;
    private TextView osdName;
    private TextView osdGroup;
    private TextView osdEpg;
    private TextView osdMeta;
    private TextView notice;
    private TextView hint;

    private View panel;
    private ListView groupList;
    private ListView list;
    private GroupAdapter groupAdapter;
    private ChannelAdapter adapter;
    private boolean panelOpen;

    private View epgPanel;
    private ListView epgList;
    private TextView epgChannelView;
    private TextView epgStatus;
    private EpgAdapter epgAdapter;
    private boolean epgOpen;

    private final List<Channel> channels = new ArrayList<Channel>();
    private final List<Group> groups = new ArrayList<Group>();
    /** 当前右栏展示的频道（分类过滤后的视图） */
    private final List<Channel> shown = new ArrayList<Channel>();
    /** shown 里每一行对应的全局下标，决定台号 */
    private int[] shownNumbers = new int[0];
    private int groupIndex;
    private int index;

    private Epg epg;
    private volatile boolean epgLoading;
    private final List<String> epgSources = new ArrayList<String>();

    private Prefs prefs;
    private final Handler handler = new Handler();

    private String numBuf = "";
    private long okDownAt = -1;

    private final Runnable commitNumber = new Runnable() {
        @Override
        public void run() {
            commitNumber();
        }
    };

    private final Runnable prepareWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!playing) {
                showNotice("连接超时，无法打开这个频道\n按 OK 重试，或按 ↓ 换下一个台");
            }
        }
    };

    private final Runnable hideOsd = new Runnable() {
        @Override
        public void run() {
            osd.setVisibility(View.GONE);
        }
    };

    private final Runnable hideHint = new Runnable() {
        @Override
        public void run() {
            hint.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);

        surfaceView = (SurfaceView) findViewById(R.id.surface);
        holder = surfaceView.getHolder();
        holder.addCallback(this);

        osd = findViewById(R.id.osd);
        osdNum = (TextView) findViewById(R.id.osd_number);
        osdName = (TextView) findViewById(R.id.osd_name);
        osdGroup = (TextView) findViewById(R.id.osd_group);
        osdEpg = (TextView) findViewById(R.id.osd_epg);
        osdMeta = (TextView) findViewById(R.id.osd_meta);

        notice = (TextView) findViewById(R.id.notice);
        hint = (TextView) findViewById(R.id.hint);

        panel = findViewById(R.id.panel);
        groupList = (ListView) findViewById(R.id.group_list);
        list = (ListView) findViewById(R.id.list);
        groupAdapter = new GroupAdapter(this);
        groupList.setAdapter(groupAdapter);
        groupList.setOnItemSelectedListener(this);
        groupList.setOnItemClickListener(this);
        adapter = new ChannelAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(this);

        epgPanel = findViewById(R.id.epg_panel);
        epgList = (ListView) findViewById(R.id.epg_list);
        epgChannelView = (TextView) findViewById(R.id.epg_channel);
        epgStatus = (TextView) findViewById(R.id.epg_status);
        epgAdapter = new EpgAdapter(this);
        epgList.setAdapter(epgAdapter);
        epgList.setOnItemClickListener(this);

        setupGestures();
        fitPanels();
        applyImmersive();

        // 局域网填源保存后，立刻重载
        ConfigServer.setSaveListener(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showNotice("收到新的播放列表，正在重新加载…");
                        stopPlayback();
                        channels.clear();
                        groups.clear();
                        loadPlaylist();
                    }
                });
            }
        });
        ConfigServer.sync(this);

        loadPlaylist();
    }

    // ------------------------------------------------------- 触摸 / 平板适配

    private void setupGestures() {
        float d = getResources().getDisplayMetrics().density;
        swipeX = 96f * d;
        swipeY = 52f * d;
        gestureLayer = findViewById(R.id.gesture);
        gestures = new GestureDetector(this, new TouchHandler());
        gestureLayer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return gestures.onTouchEvent(e);
            }
        });
    }

    /** 面板按屏幕宽度收一下，平板上不至于铺满、小屏上不至于超出。 */
    private void fitPanels() {
        int screen = getResources().getDisplayMetrics().widthPixels;
        int max = (int) (screen * 0.92f);
        clampWidth(panel, (int) (700 * getResources().getDisplayMetrics().density), max);
        clampWidth(epgPanel, (int) (620 * getResources().getDisplayMetrics().density), max);
    }

    private static void clampWidth(View v, int design, int max) {
        android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) {
            return;
        }
        lp.width = Math.min(design, max);
        v.setLayoutParams(lp);
    }

    /** 触摸设备上把系统栏藏掉，画面才是真的全屏。 */
    private void applyImmersive() {
        if (Build.VERSION.SDK_INT >= 19) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
        }
    }

    /** 触摸手势：单击显隐台标、双击列表、滑动换台/音量、长按设置。 */
    private final class TouchHandler extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onDown(MotionEvent e) {
            accX = 0;
            accY = 0;
            gestureDone = false;
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (panelOpen) {
                closePanel();
                return true;
            }
            if (epgOpen) {
                closeEpg();
                return true;
            }
            int w = gestureLayer == null ? 0 : gestureLayer.getWidth();
            float x = e.getX();
            // 左边换上一个台，右边换下一个台，中间呼出频道列表——不用记手势也能用
            if (w > 0 && x < w * 0.22f) {
                step(-1);
                return true;
            }
            if (w > 0 && x > w * 0.78f) {
                step(1);
                return true;
            }
            openPanel();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (panelOpen) {
                closePanel();
            } else if (epgOpen) {
                closeEpg();
            } else {
                openEpg();
            }
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            if (!panelOpen && !epgOpen) {
                openSettings();
            }
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (e1 == null || panelOpen || epgOpen || gestureDone) {
                return false;
            }
            accX += dx;
            accY += dy;
            if (Math.abs(accX) >= swipeX && Math.abs(accX) > Math.abs(accY) * 1.3f) {
                // 向左滑是下一台，向右滑是上一台
                gestureDone = true;
                step(accX > 0 ? 1 : -1);
                accX = 0;
                accY = 0;
            } else if (Math.abs(accY) >= swipeY && Math.abs(accY) > Math.abs(accX) * 1.3f) {
                // 上滑加音量，下滑减音量
                gestureDone = true;
                volume(accY > 0 ? 1 : -1);
                accX = 0;
                accY = 0;
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- 播放列表

    private void loadPlaylist() {
        showNotice("正在加载频道列表…");

        // 1) 先用打包在 APK 里的列表秒开，不等网络
        new Thread(new Runnable() {
            @Override
            public void run() {
                Playlist.Loaded base = null;
                try {
                    base = Playlist.loadAll("", MainActivity.this);
                } catch (Exception ignored) {
                }
                final Playlist.Loaded b = base;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        applyChannels(b, null);
                    }
                });
            }
        }).start();

        // 2) 自定义 M3U > 本地缓存 > 在线地址
        new Thread(new Runnable() {
            @Override
            public void run() {
                loadRemote();
            }
        }, "playlist").start();
    }

    /**
     * 取列表的顺序：自定义 M3U → 本地缓存（未过期就直接用，不联网）→ 在线拉取并写缓存。
     */
    private void loadRemote() {
        String custom = prefs.custom().trim();
        if (custom.length() > 0) {
            Playlist.Loaded r = Playlist.parseAll(custom);
            if (r.channels.isEmpty()) {
                postHint("自定义列表里没有频道　·　MENU 检查一下");
            } else {
                postApply(r, null);
            }
            return;
        }

        String url = prefs.url();
        Store.Cache cache = Store.readCache(this);
        boolean usable = cache != null && !cache.isEmpty() && url.equals(cache.url);
        if (usable) {
            Playlist.Loaded r = Playlist.parseAll(cache.body);
            if (r.channels.isEmpty()) {
                usable = false;
            } else {
                postApply(r, null);
                if (prefs.cacheFresh(cache.savedAt)) {
                    return;                 // 还没到期，这次不联网
                }
            }
        }
        if (!usable && cache != null) {
            Store.clearCache(this);         // 地址改过了，旧缓存作废
        }

        String text = null;
        String error = null;
        try {
            text = fetchText(url);
            if (TextUtils.isEmpty(text)) {
                text = null;
                error = "列表是空的";
            }
        } catch (Exception e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        // 用的还是默认地址、而且没拉到时，换镜像再试一次
        if (text == null && url.equals(prefs.defaultUrl())) {
            try {
                String mirror = fetchText(MIRROR_URL);
                if (!TextUtils.isEmpty(mirror)) {
                    text = mirror;
                    error = null;
                }
            } catch (Exception ignored) {
            }
        }
        if (text == null) {
            if (error != null) {
                postHint("离线：列表没拉到，正在用缓存/内置列表　·　MENU 改地址");
            }
            return;
        }

        Store.writeCache(this, url, text);
        Playlist.Loaded r = Playlist.parseAll(text);
        if (r.channels.isEmpty()) {
            postHint("列表里没有频道　·　MENU 检查地址");
            return;
        }
        postApply(r, null);
    }

    private void postApply(final Playlist.Loaded r, final String error) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyChannels(r, error);
            }
        });
    }

    private void postHint(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showHint(text);
            }
        });
    }

    /** 带总超时的拉取：DNS 卡死时 HttpURLConnection 的 connectTimeout 是管不住的。 */
    private String fetchText(final String url) throws Exception {
        java.util.concurrent.FutureTask<String> task =
                new java.util.concurrent.FutureTask<String>(
                        new java.util.concurrent.Callable<String>() {
                            @Override
                            public String call() throws Exception {
                                return Playlist.download(url);
                            }
                        });
        Thread t = new Thread(task, "fetch");
        t.setDaemon(true);
        t.start();
        try {
            return task.get(FETCH_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            task.cancel(true);
            throw new java.io.IOException("连接超时");
        }
    }

    /** 列表发生变化才重新起播，避免内置列表和在线列表都成功时抖动。 */
    private void applyChannels(Playlist.Loaded loaded, String error) {
        if (loaded == null || loaded.channels.isEmpty()) {
            if (channels.isEmpty()) {
                adapter.setData(channels, null, null);
                showNotice("频道列表是空的\n" + (error == null ? "按 MENU 检查播放列表地址" : error));
            }
            return;
        }
        if (!channels.isEmpty() && sameList(loaded.channels)) {
            hideNotice();
            return;
        }

        String playingName = channels.isEmpty() || index >= channels.size()
                ? null : channels.get(index).name;
        channels.clear();
        channels.addAll(loaded.channels);

        // 分类：M3U 里的 group-title 就是分类，台号仍然是全局的
        groups.clear();
        groups.addAll(Group.build(channels));
        groupAdapter.setData(groups);

        shown.clear();
        shown.addAll(channels);
        shownNumbers = new int[channels.size()];
        for (int i = 0; i < shownNumbers.length; i++) {
            shownNumbers[i] = i;
        }
        adapter.setData(shown, shownNumbers, epg);
        ((TextView) findViewById(R.id.panel_count)).setText(channels.size() + " 个频道");
        ConfigServer.setStatus(channels.size() + " 个频道");
        hideNotice();

        collectEpgSources(loaded.epgUrls);

        int target = 0;
        if (playingName != null) {
            int found = indexOf(playingName);
            if (found >= 0) {
                target = found;
            }
        } else if (prefs.lastIndex() >= 0 && prefs.lastIndex() < channels.size()) {
            target = prefs.lastIndex();
        }
        tune(target);
        startEpgLoad();
    }

    private boolean sameList(List<Channel> other) {
        if (other.size() != channels.size()) {
            return false;
        }
        int last = channels.size() - 1;
        return channels.get(0).name.equals(other.get(0).name)
                && channels.get(last).name.equals(other.get(last).name)
                && channels.get(last / 2).name.equals(other.get(last / 2).name);
    }

    private int indexOf(String name) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private void showHint(String text) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideHint);
        handler.postDelayed(hideHint, 6000);
    }

    // -------------------------------------------------------------------- EPG

    /** 内置几个国内可用的公共源，再加上列表自己声明的 url-tvg。 */
    private void collectEpgSources(List<String> declared) {
        epgSources.clear();
        for (String u : Epg.DEFAULT_URLS) {
            if (!epgSources.contains(u)) {
                epgSources.add(u);
            }
        }
        if (declared != null) {
            for (String u : declared) {
                if (!TextUtils.isEmpty(u) && !epgSources.contains(u)) {
                    epgSources.add(u);
                }
            }
        }
    }

    private void startEpgLoad() {
        if (channels.isEmpty() || epgLoading) {
            return;
        }
        epgLoading = true;
        final Set<String> ids = Epg.wantedIds(channels);
        final List<String> sources = new ArrayList<String>(epgSources);

        new Thread(new Runnable() {
            @Override
            public void run() {
                Epg cached = Epg.fromCache(MainActivity.this, ids);
                if (cached != null && !cached.isEmpty()) {
                    epgLoading = false;
                    final Epg c = cached;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            applyEpg(c);
                        }
                    });
                    return;
                }
                Epg fresh = Epg.download(MainActivity.this, sources, ids);
                epgLoading = false;
                if (fresh != null && !fresh.isEmpty()) {
                    final Epg f = fresh;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            applyEpg(f);
                        }
                    });
                }
            }
        }, "epg").start();
    }

    private void applyEpg(Epg e) {
        epg = e;
        adapter.setEpg(e);
        updateOsdEpg();
        if (epgOpen) {
            fillEpg();
        }
    }

    private static String epgId(Channel c) {
        return TextUtils.isEmpty(c.tvgId) ? c.name : c.tvgId;
    }

    private void updateOsdEpg() {
        if (epg == null || channels.isEmpty() || index >= channels.size()) {
            osdEpg.setVisibility(View.GONE);
            return;
        }
        String id = epgId(channels.get(index));
        Epg.Programme now = epg.now(id);
        String text = null;
        if (now != null) {
            text = "正在播　" + now.title;
        } else {
            Epg.Programme next = epg.next(id);
            if (next != null) {
                text = Epg.formatClock(next.start) + "　" + next.title;
            }
        }
        if (text == null) {
            osdEpg.setVisibility(View.GONE);
        } else {
            osdEpg.setText(text);
            osdEpg.setVisibility(View.VISIBLE);
        }
    }

    private void openEpg() {
        if (channels.isEmpty()) {
            return;
        }
        if (panelOpen) {
            closePanel();
        }
        epgOpen = true;
        hideNotice();
        handler.removeCallbacks(hideOsd);
        osd.setVisibility(View.GONE);
        fillEpg();
        epgPanel.setVisibility(View.VISIBLE);
        epgList.requestFocus();
    }

    private void closeEpg() {
        epgOpen = false;
        epgPanel.setVisibility(View.GONE);
    }

    private void fillEpg() {
        if (channels.isEmpty() || index >= channels.size()) {
            return;
        }
        Channel c = channels.get(index);
        epgChannelView.setText((index + 1) + "　" + c.name);
        if (epg == null) {
            epgStatus.setText("节目单加载中…");
            epgAdapter.setData(null);
            return;
        }
        List<Epg.Programme> list = epg.list(epgId(c));
        if (list.isEmpty()) {
            epgStatus.setText("这个频道暂时没有节目单");
            epgAdapter.setData(null);
            return;
        }
        epgStatus.setText("共 " + list.size() + " 档节目");
        epgAdapter.setData(list);
        long now = System.currentTimeMillis();
        int target = 0;
        for (int i = 0; i < list.size(); i++) {
            Epg.Programme p = list.get(i);
            if (p.start <= now && now < p.stop) {
                target = i;
                break;
            }
            if (p.start > now) {
                target = i;
                break;
            }
        }
        epgList.setSelection(target);
    }

    // ------------------------------------------------------------------ 换台

    private void tune(int i) {
        if (channels.isEmpty()) {
            return;
        }
        if (i < 0) {
            i = channels.size() - 1;
        }
        if (i >= channels.size()) {
            i = 0;
        }
        index = i;
        prefs.setLastIndex(i);
        if (adapter != null) {
            adapter.setCurrent(i);
        }

        Channel c = channels.get(i);
        showOsd(String.valueOf(i + 1), c.name, c.group);
        hideNotice();
        stopPlayback();

        if (!surfaceReady) {
            return;
        }
        startPlayback(c.url);
        handler.removeCallbacks(prepareWatchdog);
        handler.postDelayed(prepareWatchdog, PREPARE_TIMEOUT);
    }

    private void step(int delta) {
        if (channels.isEmpty()) {
            return;
        }
        tune(index + delta);
    }

    private void onDigit(int digit) {
        if (numBuf.length() >= 3) {
            numBuf = "";
        }
        numBuf = numBuf + digit;
        handler.removeCallbacks(commitNumber);
        handler.removeCallbacks(hideOsd);
        osdNum.setText(numBuf);
        osdName.setText("输入频道号");
        osdGroup.setVisibility(View.GONE);
        osdEpg.setVisibility(View.GONE);
        osdMeta.setVisibility(View.GONE);
        osd.setVisibility(View.VISIBLE);
        osd.setAlpha(1f);
        handler.postDelayed(commitNumber, NUMBER_TIMEOUT);
    }

    private void commitNumber() {
        if (numBuf.length() == 0) {
            return;
        }
        int n;
        try {
            n = Integer.parseInt(numBuf);
        } catch (NumberFormatException e) {
            n = 0;
        }
        numBuf = "";
        if (n >= 1 && n <= channels.size()) {
            tune(n - 1);
        } else {
            showOsd(String.valueOf(n), "没有这个频道", "一共 " + channels.size() + " 个频道");
        }
    }

    private void volume(int direction) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                direction > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int pct = max <= 0 ? 0 : (int) (cur * 100f / max);
        showOsd("音", "音量 " + pct + "%", "");
    }

    // ---------------------------------------------------------------- 播放内核

    private void startPlayback(String url) {
        playing = false;
        if (url != null && url.startsWith("rtsp")) {
            startLivePlayer(url);
        } else {
            startSystemPlayer(url);
        }
    }

    private void startLivePlayer(String url) {
        final int gen = ++playGen;
        LivePlayer p = new LivePlayer(new LivePlayer.Listener() {
            @Override
            public void onPrepared(int width, int height) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != playGen) {
                            return;
                        }
                        onPlaybackStarted();
                    }
                });
            }

            @Override
            public void onMeta(final String line) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != playGen) {
                            return;
                        }
                        osdMeta.setText(line);
                        osdMeta.setVisibility(View.VISIBLE);
                    }
                });
            }

            @Override
            public void onError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != playGen) {
                            return;
                        }
                        playing = false;
                        handler.removeCallbacks(prepareWatchdog);
                        showNotice("这个频道打不开\n" + message
                                + "\n按 OK 重试，或按 ↓ 换下一个台");
                    }
                });
            }
        });
        livePlayer = p;
        p.setSurface(holder);
        p.start(url);
    }

    /** 非 rtsp 的地址（http / m3u8 / 用户自定义）还是交给系统播放器。 */
    private void startSystemPlayer(String url) {
        playGen++;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDisplay(holder);
            mp.setOnPreparedListener(this);
            mp.setOnErrorListener(this);
            mp.setOnCompletionListener(this);
            mp.setOnInfoListener(this);
            mp.setDataSource(url);
            player = mp;
            mp.prepareAsync();
        } catch (Exception e) {
            playing = false;
            showNotice("播放失败：" + e.getMessage() + "\n按 OK 重试");
        }
    }

    private void onPlaybackStarted() {
        playing = true;
        handler.removeCallbacks(prepareWatchdog);
        hideNotice();
        updateOsdEpg();
        handler.removeCallbacks(hideOsd);
        handler.postDelayed(hideOsd, 3000);
        hint.setText(HINT_DEFAULT);
        handler.removeCallbacks(hideHint);
        handler.postDelayed(hideHint, 8000);
    }

    private void stopPlayback() {
        handler.removeCallbacks(prepareWatchdog);
        playing = false;
        LivePlayer lp = livePlayer;
        livePlayer = null;
        if (lp != null) {
            try {
                lp.stop();
            } catch (Throwable ignored) {
            }
        }
        if (player != null) {
            MediaPlayer mp = player;
            player = null;
            try {
                mp.reset();
            } catch (Throwable ignored) {
            }
            try {
                mp.release();
            } catch (Throwable ignored) {
            }
        }
        osdMeta.setText("");
        osdMeta.setVisibility(View.GONE);
    }

    // ------------------------------------------------- MediaPlayer（非 rtsp）

    @Override
    public void onPrepared(MediaPlayer mp) {
        if (mp != player) {
            return;
        }
        try {
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        } catch (Throwable ignored) {
        }
        mp.start();
        int w = mp.getVideoWidth();
        int h = mp.getVideoHeight();
        osdMeta.setText(w > 0 && h > 0 ? ("系统解码器 " + w + "x" + h) : "系统解码器");
        osdMeta.setVisibility(View.VISIBLE);
        onPlaybackStarted();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        if (mp != player) {
            return true;
        }
        playing = false;
        handler.removeCallbacks(prepareWatchdog);
        showNotice("这个频道打不开（" + what + "/" + extra + "）\n"
                + "按 OK 重试，或按 ↓ 换下一个台");
        return true;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        if (mp == player) {
            playing = false;
            showNotice("播放结束\n按 OK 重新播放，或按 ↓ 换下一个台");
        }
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        if (mp != player) {
            return false;
        }
        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
            showNotice("缓冲中…");
        } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
            hideNotice();
        }
        return false;
    }

    // ------------------------------------------------------------- 界面状态

    private void showOsd(String number, String title, String group) {
        osdNum.setText(number);
        osdName.setText(title);
        if (TextUtils.isEmpty(group)) {
            osdGroup.setVisibility(View.GONE);
        } else {
            osdGroup.setText(group);
            osdGroup.setVisibility(View.VISIBLE);
        }
        osdEpg.setVisibility(View.GONE);
        osd.setVisibility(View.VISIBLE);
        osd.setAlpha(1f);
        handler.removeCallbacks(hideOsd);
        handler.postDelayed(hideOsd, 3000);
    }

    private void showNotice(String text) {
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
    }

    private void hideNotice() {
        notice.setVisibility(View.GONE);
    }

    private void openPanel() {
        if (channels.isEmpty()) {
            return;
        }
        if (epgOpen) {
            closeEpg();
        }
        if (groups.isEmpty()) {
            groups.addAll(Group.build(channels));
            groupAdapter.setData(groups);
        }
        panelOpen = true;
        hideNotice();
        handler.removeCallbacks(hideOsd);
        osd.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);

        int gi = groupOf(index);
        groupList.setSelection(gi);
        showGroup(gi);
        int local = localIndexOf(index);
        if (local >= 0) {
            list.setSelection(local);
        }
        groupList.requestFocus();
    }

    private void closePanel() {
        panelOpen = false;
        panel.setVisibility(View.GONE);
    }

    /** 当前频道落在哪个分类里（跳过第一项「全部」）。 */
    private int groupOf(int globalIndex) {
        for (int i = 1; i < groups.size(); i++) {
            if (groups.get(i).indices.contains(Integer.valueOf(globalIndex))) {
                return i;
            }
        }
        return 0;
    }

    private int localIndexOf(int globalIndex) {
        for (int i = 0; i < shownNumbers.length; i++) {
            if (shownNumbers[i] == globalIndex) {
                return i;
            }
        }
        return -1;
    }

    /** 右栏切到某个分类。 */
    private void showGroup(int gi) {
        if (gi < 0 || gi >= groups.size()) {
            return;
        }
        groupIndex = gi;
        groupAdapter.setSelected(gi);
        Group g = groups.get(gi);
        shown.clear();
        shownNumbers = new int[g.indices.size()];
        for (int i = 0; i < g.indices.size(); i++) {
            int global = g.indices.get(i);
            shownNumbers[i] = global;
            shown.add(channels.get(global));
        }
        adapter.setData(shown, shownNumbers, epg);
        adapter.setCurrent(index);
        list.setSelection(0);
    }

    private void openSettings() {
        Intent i = new Intent(this, SettingsActivity.class);
        startActivityForResult(i, 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK) {
            stopPlayback();
            channels.clear();
            groups.clear();
            loadPlaylist();
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        int rid = parent.getId();
        if (rid == R.id.list) {
            int global = position < shownNumbers.length ? shownNumbers[position] : position;
            closePanel();
            tune(global);
        } else if (rid == R.id.group_list) {
            // 触摸点分类要立刻切右栏；遥控器靠 onItemSelected，但触摸模式不会有焦点变化
            showGroup(position);
            if (list.getCount() > 0) {
                list.requestFocus();
            }
        } else if (rid == R.id.epg_list) {
            closeEpg();
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (parent.getId() == R.id.group_list && panelOpen) {
            showGroup(position);
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }

    // ------------------------------------------------------------ 遥控器按键

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        final int code = e.getKeyCode();
        final boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        // 数字键：任何界面下都直达频道号
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            if (down) {
                if (panelOpen) {
                    closePanel();
                }
                if (epgOpen) {
                    closeEpg();
                }
                onDigit(code - KeyEvent.KEYCODE_0);
            }
            return true;
        }

        switch (code) {
            case KeyEvent.KEYCODE_MENU:
                if (down) {
                    openSettings();
                }
                return true;

            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_GUIDE:
                if (down) {
                    if (epgOpen) {
                        closeEpg();
                    } else {
                        openEpg();
                    }
                }
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (down && panelOpen) {
                    closePanel();
                    return true;
                }
                if (down && epgOpen) {
                    closeEpg();
                    return true;
                }
                return super.dispatchKeyEvent(e);

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (panelOpen) {
                    // 交给列表自己处理：左栏 OK = 进入右栏，右栏 OK = 换台
                    if (groupList.hasFocus() || list.hasFocus()) {
                        return super.dispatchKeyEvent(e);
                    }
                    closePanel();
                    return true;
                }
                if (epgOpen) {
                    if (epgList.hasFocus()) {
                        return super.dispatchKeyEvent(e);
                    }
                    closeEpg();
                    return true;
                }
                if (down) {
                    okDownAt = System.currentTimeMillis();
                    return true;
                }
                boolean longPress = okDownAt > 0
                        && System.currentTimeMillis() - okDownAt >= LONG_PRESS;
                okDownAt = -1;
                if (longPress) {
                    openSettings();
                } else if (notice.getVisibility() == View.VISIBLE) {
                    tune(index);
                } else {
                    openPanel();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (panelOpen || epgOpen) {
                    return super.dispatchKeyEvent(e);
                }
                if (down) {
                    step(-1);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (panelOpen || epgOpen) {
                    return super.dispatchKeyEvent(e);
                }
                if (down) {
                    step(1);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (panelOpen) {
                    if (list.hasFocus()) {
                        groupList.requestFocus();
                    }
                    return true;
                }
                if (epgOpen) {
                    if (down) {
                        step(-1);
                    }
                    return true;
                }
                if (down) {
                    volume(-1);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (panelOpen) {
                    if (groupList.hasFocus() && list.getCount() > 0) {
                        list.requestFocus();
                    }
                    return true;
                }
                if (epgOpen) {
                    if (down) {
                        step(1);
                    }
                    return true;
                }
                if (down) {
                    volume(1);
                }
                return true;

            default:
                return super.dispatchKeyEvent(e);
        }
    }

    // ------------------------------------------------------------- 生命周期

    @Override
    protected void onStart() {
        super.onStart();
        if (livePlayer == null && player == null && !channels.isEmpty() && surfaceReady) {
            tune(index);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(commitNumber);
        handler.removeCallbacks(hideOsd);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        stopPlayback();
        ConfigServer.setSaveListener(null);
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        if (livePlayer == null && player == null && !channels.isEmpty()) {
            tune(index);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        stopPlayback();
    }
}
