## CQCU-IPTV

适用于重庆联通用户的 IPTV 频道列表。

注意：此频道列表无法在 **非重庆联通** 网络环境下使用。

可用这个软件使用https://github.com/mytv-android/mytv-android

### 文件说明

- cqcu-unicast.m3u / cqcu-unicast-alternative.m3u

  重庆联通单播源，公网或 IPTV 专网均可播放。

  - 没有开通 IPTV 业务的用户也能观看
  - 必须要支持rtsp流的播放器才可使用，比如VLC、potplayer

- cqcu-multicast.m3u

  重庆联通组播源，只能在 IPTV 专网播放。

- cqcu-multicast-udpxy.m3u

  重庆联通组播源，只能在 IPTV 专网播放。

  配置了 udpxy 组播转单播，有助于避免广播风暴或在不支持 rtp 协议的设备上观看。

  请注意按实际情况更改 udpxy 服务器地址与端口。

  - 可能会造成切换频道卡顿

台标版和节目指南怎么用 / 注意
1.两个文件放同一目录（cqcu-unicast_epg.m3u 和 cqcu-epg-supplement.xml）。若播放器是从本地存储加载 m3u 且支持相对路径的 EPG，直接就行；否则把 XML 托管到一个可访问的 http 地址（GitHub raw / 本地 web 服务器 / NAS），把 url-tvg 里那个 cqcu-epg-supplement.xml 换成完整 URL。
2.补充源是静态快照（7 天），不会自动更新。需要刷新时重跑 scripts/build_epg_supplement.py 即可（也可让它每天定时跑）

### 配置说明

参考 [重庆联通 IPTV 单线复用 + 内网融合教程](https://blog.imouto.in/post/iptv/2022/cqcu-iptv-on-openwrt/)。
