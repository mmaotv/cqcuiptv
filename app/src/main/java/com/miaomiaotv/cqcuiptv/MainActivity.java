package com.miaomiaotv.cqcuiptv;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 单 Activity 直播播放器：
 * 全屏 SurfaceView + 原生 MediaPlayer（rtsp / http / m3u8 都能交给系统解码器），
 * 遥控器上下键换台、数字键直达频道号、OK 唤出频道列表、菜单键进设置。
 */
public class MainActivity extends Activity implements
        SurfaceHolder.Callback,
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnInfoListener,
        AdapterView.OnItemClickListener {

    /** 数字键连按的判定窗口（毫秒） */
    private static final long NUMBER_TIMEOUT = 1100L;
    /** 长时间没 prepare 成功就当失败 */
    private static final long PREPARE_TIMEOUT = 15000L;
    /** OK 键按住多久算长按 */
    private static final long LONG_PRESS = 700L;
    /** 底部默认提示语 */
    private static final String HINT_DEFAULT =
            "数字键 选台　↑↓ 换台　OK 频道列表　左右 音量　MENU 设置";
    /** raw.githubusercontent 在国内经常拉不动，兜底走 jsdelivr 镜像 */
    private static final String MIRROR_URL =
            "https://cdn.jsdelivr.net/gh/mmaotv/cqcuiptv@main/cqcu-tv.m3u";
    /** 在线列表的单次超时（DNS 卡住也认栽，别让用户干等） */
    private static final long FETCH_TIMEOUT = 12000L;

    private SurfaceView surfaceView;
    private SurfaceHolder holder;
    private boolean surfaceReady;

    private MediaPlayer player;
    private boolean playing;

    private View osd;
    private TextView osdNum;
    private TextView osdName;
    private TextView osdGroup;
    private TextView notice;
    private TextView hint;

    private View panel;
    private ListView list;
    private ChannelAdapter adapter;
    private boolean panelOpen;

    private final List<Channel> channels = new ArrayList<Channel>();
    private int index;

    private Prefs prefs;
    private final Handler handler = new Handler();

    private String numBuf = "";
    private long okDownAt;

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

        notice = (TextView) findViewById(R.id.notice);
        hint = (TextView) findViewById(R.id.hint);

        panel = findViewById(R.id.panel);
        list = (ListView) findViewById(R.id.list);
        adapter = new ChannelAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(this);

        loadPlaylist();
    }

    // ---------------------------------------------------------------- 播放列表

    private void loadPlaylist() {
        showNotice("正在加载频道列表…");

        // 1) 先用打包在 APK 里的列表秒开，不等网络
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Channel> base = null;
                try {
                    base = Playlist.load("", MainActivity.this);
                } catch (Exception ignored) {
                }
                final List<Channel> b = base;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        applyChannels(b, null);
                    }
                });
            }
        }).start();

        // 2) 再按配置去拉在线列表 / 用户自定义列表，成功就替换
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Channel> result = null;
                String error = null;
                try {
                    String custom = prefs.custom();
                    String url = prefs.url();
                    if (!TextUtils.isEmpty(custom.trim())) {
                        result = Playlist.parseText(custom);
                    } else if (!TextUtils.isEmpty(url)) {
                        result = fetch(url);
                        if (result == null || result.isEmpty()) {
                            result = null;
                            error = "列表里没有频道";
                        }
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                // 用的还是默认地址、而且没拉到时，换镜像再试一次
                if (result == null && prefs.custom().trim().length() == 0
                        && prefs.url().equals(prefs.defaultUrl())) {
                    try {
                        List<Channel> mirror = fetch(MIRROR_URL);
                        if (mirror != null && !mirror.isEmpty()) {
                            result = mirror;
                            error = null;
                        }
                    } catch (Exception ignored) {
                    }
                }
                final List<Channel> r = result;
                final String err = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (r != null) {
                            applyChannels(r, err);
                        } else if (err != null) {
                            showHint("离线：在线列表没拉到，正在用内置频道列表　·　MENU 改地址");
                        }
                    }
                });
            }
        }).start();
    }

    /** 带总超时的拉取：DNS 卡死时 HttpURLConnection 的 connectTimeout 是管不住的。 */
    private List<Channel> fetch(final String url) throws Exception {
        java.util.concurrent.FutureTask<List<Channel>> task =
                new java.util.concurrent.FutureTask<List<Channel>>(
                        new java.util.concurrent.Callable<List<Channel>>() {
                            @Override
                            public List<Channel> call() throws Exception {
                                return Playlist.load(url, MainActivity.this);
                            }
                        });
        Thread t = new Thread(task, "playlist");
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
    private void applyChannels(List<Channel> loaded, String error) {
        if (loaded == null || loaded.isEmpty()) {
            if (channels.isEmpty()) {
                adapter.setData(channels);
                showNotice("频道列表是空的\n" + (error == null ? "按 MENU 检查播放列表地址" : error));
            }
            return;
        }
        if (!channels.isEmpty() && sameList(loaded)) {
            hideNotice();
            return;
        }

        String playing = channels.isEmpty() || index >= channels.size() ? null : channels.get(index).name;
        channels.clear();
        channels.addAll(loaded);
        adapter.setData(channels);
        ((TextView) findViewById(R.id.panel_count)).setText(channels.size() + " 个频道");
        hideNotice();

        int target = 0;
        if (playing != null) {
            int found = indexOf(playing);
            if (found >= 0) {
                target = found;
            }
        } else if (prefs.lastIndex() >= 0 && prefs.lastIndex() < channels.size()) {
            target = prefs.lastIndex();
        }
        tune(target);
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

        Channel c = channels.get(i);
        showOsd(String.valueOf(i + 1), c.name, c.group);
        hideNotice();
        releasePlayer();

        if (!surfaceReady) {
            return;
        }
        startPlayback(c.url);
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

    // -------------------------------------------------------------- MediaPlayer

    private void startPlayback(String url) {
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

            handler.removeCallbacks(prepareWatchdog);
            handler.postDelayed(prepareWatchdog, PREPARE_TIMEOUT);
        } catch (Exception e) {
            playing = false;
            showNotice("播放失败：" + e.getMessage() + "\n按 OK 重试");
        }
    }

    private void releasePlayer() {
        handler.removeCallbacks(prepareWatchdog);
        playing = false;
        if (player != null) {
            final MediaPlayer mp = player;
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
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        if (mp != player) {
            return;
        }
        playing = true;
        handler.removeCallbacks(prepareWatchdog);
        hideNotice();
        try {
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        } catch (Throwable ignored) {
        }
        mp.start();
        handler.removeCallbacks(hideOsd);
        handler.postDelayed(hideOsd, 3000);
        hint.setText(HINT_DEFAULT);
        handler.removeCallbacks(hideHint);
        handler.postDelayed(hideHint, 8000);
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
        panelOpen = true;
        hideNotice();
        handler.removeCallbacks(hideOsd);
        osd.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        list.setSelection(index);
        list.requestFocus();
    }

    private void closePanel() {
        panelOpen = false;
        panel.setVisibility(View.GONE);
    }

    private void openSettings() {
        Intent i = new Intent(this, SettingsActivity.class);
        startActivityForResult(i, 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK) {
            releasePlayer();
            channels.clear();
            loadPlaylist();
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        closePanel();
        tune(position);
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

            case KeyEvent.KEYCODE_BACK:
                if (down && panelOpen) {
                    closePanel();
                    return true;
                }
                return super.dispatchKeyEvent(e);

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (panelOpen) {
                    return super.dispatchKeyEvent(e);
                }
                if (down) {
                    okDownAt = System.currentTimeMillis();
                    return true;
                }
                if (System.currentTimeMillis() - okDownAt >= LONG_PRESS) {
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
                if (panelOpen) {
                    return super.dispatchKeyEvent(e);
                }
                if (down) {
                    step(-1);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (panelOpen) {
                    return super.dispatchKeyEvent(e);
                }
                if (down) {
                    step(1);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (panelOpen) {
                    return super.dispatchKeyEvent(e);
                }
                if (down) {
                    volume(-1);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (panelOpen) {
                    return super.dispatchKeyEvent(e);
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
        if (player == null && !channels.isEmpty() && surfaceReady) {
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
        releasePlayer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        if (player == null && !channels.isEmpty()) {
            startPlayback(channels.get(index).url);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        releasePlayer();
    }
}
