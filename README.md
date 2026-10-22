# 轻直播 · LightTV

一个 **约 100 KB** 的电视直播客户端，遥控器、触摸屏都能用：
按 `1` 就是 CCTV1，CCTV 全部排完之后紧接着是湖南卫视等卫视台；频道按央视 / 卫视 / 4K / 各地
分类展示，每个频道都带节目单（EPG）。

配套提供一套重庆联通 IPTV 的频道列表（`cqcu-tv.m3u`），但这些源只能在**重庆联通网络**下播放，
其他网络打开会黑屏/超时。

![播放画面](docs/screenshots/01-live.jpg)

---

## 特性

- **分类浏览**：直接读 M3U 的 `group-title`，左侧分类、右侧该分类下的频道，分类里还能看到频道数
- **节目单（EPG）**：每个频道显示「正在播 / 下一档」，`INFO` 键呼出当天完整节目表，正在播出的那一档高亮
- **自研播放内核**：RTSP(TCP 交织) → RTP → MPEG-TS → `MediaCodec` 硬解直接上屏，音频 MP2 用纯 Java 软解，
  AAC / AC3 / EAC3 走系统解码器，最后统一进 `AudioTrack`
- **按源分辨率播放**：从码流 SPS 里解出真实分辨率（1920×1080 / 3840×2160）再配置解码器，
  不降级、不软转码；只有当解码器根本建不起来时才退一档
- **体积**：零第三方依赖（不用 AndroidX / ExoPlayer / VLC），release 包约 100 KB
- **兼容**：minSdk 19，Android 4.4 及以上都能装
- **换台**：数字键直达频道号，上下键依次换台，左右键调音量
- **触摸屏 / 平板**：单击显隐台标、双击频道列表、左右滑动换台、上下滑动调音量、长按设置
- **本地缓存**：从地址拉下来的列表存到本地，下次启动直接读，不重复下载；有效期可选 7 天 / 15 天 / 一个月 / 不自动
- **局域网填源**：手机上打开 `http://盒子IP:8965` 就能粘贴 M3U，不用拿遥控器打字
- **自定义直播源**：设置页填 m3u 地址，或者把整段 m3u 内容直接粘进去
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

| 手势 | 作用 |
| --- | --- |
| 单击画面 | 显示 / 隐藏台标与当前节目 |
| 双击 | 打开 / 关闭频道列表 |
| 左右滑动 | 换台（左滑下一台、右滑上一台） |
| 上下滑动 | 音量（上滑加、下滑减） |
| 长按 | 打开设置 |

系统栏会自动隐藏（沉浸式全屏），面板宽度按屏幕宽度自适应，手机横屏也不会超出画面。

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
- 列表第二行显示「正在播」的节目名；`INFO` 键打开节目单，正在播出的那一档用橙色标出

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

`SpsParser` 从码流的 SPS 里解出**真实**分辨率，然后按这个尺寸 `configure` 解码器：

- 1080P 的源按 1920×1080 配
- 4K 的源按 3840×2160 配，屏幕是 1080p 的话由芯片的硬件缩放器缩到屏幕上（这不是转码）
- 只有 `configure` 抛异常（个别固件 4K 硬解起不来）时，才退到 1920×1080 再试一次

OSD 第三行会显示实际配置，例如 `HEVC 3840x2160 · AAC`；真退档了会显示
`3840x2160 → 1920x1080`，一眼能看出来。

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

- **直接下载**：[Releases](https://github.com/mmaotv/cqcuiptv/releases/latest) 里的 `LightTV-*.apk`
- **adb 安装**：

  ```bash
  adb install -r LightTV-2.0.0.apk
  ```

装好以后在电视桌面上找 **轻直播**（蓝紫渐变 + 白色电视图标）。

> 包名仍然是 `com.miaomiaotv.cqcuiptv`，这样从 1.x 升级上来可以直接覆盖安装，不用先卸载。

## 自己编译

需要 JDK 17+ 和 Android SDK（compileSdk 34）：

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/LightTV-2.0.0.apk
```

想把自己的列表打进 APK 当默认列表，可以覆盖默认地址：

```bash
./gradlew assembleRelease -PdefaultPlaylist=http://192.168.1.10:8000/my.m3u
```

图标与 TV banner 由 `scripts/make_icons.py` 生成（纯标准库，不需要 Pillow）：

```bash
python scripts/make_icons.py
```

签名用仓库里的 `keystore/cqcuiptv.jks`（密码 `cqcuiptv`，公开自签名，只为保证版本之间能覆盖安装）。
推送到 `main` 后 GitHub Actions 会自动构建并更新 `app-latest` Release。

---

## 已知限制

- 源本身只能在重庆联通网络下播放，别的网络放不出来跟 App 无关
- 部分电视盒子固件的 4K HEVC 10bit 硬解有问题（表现为纯绿画面），遇到会退到 1080p 配置
- 音频统一输出双声道，5.1 / 7.1 源在软件里降混成立体声（盒子大多只有双声道输出）
- 节目单首次下载要几十秒到一分钟（约 9 MB），之后读缓存；节目单源失效时列表第二行为空
- 没有回看、收藏夹——这个 App 只干一件事：把台放出来、把节目单写清楚
- 已在 晶晨 S905L3A / Android 9 / 1920×1080 上实测，源码按 Android 4.4 的 API 编写

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

其他播放器也可以用它：[mytv-android](https://github.com/mytv-android/mytv-android)

### 自动更新

`.github/workflows/update.yml` 每天 0 点（UTC）跑一次：

1. `scripts/build_epg_m3u.py` 重新生成带台标/EPG 的 `cqcu-unicast_epg.m3u`
2. `scripts/build_tv_m3u.py` 据此重排出「台号顺序版」`cqcu-tv.m3u`，并同步到 `app/src/main/assets/playlist.m3u`

### 配置说明

参考 [重庆联通 IPTV 单线复用 + 内网融合教程](https://blog.imouto.in/post/iptv/2022/cqcu-iptv-on-openwrt/)。

---

## 致谢

- MP2 音频解码移植自 [delthas/JavaMP3](https://github.com/delthas/JavaMP3)（MIT），
  许可证见 `third_party/javamp3/LICENSE`
- 节目单数据来自 [fanmingming/live](https://github.com/fanmingming/live)

本项目仅用于学习和个人观看，直播源版权归各电视台所有，请勿用于商业用途。
