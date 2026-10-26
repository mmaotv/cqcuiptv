# 轻直播 · LightLive

> **极轻量的安卓电视 / 盒子直播客户端** —— 约 **100 KB**、零第三方依赖、Android 4.4+ 都能装。
> 支持**自定义 M3U 源**、**EPG 节目单**、**频道分类**、**触摸与遥控操作**，自研 RTSP/MPEG-TS 播放内核，
> 不内置任何广告、不需要账号。

[![Build APK](https://github.com/mmaotv/cqcuiptv/actions/workflows/android.yml/badge.svg)](https://github.com/mmaotv/cqcuiptv/actions/workflows/android.yml)
[![License](https://img.shields.io/github/license/mmaotv/cqcuiptv)](LICENSE)
[![APK size](https://img.shields.io/badge/APK-%7E100%20KB-blue)](https://github.com/mmaotv/cqcuiptv/releases/latest)
[![Android](https://img.shields.io/badge/Android-4.4%2B-brightgreen)](https://www.android.com/)
[![Platform](https://img.shields.io/badge/platform-Android%20TV%20%2F%20Phone-orange)](https://github.com/mmaotv/cqcuiptv/releases/latest)

**一句话**：像老电视一样「按 1 就是 CCTV1」的直播 App —— 遥控器、触摸屏都能用，频道按
央视 / 卫视 / 4K / 各地分类展示，每个频道都带节目单（EPG）。

> 关键词：安卓直播 · 电视直播 · IPTV · M3U / M3U8 播放器 · EPG 节目单 · 自定义源 · 直播软件 ·
> Android live TV · IPTV player · M3U · EPG · RTSP · 免费 · 开源

![播放画面](docs/screenshots/01-live.jpg)

---

## 目录

- [这是什么](#这是什么)
- [快速开始（下载安装）](#快速开始下载安装)
- [特性](#特性)
- [遥控器按键](#遥控器按键)
- [触摸操作（平板 / 触摸大屏）](#触摸操作平板--触摸大屏)
- [分类与台号](#分类与台号)
- [节目单（EPG）](#节目单epg)
- [播放内核](#播放内核)
- [换直播源](#换直播源)
- [安装](#安装)
- [应用内更新（检查更新）](#应用内更新检查更新)
- [自己编译](#自己编译)
- [已知限制](#已知限制)
- [播放列表文件说明](#播放列表文件说明)
- [English](#english)
- [相关项目](#相关项目)
- [致谢与许可](#致谢与许可)

---

## 这是什么

轻直播（LightLive）是一个**只干一件事**的电视直播客户端：把台放出来、把节目单写清楚。

它不像通用播放器那样大而全，而是针对「电视 / 盒子上看直播」这个场景做了减法：

- **体积约 100 KB**，零第三方依赖（不使用 AndroidX / ExoPlayer / VLC），老盒子也跑得动；
- **不用记复杂手势**：遥控器数字键直达、上下换台，触摸屏单击左右换台、点中间开列表；
- **源是自定义的**：你给它一个 M3U 地址（或整段内容），它就能播，不止局限于内置的重庆联通源；
- **节目单是外挂的**：每个频道显示「正在播 / 下一档」，按 `INFO` 看当天完整节目表。

配套仓库里还提供一套重庆联通 IPTV 的频道列表（`cqcu-tv.m3u`），但这些源**只能在重庆联通网络**下播放，
其他网络打开会黑屏 / 超时 —— 这是源本身的限制，跟 App 无关。

---

## 快速开始（下载安装）

1. 打开 [Releases](https://github.com/mmaotv/cqcuiptv/releases/latest)，下载 `LightLive-*.apk`；
2. 把 APK 拷到电视 / 盒子上安装，或在电脑上用 adb 安装：

   ```bash
   adb install -r LightLive-3.0.0.apk
   ```

3. 装好后在桌面找 **轻直播**（蓝紫渐变 + 白色电视图标），打开就能看内置频道；
4. 想换源：进设置页填 M3U 地址 / 粘贴整段内容，或在同局域网用手机/电脑打开 `http://盒子IP:8965` 填源。

> 包名是 `com.miaomiao.qinglive`，**刻意不含 `tv` / `iptv` / `launcher`** —— 小米、海信、创维等电视的
> 安装管控会按这些词把 APK 判成「电视应用 / 运营商业务」直接拦截，跟功能无关。详见[安装](#安装)。

---

## 特性

- **分类浏览**：直接读 M3U 的 `group-title`，左侧分类、右侧该分类下的频道，分类里还能看到频道数
- **节目单（EPG）**：每个频道显示「正在播 / 下一档」，`INFO` 键呼出当天完整节目表，正在播出的那一档高亮
- **自研播放内核**：RTSP(TCP 交织) → RTP → MPEG-TS → `MediaCodec` 硬解直接上屏，音频 MP2 用纯 Java 软解，
  AAC / AC3 / EAC3 走系统解码器，最后统一进 `AudioTrack`
- **按源分辨率播放**：从码流 SPS 里解出真实分辨率（1920×1080 / 3840×2160），再按解码器能力表夹到它支持的上限后配置，
  不软解、不转码；万一被夹过，台标里会写成 `3840x2160 → 2560x1440` 让你看得见
- **体积**：零第三方依赖（不用 AndroidX / ExoPlayer / VLC），release 包约 100 KB
- **兼容**：minSdk 19，Android 4.4 及以上都能装
- **换台**：数字键直达频道号，上下键依次换台，左右键调音量
- **触摸屏 / 平板**：单击画面左/右 1/5 换台、单击中间开频道列表、双击节目单、滑动换台/调音量、长按设置
- **本地缓存**：从地址拉下来的列表存到本地，下次启动直接读，不重复下载；有效期可选 7 天 / 15 天 / 一个月 / 不自动
- **局域网填源**：手机上打开 `http://盒子IP:8965` 就能粘贴 M3U，不用拿遥控器打字
- **自定义直播源**：设置页填 m3u 地址，或者把整段 m3u 内容直接粘进去
- **应用内更新**：设置页可检查新版本、先看更新日志，再由你决定装不装，装的时候直接覆盖安装
- **离线可用**：APK 内置一份频道列表，断网 / 地址失效也能进界面看电视
- **记住上次的台**：重新打开接着上次的频道放

### 遥控器按键

| 按键 | 作用 |
| --- | --- |
| `0`–`9` | 输入频道号，约 1.1 秒后自动跳转（最多 3 位，如 `1` → CCTV1，`1` `8` → 湖南卫视） |
| `↑` / `↓`、`CH+` / `CH-` | 上一个 / 下一个频道 |
| `←` / `→` | 音量 - / +（节目单打开时是上一个 / 下一个频道） |
| `OK` / `确认` | 打开分类频道列表；列表里 `→` 进右栏、`←` 回分类、`OK` 换台；播放失败时按 `OK` 重试 |
| `INFO` / `GUIDE` | 打开 / 关闭当前频道的节目单 |
| `返回` | 关闭列表或节目单；再按退出 App |
| `MENU` / 长按 `OK` | 打开设置页 |

### 触摸操作（平板 / 触摸大屏）

没有遥控器也能用，而且是**不用记手势**的：

| 手势 | 作用 |
| --- | --- |
| 单击画面**左侧**（约左边 1/5） | 上一个频道 |
| 单击画面**右侧**（约右边 1/5） | 下一个频道 |
| 单击画面**中间** | 打开频道列表（分类在左、频道在右，当前频道红底标出） |
| 双击 | 打开 / 关闭当前频道的节目单 |
| 左右滑动 | 换台（左滑下一台、右滑上一台，一次滑动只换一个台） |
| 上下滑动 | 音量（上滑加、下滑减） |
| 长按画面 | 打开设置页 |

面板开着时，单击面板外面任意位置就是关闭。系统栏会自动隐藏（沉浸式全屏），面板宽度按屏幕宽度自适应，
手机横屏也不会超出画面。

---

## 分类与台号

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
| 卫视之后 | CCTV 付费频道 / CETV / CHC、重庆本地台、上海、IPTV、影视、其他 |

顺序由 `scripts/build_tv_m3u.py` 生成。想调整（比如把湖南卫视换到 19 位、或把卫视放最后），
改脚本顶部的 `CATEGORIES` / `RANK` 再跑一遍即可，每天也会随 CI 自动重建。

---

## 节目单（EPG）

节目单走外部 XMLTV，不用码流里的 EIT 表——实测这些流的 TS 里 EIT 是空的，只能外挂。

- 默认源：`https://gcore.jsdelivr.net/gh/fanmingming/live@main/e.xml`（`cdn.jsdelivr.net` 兜底，
  再兜底用 M3U 头部 `url-tvg` 里声明的源）
- 首次后台下载约 9 MB，解析后**只保留本列表里的频道**、且只留「12 小时前 ~ 36 小时后」的节目，
  内存占用很小；解析是流式（`XmlPullParser`），XML 先落盘再解析，不会整块读进内存
- 结果缓存 6 小时，冷启动直接读缓存，不重复下载
- 频道匹配用 `tvg-id`（如 `CCTV1`、`湖南卫视`），没有 `tvg-id` 就用频道名
- **时间按 XMLTV 自带的时区解析**（`20261023070000 +0800` 里的 `+0800` 是节目时间所在的时区，
  不是设备时区）。之前把它当成设备时区又减了一次偏移，中国时区下等于多减 8 小时，
  整张节目单全部错位——早上 7 点显示的是下午 3 点的节目
- 节目单源没收录的频道（付费频道、重庆/上海地方台、IPTV 精选频道）第二行直接写「暂无节目单」，
  不会让人以为是错位
- 列表第二行显示「正在播」的节目名；`INFO` 键打开节目单，正在播出的那一档用橙色标出

校对方法：CCTV1 早上 7 点是《朝闻天下》、晚上 7 点是《新闻联播》，
CCTV1 的节目单能对上这两个点，其他频道就是对的（同一份 XML、同一套解析）。

节目单加载失败不影响看电视，只是列表第二行和节目单面板是空的。

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

几个踩过的坑，代码里都留着处理：

- **服务器只接受 TCP 交织**：`Transport: RTP/AVP/UDP;unicast;client_port=x-y` 会返回
  `461 Unsupported Transport`（ffmpeg 也一样），必须 `RTP/AVP/TCP;unicast;interleaved=0-1`
- **DESCRIBE 会 302 跳转**，要跟着 `Location` 重新建连接
- **SDP 是 MP2T 单轨**：`m=video 0 RTP/AVP 33` + `a=rtpmap:33 MP2T/90000`，
  真正的轨道要拼 `trackID=2`
- **SPS 必须先去掉防竞争字节**（`00 00 03` → `00 00`）再读位流，否则 HEVC 的分辨率永远解析错
- **HEVC 必须带 csd-0（VPS+SPS+PPS）**：不带参数集去 configure，部分固件只给一片纯绿

### 分辨率怎么定的

`SpsParser` 从码流的 SPS 里解出**真实**分辨率，然后交给这颗芯片的解码器能力表去夹：

- 1080P 的源按 1920×1080 配
- 4K 的源优先按 3840×2160 配，屏幕比源小的话由芯片的硬件缩放器缩到屏幕上（这不是转码）
- **配之前先查解码器能力上限**：`MediaCodecInfo.VideoCapabilities` 里写着这颗 HEVC 解码器最大能到多少，
  超过就按 2560×1440 → 1920×1080 逐档往下找一档它真支持的，再用 `createByCodecName` 点名建
- 为什么不能直接 `createDecoderByType`：它只返回「第一个匹配」，压根不看尺寸上限。这台测试机的
  `OMX.MTK.VIDEO.DECODER.HEVC` 上限是 2560×1440，拿 3840×2160 去 configure 会把 OMX 组件直接撑崩
  （`ACodec ERROR 0x80001005`），MediaCodec 随后进入 Released 态，每次 dequeue 都抛异常
- 解码器中途死掉时，播放线程会立刻退出并把原因说清楚，**不会空转烧 CPU**（这个空转就是之前「异常卡顿」的元凶）

OSD 第三行会显示实际配置，例如 `HEVC 3840x2160 · AAC`；被夹过会显示
`3840x2160 → 2560x1440`，一眼能看出来。

### 音画同步：不丢帧、也不逐帧 sleep

这批源的**视频 PTS 本来就比音频 PTS 超前约 0.7 秒**（抓包量的：同一时刻到达的 PES，
视频 PTS 减音频 PTS 的中位数是 0.72 秒）。所以视频必须压后 0.7 秒上屏，才对得上口型。

之前那段代码在「输出」环节按延迟 `sleep`，写成了 `sleep(整个延迟)`：每一帧都睡满 200ms，
喂帧速率掉到 5fps，队列越堆越满 → 丢参考帧 → 画面又卡又花，而且因为丢帧让视频永远超前，
**这个死循环再也退不出来**（CCTV3 这种高动态频道最明显，静态画面的 CCTV1 反而不容易踩到）。

现在的做法：

- **按音频时钟放行**：帧到了先躺在队列里（约 0.7 秒的量），等它「快轮到上屏」了才送进解码器，
  喂帧速率自然等于 25fps，不会过冲也不会堆积
- **不 sleep 整个延迟**：等待循环每 40ms 检查一次，够近了立刻放行
- **音画同步以「耳朵听到的位置」为准**：`AudioTrack.getPlaybackHeadPosition()` 减掉还没放出来的帧数，
  才是真正的播放位置。原来的时钟是「最后写入的 PTS + 流逝时间」，比耳朵快一个缓冲区（约 150ms）
- **音频队列启动时清空一次**：音频线程等解码器就绪的那一秒会攒下近一秒的旧数据，
  不清掉的话整场播放声音都比画面晚
- **输入缓冲拿不到时先抽输出、不丢这一帧**；真的溢出（`40` 帧 / `8 MB`）就插一个「等关键帧」标志，
  下一个 I 帧先 `flush()` 再重来，避免一路花屏

播放状态每 10 秒打一行日志（`[stat] q=... drop=... fed=... av=...`），
`drop` 一直是 0、`av` 在 ±100ms 内、`fed` 约等于 25×秒数，就说明这条链路是健康的。

### 非 rtsp 的源

`http://` / `.m3u8` 这类地址仍然交给系统 `MediaPlayer`（把自定义列表换成 http 源也能用）。
判断逻辑只有一条：地址以 `rtsp` 开头走自带内核，其余走系统播放器。

---

## 换直播源

取列表的顺序（优先级从高到低）：

1. **自定义 M3U**：整段粘进去的内容，直接在本地用，不联网
2. **本地缓存**：从地址拉过就存到本地，没到期直接读缓存
3. **播放列表地址**：填一个 `http(s)://` 地址，App 按缓存周期去拉
4. **内置列表**：上面都没有时，用 APK 里打包的 `cqcu-tv.m3u`

点「保存并重载」立刻生效，「恢复默认」回到仓库里的在线地址。

默认地址是 GitHub raw（`raw.githubusercontent.com`），国内网络经常拉不动。拉不到时会自动再试一次
jsdelivr 镜像（`cdn.jsdelivr.net/gh/mmaotv/cqcuiptv@main/cqcu-tv.m3u`），还不行就用缓存 / 内置列表，
底部提示「离线：列表没拉到」，不影响继续看。单次拉取有 12 秒总超时，不会卡在转圈上。

### 本地缓存与自动拉取

拉到的列表写在 App 私有目录（`playlist.cache`），文件头三行记录**来源地址**和**拉取时间**，
正文就是原始 M3U。下次启动：

- 缓存里的地址和当前设置一致、且还在有效期内 → 直接读缓存，**一次网络请求都不发**
- 过期了 → 先把缓存顶上让你立刻有台看，后台再拉新的，拉完自动换上
- 地址改了 → 旧缓存作废，重新拉

有效期在设置页「播放列表缓存」里选：**7 天（默认）/ 15 天 / 一个月 / 不自动**。
选「不自动」就一直用现有缓存，只有点「立即重新拉取」才会去拉。

### 在手机 / 电脑上填源

遥控器打字太慢，App 内置了一个极简 HTTP 服务：

1. 设置页打开「在手机 / 电脑上填源」，下面会显示地址，例如 `http://192.168.10.105:8965`
2. 同一局域网里的手机或电脑浏览器打开这个地址
3. 把 M3U 整段粘进去点「保存内容并应用」，或者只填一个地址点「保存地址并应用」
4. 电视上立刻重新加载，不用重启

页面是纯 HTML（没有二维码、没有图片），一行都不走后端，省内存也省流量。
端口默认 8965，被占用会自动往后找 8966、8967……显示出来的一定是实际在用的那个。

> 服务只监听局域网，不做鉴权——同网段谁都能改列表，别把端口映射到公网。默认关，用的时候再开。

---

## 安装

- **直接下载**：[Releases](https://github.com/mmaotv/cqcuiptv/releases/latest) 里的 `LightLive-*.apk`
- **adb 安装**：

  ```bash
  adb install -r LightLive-3.0.0.apk
  ```

装好以后在电视桌面上找 **轻直播**（蓝紫渐变 + 白色电视图标）。

> 包名是 `com.miaomiao.qinglive`。**刻意不含 `tv` / `iptv` / `launcher` 这些词**——小米、海信、创维等
> 电视的安装管控会按关键词把 APK 判成「电视应用」或「运营商业务」直接拦截，跟功能无关。
> 3.0.0 换了包名，等于一个全新应用，跟 1.x / 2.x 不能互相覆盖升级，装之前可以先留着旧版对照。

### 电视上装不上时先分清是哪一类

包装不上大多数时候跟包名无关，先照这个顺序试一遍，别一上来就改包名：

| 现象 | 真实原因 | 怎么办 |
| --- | --- | --- |
| `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED` | 电视没开「未知来源安装」，或没给文件管理器单独授权 | 去设置里开权限，跟包名无关 |
| `adb install` 报 `INSTALL_FAILED_VERIFICATION_FAILURE` | 联网时走了云端合规/签名校验 | 电视先断网再装 |
| 点 APK 弹「该应用存在风险，已拦截」，但 `adb install` 能装 | 安装器 UI 的关键词策略 | 这时包名、文件名、应用名里的用词才有意义 |
| 弹「该应用是为旧版 Android 构建」 | `targetSdk = 28` 偏低 | 暂时忽略，或升 targetSdk 后重编 |

---

## 应用内更新（检查更新）

装上 3.0.6 及以后，就不用再拷 U 盘了：**设置页最下面 → 版本更新**。

- **检查更新**：读仓库根目录的 [`update.json`](update.json)，比对版本号；
- **更新日志**：随时查看最新版本改了什么，只是看一眼，不会下载任何东西；
- 有新版本时弹窗列出更新内容，**点「立即更新」才下载安装**，点「以后再说」就跳过这次；
- 全程没有自动检查、没有后台下载、没有静默安装——装不装始终由你决定。

> 第一次从 3.0.5 及更早版本升级还是要手动装一次，因为旧版本里没有这个入口。

`update.json` 就是发布端要维护的唯一文件，字段很短：

```json
{
  "versionName": "3.0.9",
  "versionCode": 13,
  "date": "2026-10-26",
  "size": 116605,
  "apk": "https://github.com/mmaotv/cqcuiptv/releases/download/app-latest/LightLive-3.0.9.apk",
  "notes": ["这一版改了什么", "一行一条"]
}
```

发版流程：改 `app/build.gradle.kts` 里的版本号 → 推送触发 CI 出 `app-latest` 的 Release →
把 `update.json` 的版本号、包大小、安装包地址和说明改成新版本 → 推送。
只剩 `update.json` 变更时 CI 不会重新构建，不会多出一次 Release。

清单同时从 `raw.githubusercontent.com` 和 jsDelivr 两个源并发取（谁先回用谁），
国内 GitHub 时通时断时也能查出结果。

## 自己编译

需要 JDK 17+ 和 Android SDK（compileSdk 34）：

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/LightLive-3.0.0.apk
```

想把自己的列表打进 APK 当默认列表，可以覆盖默认地址：

```bash
./gradlew assembleRelease -PdefaultPlaylist=http://192.168.1.10:8000/my.m3u
```

图标与 TV banner 由 `scripts/make_icons.py` 生成（纯标准库，不需要 Pillow）：

```bash
python scripts/make_icons.py
```

签名用仓库里的 `keystore/qinglive.jks`（密码 `qinglive`，公开自签名，只为保证版本之间能覆盖安装）。
推送到 `main` 后 GitHub Actions 会自动构建并更新 `app-latest` Release。

---

## 已知限制

- 源本身只能在重庆联通网络下播放，别的网络放不出来跟 App 无关
- **4K 频道要看盒子的硬解能力**：App 会先读解码器的能力表，把解码尺寸夹到它真正支持的上限内
  （例如 MTK 的 `OMX.MTK.VIDEO.DECODER.HEVC` 上限只有 2560×1440），再按这个尺寸配置，
  不做软解、不转码。盒子本身解不动 4K 时会明确提示「这个频道解不了：3840×2160 / 本机 HEVC 硬解最高只到 2560×1440」，
  而不是黑屏或者卡死。要真 4K 需要换一颗支持 4K HEVC 的芯片（S905X3 及以上）
- 部分固件的 4K HEVC 10bit 硬解有问题（表现为纯绿画面），遇到会退一档尺寸
- 解码器在播放中途被系统回收时，播放线程会立刻退出并提示，不会空转烧 CPU
- 音频统一输出双声道，5.1 / 7.1 源在软件里降混成立体声（盒子大多只有双声道输出）
- 节目单首次下载要几十秒到一分钟（约 9 MB），之后读缓存；节目单源没收录的台（付费频道、地方台）第二行显示「暂无节目单」
- 没有回看、收藏夹——这个 App 只干一件事：把台放出来、把节目单写清楚
- 已在 晶晨 S905L3A / Android 9、MTK Android 14 / 1612×720 上实测，源码按 Android 4.4 的 API 编写

---

## 播放列表文件说明

- **cqcu-tv.m3u**

  为 App 重排过的版本：CCTV1 是 1 台，卫视紧跟央视之后，并细化了 `group-title` 分类。
  **播放器/新装 App 推荐用这个。**

- **cqcu-unicast_epg.m3u**

  带台标 `tvg-logo` + 节目单 `tvg-id` 的完整版，按分组字母序排列，适合 VLC / Kodi / mytv-android 这类播放器。

- **cqcu-unicast.m3u / cqcu-unicast-alternative.m3u**

  重庆联通单播源，公网或 IPTV 专网均可播放。

  - 没有开通 IPTV 业务的用户也能观看
  - 必须要支持 rtsp 流的播放器才可使用，比如 VLC、potplayer

- **cqcu-multicast.m3u / cqcu-multicast-udpxy.m3u**

  重庆联通组播源，只能在 IPTV 专网播放。带 udpxy 的版本做了组播转单播，
  请按实际情况修改 udpxy 服务器地址与端口（可能会造成切换频道卡顿）。

### 自动更新

`.github/workflows/update.yml` 每天 0 点（UTC）跑一次：

1. `scripts/build_epg_m3u.py` 重新生成带台标/EPG 的 `cqcu-unicast_epg.m3u`
2. `scripts/build_tv_m3u.py` 据此重排出「台号顺序版」`cqcu-tv.m3u`，并同步到 `app/src/main/assets/playlist.m3u`

### 配置说明

参考 [重庆联通 IPTV 单线复用 + 内网融合教程](https://blog.imouto.in/post/iptv/2022/cqcu-iptv-on-openwrt/)。

---

## English

**LightLive (轻直播)** is a tiny (~100 KB, **zero-dependency**) Android TV / set-top-box **live-TV client**
for playing **IPTV / M3U playlists**.

- 📺 **Channel numbers like a real TV** — press `1` for CCTV1, `18` for 湖南卫视 (Hunan TV), etc.
- 🗂️ **Categories** from M3U `group-title`; **EPG** program guide (XMLTV) with "now playing / next".
- 📡 **Custom sources** — paste a M3U URL or the whole playlist; not tied to the bundled China Unicom list.
- 🎮 **Remote + touch** — D-pad number keys, or tap left/right to change channel, tap center for the list.
- 🧩 **Self-built player** — RTSP (TCP interleaved) → RTP → MPEG-TS → `MediaCodec` hardware decode
  (HEVC / AVC), pure-Java MP2 soft-decode, AAC / AC3 / EAC3 via system decoder, unified `AudioTrack`.
- 🪶 **~100 KB**, no AndroidX / ExoPlayer / VLC; **Android 4.4+** (API 19).

**Download / build**

```bash
# build
./gradlew assembleRelease
# or grab the latest APK from Releases
```

Default playlist and EPG are fetched over the network; the APK also ships a built-in list for offline use.

*Keywords for search:* `android iptv player`, `m3u`, `m3u8`, `epg`, `live tv`, `rtsp`, `mpeg-ts`,
`media player`, `android tv`, `set-top box`, `free`, `open source`.

---

## 相关项目

- [mytv-android](https://github.com/mytv-android/mytv-android) —— 另一个开源安卓直播 App，可作为替代品参考
- [fanmingming/live](https://github.com/fanmingming/live) —— 本项目的 EPG（节目单）数据来源
- [delthas/JavaMP3](https://github.com/delthas/JavaMP3) —— 本项目的 MP2 音频软解移植来源

---

## 致谢与许可

- MP2 音频解码移植自 [delthas/JavaMP3](https://github.com/delthas/JavaMP3)（MIT），
  许可证见 `third_party/javamp3/LICENSE`
- 节目单数据来自 [fanmingming/live](https://github.com/fanmingming/live)

本项目基于 **MIT 许可证** 开源（见 [LICENSE](LICENSE)）。

本项目仅用于学习和个人观看，直播源版权归各电视台所有，请勿用于商业用途。

如果这个项目对你有用，欢迎 **Star** ⭐ 让更多人能看到：<https://github.com/mmaotv/cqcuiptv>
