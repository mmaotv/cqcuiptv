#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 cqcu-unicast.m3u 补全成带台标(tvg-logo) + 节目预告(EPG) 的 IPTV 播放列表。

EPG 策略(全部远程、自动更新，播放器自行拉取，无本地静态文件)：
  - url-tvg 同时指向三个远程 XMLTV 源:
      1) https://epg.112114.xyz/pp.xml.gz        (央视/卫视/全国台, 字符id)
      2) http://epg.51zmt.top:8000/e.xml.gz      (CETV/CHC/重庆卫视, 数字id)
      3) https://epg.zsdc.eu.org/t.xml.gz         (央视/卫视/CETV/CHC, 字符id, GitHub Actions 日更)
  - 每个频道按优先级取"存在的源"的 tvg-id:  zsdc(字符) > 51zmt(数字) > 112114(字符)
  - tvg-logo 优先 fanmingming, 缺的用 vircloud/TVLogo(其文件名即 112114 id) 兜底

运行时需要联网(拉台标目录清单 + 两个 EPG 源的频道列表做精确 id 匹配)。
"""
import re
import os
import json
import sys
import urllib.request
import urllib.error
import gzip

# 路径自适应: 优先用仓库内的源文件(便于 GitHub Actions 自动跑), 本地调试回退到原位置
_HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(_HERE)  # scripts/ 的上一级 = 仓库根
_SRC = os.path.join(REPO_ROOT, "cqcu-unicast.m3u")
INPUT  = _SRC if os.path.exists(_SRC) else r"D:\Edge浏览器下载\cqcu-unicast.m3u"
OUTPUT = os.path.join(REPO_ROOT, "cqcu-unicast_epg.m3u")

# 三个自动更新远程 EPG 源
EPG_112114 = "https://epg.112114.xyz/pp.xml.gz"
EPG_51ZMT  = "http://epg.51zmt.top:8000/e.xml.gz"
EPG_ZSDC   = "https://epg.zsdc.eu.org/t.xml.gz"

FAN_RAW = "https://live.fanmingming.cn/tv/{}.png"          # fanmingming 台标
VIR_RAW = "https://raw.githubusercontent.com/vircloud/TVLogo/master/{}.png"  # 112114-id 台标

# 51zmt 用中文名表示付费/教育台, 这里把我们的代号映射过去
ALIAS_51ZMT = {
    "CETV1": "中国教育1台",
    "CETV4": "中国教育4台",
}

TRAILING = ["4K", "HEVC", "HDR", "AAC", "AC3", "SD", "HD", "+"]
UA = {"User-Agent": "Mozilla/5.0"}


def github_png_names(repo, path=""):
    names = set()
    url = f"https://api.github.com/repos/{repo}/contents/{path}?per_page=1000"
    while url:
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=30) as r:
                data = json.load(r)
                link = r.headers.get("Link", "")
            for it in data:
                if it["name"].lower().endswith(".png"):
                    names.add(it["name"][:-4])
            nxt = None
            for part in link.split(","):
                if 'rel="next"' in part:
                    nxt = part[part.find("<") + 1:part.find(">")]
            url = nxt
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as e:
            print(f"[警告] 拉取 {repo} 失败: {e}", file=sys.stderr)
            break
    return names


def fetch_xmltv_channels(url):
    """拉取 XMLTV 的 <channel> 段, 返回 {归一化名: id}。"""
    out = {}
    req = urllib.request.Request(url, headers=UA)

    def _parse(stream):
        buf = ""
        for raw in stream:
            line = raw.decode("utf-8", "ignore") if isinstance(raw, bytes) else raw
            if line.startswith("<programme"):
                break
            buf += line
            if "</channel>" in buf:
                m = re.search(r'<channel id="([^"]*)">', buf)
                if m:
                    cid = m.group(1)
                    nm = re.search(r"<display-name[^>]*>([^<]*)</display-name>", buf)
                    name = nm.group(1) if nm else ""
                    out[normalize(name)] = cid
                buf = ""

    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            _parse(gzip.GzipFile(fileobj=r))   # 先按 gzip 解析
    except OSError:
        # 源偶尔返回明文 XML(非 gz): 重新拉取按明文解析
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                _parse(r)
        except Exception as e:
            print(f"[警告] 拉取 EPG 源失败 {url}: {e}", file=sys.stderr)
    except Exception as e:
        print(f"[警告] 拉取 EPG 源失败 {url}: {e}", file=sys.stderr)
    return out


def normalize(s):
    return re.sub(r"\s+", "", s or "")


def strip_trailing(name):
    n = re.sub(r"\s+", "", name)
    changed = True
    while changed:
        changed = False
        for t in TRAILING:
            if n.endswith(t) and len(n) > len(t):
                n = n[: -len(t)]
                changed = True
                break
    return n


def base_id(name):
    raw = name.strip()
    if raw.startswith("爱上"):
        return "爱上4K"
    m = re.match(r"CCTV\s*(\d+)\s*(\+)?", raw)
    if m:
        return "CCTV" + m.group(1) + (m.group(2) or "")
    return strip_trailing(raw)


def lookup_51zmt(nb, zsmt):
    if nb in zsmt:
        return zsmt[nb]
    alias = ALIAS_51ZMT.get(nb)
    if alias and normalize(alias) in zsmt:
        return zsmt[normalize(alias)]
    return None


def group_of(name):
    if re.match(r"(CCTV|CETV|CGTN|CHC)", name):
        return "央视"
    if name.startswith("重庆"):
        return "重庆"
    if name.startswith("上海"):
        return "上海"
    if name.startswith("IPTV"):
        return "IPTV"
    if "卫视" in name:
        return "卫视"
    if any(k in name for k in ["电影", "剧场", "影院", "影视", "剧", "戏曲", "纪录", "动画", "卡通"]):
        return "影视"
    return "其他"


def main():
    print("拉取台标清单 (fanmingming / vircloud) ...")
    fan = github_png_names("fanmingming/live", "tv")
    vir = github_png_names("vircloud/TVLogo")
    print(f"  fanmingming 台标: {len(fan)} 个, vircloud(112114-id): {len(vir)} 个")

    print("拉取 EPG 源频道列表 (112114 / 51zmt / zsdc) ...")
    e112 = fetch_xmltv_channels(EPG_112114)
    if not e112:  # 抓取失败则用 vircloud 文件名作为 112114 id 代理
        e112 = {normalize(v): v for v in vir}
    zsmt = fetch_xmltv_channels(EPG_51ZMT)
    zsdc = fetch_xmltv_channels(EPG_ZSDC)
    print(f"  112114 频道: {len(e112)} 个, 51zmt: {len(zsmt)} 个, zsdc: {len(zsdc)} 个")

    out = []
    out.append(f'#EXTM3U url-tvg="{EPG_112114},{EPG_51ZMT},{EPG_ZSDC}"')
    no_logo = []
    n_zsdc = n_51 = n_112 = n_fb = 0
    fb_channels = []   # 退回中文名, 依赖 112114 按名匹配(沙箱不可达无法验证)

    with open(INPUT, encoding="utf-8") as f:
        lines = [l.rstrip("\n") for l in f]

    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("#EXTINF"):
            ch_name = line.split(",", 1)[1].strip() if "," in line else ""
            url = lines[i + 1] if i + 1 < len(lines) else ""
            base = base_id(ch_name)
            nb = normalize(base)

            # EPG id: zsdc(字符) > 51zmt(数字) > 112114(字符) > 中文名兜底
            if nb in zsdc:
                tid = zsdc[nb]; n_zsdc += 1
            else:
                t51 = lookup_51zmt(nb, zsmt)
                if t51:
                    tid = t51; n_51 += 1
                elif nb in e112:
                    tid = e112[nb]; n_112 += 1
                else:
                    tid = base   # 退回中文名, 播放器端 112114 多按名称匹配
                    n_fb += 1; fb_channels.append(ch_name)

            # 台标: 优先 fanmingming, 否则 vircloud(112114-id)
            if nb in fan:
                logo = FAN_RAW.format(nb)
            elif nb in vir:
                logo = VIR_RAW.format(nb)
            elif base in fan:
                logo = FAN_RAW.format(base)
            elif base in vir:
                logo = VIR_RAW.format(base)
            else:
                logo = ""
                no_logo.append(ch_name)

            grp = group_of(ch_name)
            ext = f'#EXTINF:-1 tvg-id="{tid}" tvg-logo="{logo}" group-title="{grp}",{ch_name}'
            out.append(ext)
            if url:
                out.append(url)
            i += 2
        else:
            i += 1

    with open(OUTPUT, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")

    total = sum(1 for l in out if l.startswith("#EXTINF"))
    print(f"\n已生成: {OUTPUT}")
    print(f"总频道: {total}")
    print(f"  EPG 已分配 id: zsdc(字符){n_zsdc} + 51zmt(数字){n_51} + 112114(字符){n_112}"
          f" = 已验证源 {n_zsdc+n_51+n_112}")
    print(f"  退回中文名兜底(播放器端由 112114 按名匹配, 沙箱不可达未验证): {n_fb}")
    if fb_channels:
        print("  全量清单(多为卫视/地方台/CCTV付费台, 播放器端大概率也有节目单):")
        for n in fb_channels:
            print(f"    {n}")
    print(f"  无台标: {len(no_logo)} 个 (已留空 tvg-logo)")
    if no_logo:
        print("  无台标清单:")
        for n in no_logo:
            print(f"    {n}")


if __name__ == "__main__":
    main()
