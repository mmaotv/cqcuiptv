# 技术说明

README 放不下的实现细节、踩过的坑、源列表维护与发版流程都记在这里。

---

## 目录

- [频道分类与台号](#频道分类与台号)
- [播放内核](#播放内核)
- [换源的取舍顺序](#换源的取舍顺序)
- [节目单（EPG）实现](#节目单epg实现)
- [电视上装不上怎么办](#电视上装不上怎么办)
- [应用内更新与发版流程](#应用内更新与发版流程)
- [播放列表文件说明](#播放列表文件说明)
- [相关项目](#相关项目)

---

## 频道分类与台号

分类来自 M3U 的 `group-title`，`cqcu-tv.m3u` 里一共 9 个分类、151 个频道：

| 分类 | 频道数 |
| --- | --- |
| 央视 | 17 |
| 卫视 | 33 |
| 4K超高清 | 12 |
| 央视付费 | 19 |
| 重庆 | 11 |
| 上海 | 9 |
| IPTV | 12 |
| 影视 | 14 |
| 其他 | 24 |

台号是**全局**的，跟分类无关：分类只是换个视图，不会打乱「按 1 是 CCTV1、按 18 是湖南卫视」这套规则。

| 台号 | 内容 |
| --- | --- |
| 1 – 17 | CCTV1 … CCTV17（CCTV5+ 在第 6 位） |
| 18 起 | 湖南卫视、浙江卫视、江苏卫视、东方卫视、北京卫视…… 各省卫视 |
| 卫视之后 | CCTV 付费 / CETV / CHC、重庆本地台、上海、IPTV、影视、其他 |

顺序由 `scripts/build_tv_m3u.py` 生成。想调整（比如把湖南卫视换到 19 位、或把卫视放最后），
改脚本顶部的 `CATEGORIES` / `RANK` 再跑一遍即可，每天也会随 CI 自动重建。

---

## 播放内核

### 为什么不直接用系统 MediaPlayer

在晶晨 S905L3A / Android 9 这类盒子上，`MediaPlayer` 播不了这批源，两个硬伤：

1. **MP2 音频没有解码器**：固件把 MP2 的 mime 报成 `audio/ffmpeg`，日志里直接是
   `Couldn't map mime type "audio/ffmpeg"` → `Failed to create audio/ffmpeg decoder`，所以只有画面没有声音。
   `/vendor/etc/media_codecs.xml` 里只有 aac / ac3 / eac3 / mp3 / vorbis / opus / flac，没有 MP2；
   唯一名字像样的 `OMX.google.mp3.decoder` 只声明 `audio/mpeg`（真 MP3），喂 MP2 会报
   `SoftMP3: decoder returned error 1`。
2. **4K 频道直接失败**：系统 `MediaExtractor` 不支持 rtsp（`Failed to instantiate extractor`），
   `setDataSource` 返回 `status=0x80000000`。

所以这个 App 自己把整条链路做了一遍：

```
RTSP(OPTIONS/DESCRIBE/SETUP/PLAY，RTP/AVP/TCP 交织)
  → RTP 拆包
  → MPEG-TS 解复用（PAT/PMT/PES）
  → 视频：MediaCodec 硬解，直接 releaseOutputBuffer 到 Surface（零拷贝、零转码）
    音频：MP2 → 纯 Java 软解成 PCM；AAC / AC3 / EAC3 → MediaCodec
  → AudioTrack（统一双声道，多声道源在软件里降混）
```

### 踩过的坑

- **服务器只接受 TCP 交织**：`Transport: RTP/AVP/UDP;unicast;client_port=x-y` 会返回
  `461 Unsupported Transport`（ffmpeg 也一样），必须 `RTP/AVP/TCP;unicast;interleaved=0-1`
- **DESCRIBE 会 302 跳转**，要跟着 `Location` 重新建连接
- **SDP 是 MP2T 单轨**：`m=video 0 RTP/AVP 33` + `a=rtpmap:33 MP2T/90000`，真正的轨道要拼 `trackID=2`
- **SPS 必须先去掉防竞争字节**（`00 00 03` → `00 00`）再读位流，否则 HEVC 的分辨率永远解析错
- **HEVC 必须带 csd-0（VPS+SPS+PPS）**：不带参数集去 configure，部分固件只给一片纯绿

### 分辨率怎么定的

`SpsParser` 从码流 SPS 里解出**真实**分辨率，然后交给这颗芯片的解码器能力表去判断：

- 1080P 的源按 1920×1080 配；
- 4K 的源按 3840×2160 配，屏幕比源小由硬件缩放器缩到屏幕上（不是转码）；
- **配置尺寸必须等于码流分辨率**。历史上曾实现过「解不了就降一档配置尺寸」的 `SIZE_LADDER`，
  这是错的：降尺寸之后再喂 3840×2160 的码流，解码器会直接崩或者零输出；
- 为什么不能直接 `createDecoderByType`：它只返回「第一个匹配」，压根不看尺寸上限。某台测试机的
  `OMX.MTK.VIDEO.DECODER.HEVC` 上限是 2560×1440，拿 3840×2160 去 configure 会把 OMX 组件撑崩
  （`ACodec ERROR 0x80001005`），MediaCodec 随后进 Released 态，每次 dequeue 都抛异常；
- 部分设备（国科 goke 盒子）的**运行时能力表是坏的**：XML 里声明 4096×2306，`getSupportedWidths()`
  却返回 `[128, 288]`，于是硬解永远被判「不支持」。兜底是按 mime 直接 `createDecoderByType`，
  配置成功就能进 Executing 正常硬解 4K；
- 解码器中途死掉时播放线程立刻退出并提示，**不会空转烧 CPU**。

OSD 第三行显示实际配置，例如 `HEVC 3840x2160 · AAC`。

### 音画同步：不丢帧、也不逐帧 sleep

这批源的**视频 PTS 本来就比音频 PTS 超前约 0.7 秒**（抓包量的：同一时刻到达的 PES，
视频 PTS 减音频 PTS 的中位数是 0.72 秒）。所以视频必须压后上屏，才对得上口型。

早期版本在「输出」环节按延迟 `sleep` 了整个延迟：每帧睡满 200ms，喂帧掉到 5fps，队列堆满 →
丢参考帧 → 又卡又花，而且丢帧让视频永远超前，**这个死循环退不出来**（CCTV3 这种高动态频道最明显）。

现在的做法：

- **按音频时钟放行**：帧到了先躺在队列里，等「快轮到上屏」才送解码器，喂帧速率自然等于帧率；
- **不 sleep 整个延迟**：等待循环每 40ms 检查一次，够近了立刻放行；
- **以「耳朵听到的位置」为准**：`AudioTrack.getPlaybackHeadPosition()` 减掉还没放出的帧数，
  原来的「最后写入 PTS + 流逝时间」比耳朵快一个缓冲区（约 150ms）；
- **音频队列启动时清空一次**，否则整场声音都比画面晚；
- **输入缓冲拿不到时先抽输出、不丢这一帧**。

### 4K 间歇卡死：队列溢出触发的自激振荡

3.0.12 及以前，4K 频道播 1–2 分钟后画面冻十几到几十秒、声音正常。`[stat]` 日志的特征是：
`q` 从满 → 溢出 → **q=0**，`fed` 掉到关键帧频率（≈2.6fps），`out` 冻结。

根因三条叠加：

1. `QUEUE_VIDEO=40` 对 4K 50fps 只有 0.8 秒，而源本身 PTS 前瞻就有 0.7 秒（≈35 帧），几乎贴顶，
   网络成簇送包即溢出；
2. 溢出丢帧置 `resyncNeeded`，消费侧在关键帧处 `vCodec.flush()`；溢出是持续的 → **每个 GOP 都
   flush 一次** → 解码器永远只拿到 1 帧/GOP、凑不出完整 GOP → 零输出 → 队列被瞬间抽空；
3. 放行闸门没有 PTS 跳变与最长等待保护。

3.0.13 修复：

- `QUEUE_VIDEO` 40 → **90 帧**（1.8 秒），`MAX_QUEUE_BYTES` 8 → **20 MB**；
  不增加延迟，因为放行仍由音频钟 just-in-time 控制，队列只是内存里的抖动缓冲；
- **溢出后等关键帧不再 flush**，硬件解码器收到 IDR 会自行重同步；
- `RESYNC_GIVEUP_MS=3s`：等关键帧超时后退一步接着喂，不会无限期丢弃；
- 闸门加 `LEAD_RESET_US=2s` 跳变保护与 `GATE_MAX_WAIT_MS=900ms` 上限；
- `[stat]` 增加 `skip=` / `rsy=` 计数。

实测（S905X2 / Amlogic，江苏卫视 4K，连续 9.5 分钟）：
`in=28091 fed=28003 out=28000 drop=0 rsy=0`，稳定 50fps。旧版 2.5 分钟即卡、之后每 1–2 分钟复发。

### 健康看门狗（黑屏未起播 / 播放中卡死）

视频线程可能被硬件解码器钉死，所以单独起一个守护线程每 2 秒读共享计数器评估：

| 判定 | 条件 | 自愈 |
| --- | --- | --- |
| 黑屏未起播 | 流已连通、视频帧在进，但 12 秒内 `out` 仍为 0 | flush → 「切走再切回」 |
| 播放中卡死 | 曾正常出帧，但 6 秒无新帧且码流仍在进 | flush → 原地重建解码器（音频不中断）→ 同频道重拉 |

两项都要求「近期有视频帧到达」，所以正常频道持续出帧时永不触发。每次成功起播清零重试计数，
连续 4 次失败则停止自动恢复并提示，不对坏频道死循环重连。

### 诊断日志

每 10 秒一行 `[stat]`：

```
[stat] q=57/90 in=26583 drop=0 fed=26499 out=26496 rsy=0 skip=0 av=-27ms kbps=38106
```

健康的判据：`drop` 一直是 0、`rsy`/`skip` 不涨、`av` 在 ±100ms 内、`fed ≈ out ≈ 帧率×秒数`。

其他事件：`[HEAL]`（看门狗自愈）、`[resync]`（等关键帧）、`[gate]`（放行被跳变保护放行）、
`[starve]`（队列断粮）、`[dec]`（等解码器输入缓冲超时）。

### 非 rtsp 的源

`http://` / `.m3u8` 这类地址交给系统 `MediaPlayer`。判断只有一条：地址以 `rtsp` 开头走自带内核。

---

## 换源的取舍顺序

1. **自定义 M3U**：整段粘进去的内容，本地直接用，不联网；
2. **本地缓存**：从地址拉过就存到本地，没到期直接读；
3. **播放列表地址**：填一个 `http(s)://` 地址，按缓存周期去拉；
4. **内置列表**：都没有时用 APK 里打包的 `cqcu-tv.m3u`。

缓存文件写在 App 私有目录（`playlist.cache`），文件头三行记录来源地址与拉取时间，正文是原始 M3U。
缓存地址与当前设置一致且在有效期内 → 一次网络请求都不发；过期 → 先顶上旧列表再后台拉新；地址变了 → 旧缓存作废。

单次拉取有 12 秒总超时。默认地址是 GitHub raw，国内经常拉不动，失败会自动再试
`cdn.jsdelivr.net/gh/mmaotv/cqcuiptv@main/cqcu-tv.m3u`。

**局域网填源**：内置极简 HTTP 服务（纯 HTML，无图片、无后端），端口默认 8965，被占用自动往后找。
只监听局域网、**不做鉴权**，别把端口映射到公网；默认关闭，用的时候再开。

---

## 节目单（EPG）实现

节目单走外部 XMLTV，不用码流里的 EIT 表 —— 实测这些流的 TS 里 EIT 是空的。

- 默认源 `https://gcore.jsdelivr.net/gh/fanmingming/live@main/e.xml`（`cdn.jsdelivr.net` 兜底，
  再兜底用 M3U 头部 `url-tvg` 声明的源）；
- 首次约 9 MB，流式解析（`XmlPullParser`，XML 先落盘再解析，不整块读进内存），
  只保留本列表里的频道、且只留「12 小时前 ~ 36 小时后」的节目；
- 结果缓存 6 小时，冷启动直接读缓存；
- 频道匹配用 `tvg-id`（如 `CCTV1`、`湖南卫视`），没有就用频道名；
- **时间按 XMLTV 自带时区解析**：`20261023070000 +0800` 里的 `+0800` 是节目时间所在时区，不是设备时区。
  早期当成设备时区又减了一次偏移，中国时区下整张表错位 8 小时（早 7 点显示下午 3 点的节目）。

校对方法：CCTV1 早 7 点《朝闻天下》、晚 7 点《新闻联播》，对得上就说明整份解析是对的。

---

## 电视上装不上怎么办

先分清是哪一类，别一上来就改包名：

| 现象 | 真实原因 | 怎么办 |
| --- | --- | --- |
| `INSTALL_FAILED_USER_RESTRICTED` | 没开「未知来源安装」，或没给文件管理器授权 | 去设置里开权限，跟包名无关 |
| `INSTALL_FAILED_VERIFICATION_FAILURE` | 联网时走了云端合规 / 签名校验 | 电视先断网再装 |
| 点 APK 弹「存在风险，已拦截」，但 `adb install` 能装 | 安装器 UI 的关键词策略 | 这时包名、文件名、应用名里的用词才有意义 |
| 弹「该应用是为旧版 Android 构建」 | `targetSdk = 28` 偏低 | 暂时忽略，或升 targetSdk 后重编 |

3.0.0 换了包名（`com.miaomiao.qinglive`），等于一个全新应用，跟 1.x / 2.x 不能互相覆盖升级。

---

## 应用内更新与发版流程

App 读仓库根目录的 [`update.json`](../update.json) 比对版本号，字段很短：

```json
{
  "versionName": "3.0.14",
  "versionCode": 18,
  "date": "2026-11-02",
  "size": 119785,
  "apk": "https://github.com/mmaotv/cqcuiptv/releases/download/app-latest/LightLive-3.0.14.apk",
  "notes": ["这一版改了什么", "一行一条"]
}
```

发版流程：

1. 改 `app/build.gradle.kts` 里的 `appVersionName` / `appVersionCode`；
2. 推送到 `main` → CI 构建并更新 `app-latest` Release；
3. 把 `update.json` 的版本号、包大小、APK 地址、说明改成新版本 → 推送。

只剩 `update.json` 变更时 CI 不会重新构建，不会多出一次 Release。

> **清单为什么取「版本号最高的源」而不是「谁先返回用谁」**：jsDelivr 的分支缓存可能十几小时不刷，
> 且国内常比 raw 快。发 3.0.13 时实测 `gcore.jsdelivr.net` 仍在返回 3.0.9，导致「检查更新」
> 一直显示已是最新。现在并发取三源、取 `versionCode` 最大的一份，首个成功后只再宽限 1.2 秒；
> jsDelivr 地址还带 10 分钟一换的时间桶参数绕过陈旧缓存。

---

## 播放列表文件说明

- **cqcu-tv.m3u** —— 为 App 重排过的版本：CCTV1 是 1 台，卫视紧跟央视之后，细化了 `group-title`。
  **播放器 / 新装 App 推荐用这个。**
- **cqcu-unicast_epg.m3u** —— 带台标 `tvg-logo` + 节目单 `tvg-id` 的完整版，按分组字母序排列，
  适合 VLC / Kodi / mytv-android。
- **cqcu-unicast.m3u / cqcu-unicast-alternative.m3u** —— 重庆联通单播源，公网或 IPTV 专网均可播放；
  未开通 IPTV 业务也能看；需要支持 rtsp 的播放器（VLC、potplayer）。
- **cqcu-multicast.m3u / cqcu-multicast-udpxy.m3u** —— 重庆联通组播源，只能在 IPTV 专网播放；
  udpxy 版做了组播转单播，请按实际修改服务器地址与端口（可能导致换台卡顿）。

### 自动更新

`.github/workflows/update.yml` 每天 0 点（UTC）跑一次：

1. `scripts/build_epg_m3u.py` 重新生成带台标 / EPG 的 `cqcu-unicast_epg.m3u`；
2. `scripts/build_tv_m3u.py` 据此重排出「台号顺序版」`cqcu-tv.m3u`，并同步到
   `app/src/main/assets/playlist.m3u`。

配置参考 [重庆联通 IPTV 单线复用 + 内网融合教程](https://blog.imouto.in/post/iptv/2022/cqcu-iptv-on-openwrt/)。

---

## 相关项目

- [mytv-android](https://github.com/mytv-android/mytv-android) —— 另一个开源安卓直播 App，可作替代品参考
- [fanmingming/live](https://github.com/fanmingming/live) —— 本项目的 EPG 数据来源
- [delthas/JavaMP3](https://github.com/delthas/JavaMP3) —— MP2 音频软解移植来源
