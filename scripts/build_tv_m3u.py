#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 cqcu-unicast_epg.m3u 重排成"适合电视遥控器"的频道顺序，输出 cqcu-tv.m3u。

为什么需要重排：
  cqcu-unicast_epg.m3u 是按分组英文序生成的，CCTV 与其他台混在一起，
  在电视上按数字键根本对不上台号。这里按下面的顺序输出，使：

      1 台 = CCTV1，2 台 = CCTV2 …… CCTV 全部排完之后紧接着是湖南卫视等卫视台

  这样遥控器按 1 就是 CCTV1，按 1、2 就是 12 台，跟运营商盒子一致。

同时把结果复制一份到 app/src/main/assets/playlist.m3u，作为 App 的内置兜底列表。

用法：python scripts/build_tv_m3u.py
"""
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "cqcu-unicast_epg.m3u")
DST = os.path.join(ROOT, "cqcu-tv.m3u")
ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "playlist.m3u")

# 台号顺序（先 CCTV 主频道，紧接着卫视，之后才是央视付费频道和别的分组）
RANK_CCTV = 0      # CCTV1 … CCTV17
RANK_WEISHI = 1    # 湖南卫视 … 各省卫视
RANK_CCTV_PAY = 2  # CCTV 付费频道 / CETV / CHC / CGTN
GROUP_RANK = {"重庆": 3, "上海": 4, "IPTV": 5, "影视": 6, "其他": 7}

# 卫视频道内部顺序：湖南卫视排第一（紧跟央视之后），其余按常见习惯
WEISHI_ORDER = [
    "湖南卫视", "浙江卫视", "江苏卫视", "东方卫视", "北京卫视",
    "广东卫视", "深圳卫视", "四川卫视", "天津卫视", "安徽卫视",
    "山东卫视", "山东教育卫视", "湖北卫视", "河南卫视", "河北卫视",
    "江西卫视", "辽宁卫视", "吉林卫视", "黑龙江卫视", "山西卫视",
    "陕西卫视", "甘肃卫视", "青海卫视", "宁夏卫视", "新疆卫视",
    "新疆兵团卫视", "西藏卫视", "云南卫视", "贵州卫视", "广西卫视",
    "海南卫视", "内蒙古卫视", "东南卫视", "重庆卫视",
]

# 央视付费 / 教育 / 外语 / 电影频道内部顺序
PAY_ORDER = [
    "CCTV兵器科技", "CCTV第一剧场", "CCTV风云剧场", "CCTV风云音乐",
    "CCTV风云足球", "CCTV高尔夫网球", "CCTV怀旧剧场", "CCTV女性时尚",
    "CCTV世界地理", "CCTV央视台球", "CCTV央视文化精品",
    "CETV1", "CETV4", "CETV早期教育", "CGTN",
    "CHC动作电影", "CHC高清电影", "CHC家庭影院", "CHC影迷电影",
]

TRAILING = ["4K", "HEVC", "HDR", "AAC", "AC3", "SD", "HD", "+"]
CCTV_RE = re.compile(r"^CCTV\s*(\d+)\s*(\+)?")


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


class Rec(object):
    def __init__(self, idx, extinf, url, name, group):
        self.idx = idx
        self.extinf = extinf
        self.url = url
        self.name = name
        self.group = group

    def key(self):
        m = CCTV_RE.match(self.name)

        # 1) 央视主频道：CCTV1、CCTV2 …… CCTV5、CCTV5+ …… CCTV17
        if m and self.group == "央视":
            return (RANK_CCTV, int(m.group(1)), 0 if not m.group(2) else 1, 0, self.idx)

        # 2) 卫视：湖南卫视打头
        if self.group == "卫视":
            base = strip_trailing(self.name)
            k = WEISHI_ORDER.index(base) if base in WEISHI_ORDER else 500 + self.idx
            return (RANK_WEISHI, 0, 0, k, self.idx)

        # 3) 央视付费频道 / CETV / CHC / CGTN
        if self.group == "央视":
            base = strip_trailing(self.name)
            k = PAY_ORDER.index(base) if base in PAY_ORDER else 900
            return (RANK_CCTV_PAY, 0, 0, k, self.idx)

        # 4) 其余分组保持源文件里的相对顺序
        rank = GROUP_RANK.get(self.group, 7)
        return (rank, 0, 0, 0, self.idx)


def read_records(path):
    with open(path, encoding="utf-8") as f:
        lines = [l.rstrip("\n") for l in f]

    header = None
    recs = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("#EXTM3U") and header is None:
            header = line
            i += 1
            continue
        if line.startswith("#EXTINF"):
            name = line.rsplit(",", 1)[-1].strip() if "," in line else ""
            m = re.search(r'group-title="([^"]*)"', line)
            group = m.group(1) if m else ""
            url = ""
            j = i + 1
            while j < len(lines) and lines[j].startswith("#"):
                j += 1
            if j < len(lines):
                url = lines[j].strip()
            recs.append(Rec(len(recs), line, url, name, group))
            i = j + 1
            continue
        i += 1
    return header, recs


def main():
    if not os.path.exists(SRC):
        print(f"[错误] 找不到 {SRC}", file=sys.stderr)
        return 1

    header, recs = read_records(SRC)
    if not recs:
        print("[错误] 源列表里没有频道", file=sys.stderr)
        return 1

    recs.sort(key=lambda r: r.key())

    out = [header or "#EXTM3U"]
    for r in recs:
        out.append(r.extinf)
        if r.url:
            out.append(r.url)
    text = "\n".join(out) + "\n"

    with open(DST, "w", encoding="utf-8") as f:
        f.write(text)

    os.makedirs(os.path.dirname(ASSET), exist_ok=True)
    shutil.copyfile(DST, ASSET)

    print(f"已生成: {DST}")
    print(f"已同步: {ASSET}")
    print(f"总频道: {len(recs)}")
    print("前 20 个频道：")
    for n, r in enumerate(recs[:20], 1):
        print(f"  {n:>3}  {r.name}")
    cctv = sum(1 for r in recs if CCTV_RE.match(r.name) and r.group == "央视")
    print(f"CCTV 主频道占前 {cctv} 个台号，卫视从第 {cctv + 1} 个开始")
    return 0


if __name__ == "__main__":
    sys.exit(main())
