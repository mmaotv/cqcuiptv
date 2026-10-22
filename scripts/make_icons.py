#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 App 图标（纯标准库，不依赖 Pillow）。

图标：红色圆角底 + 白色播放三角，4x 超采样后箱式缩小，边缘平滑。
输出 app/src/main/res/mipmap-*/ic_launcher.png
"""
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(HERE), "app", "src", "main", "res")

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SS = 4  # 超采样倍数

BG_TOP = (255, 74, 58)
BG_BOTTOM = (196, 30, 22)


def in_round_rect(x, y, w, h, r):
    if x < 0 or y < 0 or x > w or y > h:
        return False
    cx = min(max(x, r), w - r)
    cy = min(max(y, r), h - r)
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


def render(size):
    """返回 size*size 的 RGBA 像素列表。"""
    n = size * SS
    px = []
    radius = n * 0.22
    # 播放三角（略微右移，视觉居中）
    ax, ay = n * 0.35, n * 0.27
    bx, by = n * 0.35, n * 0.73
    cx, cy = n * 0.72, n * 0.50
    for y in range(n):
        t = y / float(n - 1)
        bg = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3))
        row = []
        for x in range(n):
            if not in_round_rect(x + 0.5, y + 0.5, n, n, radius):
                row.append((0, 0, 0, 0))
            elif in_triangle(x + 0.5, y + 0.5, ax, ay, bx, by, cx, cy):
                row.append((255, 255, 255, 255))
            else:
                row.append((bg[0], bg[1], bg[2], 255))
        px.append(row)

    # 箱式缩小
    out = []
    for y in range(size):
        row = []
        for x in range(size):
            r = g = b = a = 0
            for dy in range(SS):
                for dx in range(SS):
                    p = px[y * SS + dy][x * SS + dx]
                    r += p[0] * p[3]
                    g += p[1] * p[3]
                    b += p[2] * p[3]
                    a += p[3]
            if a == 0:
                row.append((0, 0, 0, 0))
            else:
                row.append((r // a, g // a, b // a, a // (SS * SS)))
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


if __name__ == "__main__":
    main()
