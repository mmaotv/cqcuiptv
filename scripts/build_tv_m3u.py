#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 cqcu-unicast_epg.m3u 重排成"适合电视遥控器 + 分好类"的频道表，输出 cqcu-tv.m3u。

重排规则（决定台号）：
    1 台 = CCTV1 …… 17 台 = CCTV17+CCTV5+，18 台起是湖南卫视等卫视台，
    之后是 4K 超高清、央视付费、重庆、上海、IPTV、影视、其他。

同时重写 group-title，把原来的粗分组细化成：
    央视 / 卫视 / 4K超高清 / 央视付费 / 重庆 / 上海 / IPTV / 影视 / 其他
这样 App 里就能按分类浏览，而不是 150 个台平铺。

结果会复制一份到 app/src/main/assets/playlist.m3u 作为内置兜底列表。

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

# 分类与排序权重
CATEGORIES = ["央视", "卫视", "4K超高清", "央视付费", "重庆", "上海", "IPTV", "影视", "其他"]
RANK = {name: i for i, name in enumerate(CATEGORIES)}

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
        self.category = self.classify()
        self.extinf = self.rebuild_extinf()

    def classify(self):
        if "4k" in self.name.lower() or "4K" in self.name:
            return "4K超高清"
        m = CCTV_RE.match(self.name)
        if m and self.group == "央视":
            return "央视"
        if self.group == "卫视":
            return "卫视"
        if self.group == "央视":
            return "央视付费"
        return self.group if self.group in RANK else "其他"

    def rebuild_extinf(self):
        line = self.extinf
        if re.search(r'group-title="[^"]*"', line):
            return re.sub(r'group-title="[^"]*"', 'group-title="%s"' % self.category, line, count=1)
        # 没有 group-title 就补一个
        return line.replace("#EXTINF:-1", '#EXTINF:-1 group-title="%s"' % self.category, 1)

    def key(self):
        rank = RANK[self.category]
        m = CCTV_RE.match(self.name)

        if self.category == "央视" and m:
            return (rank, int(m.group(1)), 0 if not m.group(2) else 1, 0, self.idx)

        if self.category == "卫视":
            base = strip_trailing(self.name)
            k = WEISHI_ORDER.index(base) if base in WEISHI_ORDER else 500 + self.idx
            return (rank, 0, 0, k, self.idx)

        if self.category == "4K超高清":
            base = strip_trailing(self.name)
            k = WEISHI_ORDER.index(base) if base in WEISHI_ORDER else 500 + self.idx
            return (rank, 0, 0, k, self.idx)

        if self.category == "央视付费":
            base = strip_trailing(self.name)
            k = PAY_ORDER.index(base) if base in PAY_ORDER else 900 + self.idx
            return (rank, 0, 0, k, self.idx)

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

    counts = {}
    for r in recs:
        counts[r.category] = counts.get(r.category, 0) + 1
    print(f"已生成: {DST}")
    print(f"已同步: {ASSET}")
    print(f"总频道: {len(recs)}")
    for c in CATEGORIES:
        if counts.get(c):
            print(f"  {c:<8} {counts[c]}")
    print("前 20 个频道：")
    for n, r in enumerate(recs[:20], 1):
        print(f"  {n:>3}  {r.name}  [{r.category}]")
    return 0


if __name__ == "__main__":
    sys.exit(main())
