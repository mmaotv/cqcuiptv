#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 App 图标与 TV banner（纯标准库，不依赖 Pillow）。

图形：蓝紫渐变的圆角方块 + 白色电视外框（屏内一个播放三角）+ 底座。
4x 超采样后箱式缩小，边缘平滑。
输出：
  app/src/main/res/mipmap-*/ic_launcher.png
  app/src/main/res/drawable-nodpi/banner.png（320x180，Android TV 桌面卡片）
"""
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(HERE), "app", "src", "main", "res")

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BANNER = (320, 180)
SS = 4  # 超采样倍数

BG_TOP = (47, 107, 255)
BG_BOTTOM = (124, 58, 237)
WHITE = (255, 255, 255)


def in_round_rect(x, y, x0, y0, x1, y1, r):
    if x < x0 or y < y0 or x > x1 or y > y1:
        return False
    cx = min(max(x, x0 + r), x1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    dx = x - cx
    dy = y - cy
    return dx * dx + dy * dy <= r * r


def in_triangle(px, py, ax, ay, bx, by, cx, cy):
    d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
    d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
    d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
    neg = (d1 < 0) or (d2 < 0) or (d3 < 0)
    pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
    return not (neg and pos)


def glyph(u, v):
    """电视图形，坐标都在 0..1 的自身方框里。返回是否白色。"""
    # 屏幕外框
    outer = in_round_rect(u, v, 0.04, 0.14, 0.96, 0.72, 0.11)
    inner = in_round_rect(u, v, 0.04 + 0.065, 0.14 + 0.065, 0.96 - 0.065, 0.72 - 0.065, 0.06)
    if outer and not inner:
        return True
    # 屏内播放三角
    if in_triangle(u, v, 0.40, 0.30, 0.40, 0.56, 0.62, 0.43):
        return True
    # 脖子 + 底座
    if in_round_rect(u, v, 0.455, 0.70, 0.545, 0.79, 0.03):
        return True
    if in_round_rect(u, v, 0.26, 0.79, 0.74, 0.86, 0.035):
        return True
    return False


def render(size):
    """App 图标：圆角方块 + 居中的电视图形。"""
    n = size * SS
    radius = n * 0.22
    box = 0.74 * n            # 图形占方块的比例
    ox = (n - box) / 2.0
    oy = (n - box) / 2.0
    px = []
    for y in range(n):
        t = y / float(n - 1)
        bg = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3))
        row = []
        for x in range(n):
            fx = x + 0.5
            fy = y + 0.5
            if not in_round_rect(fx, fy, 0, 0, n, n, radius):
                row.append((0, 0, 0, 0))
                continue
            u = (fx - ox) / box
            v = (fy - oy) / box
            if glyph(u, v):
                row.append((255, 255, 255, 255))
            else:
                row.append((bg[0], bg[1], bg[2], 255))
        px.append(row)
    return downscale(px, size)


def render_banner(w, h):
    """TV banner：横向铺满渐变，图形放中间。"""
    n = 4
    W, H = w * n, h * n
    box = 0.80 * H
    ox = (W - box) / 2.0
    oy = (H - box) / 2.0
    px = []
    for y in range(H):
        t = y / float(H - 1)
        bg = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3))
        row = []
        for x in range(W):
            u = (x + 0.5 - ox) / box
            v = (y + 0.5 - oy) / box
            if glyph(u, v):
                row.append((255, 255, 255, 255))
            else:
                row.append((bg[0], bg[1], bg[2], 255))
        px.append(row)
    return downscale(px, w, h, factor=n)


def downscale(px, w, h=None, factor=SS):
    h = w if h is None else h
    out = []
    for y in range(h):
        row = []
        for x in range(w):
            r = g = b = a = 0
            for dy in range(factor):
                for dx in range(factor):
                    p = px[y * factor + dy][x * factor + dx]
                    r += p[0] * p[3]
                    g += p[1] * p[3]
                    b += p[2] * p[3]
                    a += p[3]
            if a == 0:
                row.append((0, 0, 0, 0))
            else:
                row.append((r // a, g // a, b // a, a // (factor * factor)))
        out.append(row)
    return out


def write_png(path, pixels):
    h = len(pixels)
    w = len(pixels[0])
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    return len(png)


def main():
    for dens, size in SIZES.items():
        d = os.path.join(RES, "mipmap-" + dens)
        if not os.path.isdir(d):
            os.makedirs(d)
        p = os.path.join(d, "ic_launcher.png")
        n = write_png(p, render(size))
        print(f"{p}  {size}x{size}  {n} bytes")

    p = os.path.join(RES, "drawable-nodpi", "banner.png")
    n = write_png(p, render_banner(*BANNER))
    print(f"{p}  {BANNER[0]}x{BANNER[1]}  {n} bytes")


if __name__ == "__main__":
    main()
