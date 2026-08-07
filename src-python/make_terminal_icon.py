# -*- coding: utf-8 -*-
"""生成 X86 取餐终端图标 terminal_icon.png 与 terminal_icon.ico(纯 Python + PIL,无需 PowerShell)。
图标概念:品牌绿色渐变圆角底 + 白色餐碗 + 热气蒸腾(食堂取餐终端,区别于读卡助手的 RFID 图标)。
"""
import math
import os
from PIL import Image, ImageDraw

S = 256
IMG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'terminal_icon.png')
ICO_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'terminal_icon.ico')


def draw_steam(d, base_x, base_y, scale=1.0, alpha=230, width=6):
    """在 (base_x, base_y) 位置画一条向上的波浪蒸汽线。"""
    pts = []
    steps = 24
    for i in range(steps):
        t = i / (steps - 1)
        y = base_y - t * 46 * scale
        x = base_x + math.sin(t * math.pi * 2.2) * 7 * scale
        pts.append((x, y))
    d.line(pts, fill=(255, 255, 255, alpha), width=width, joint='curve')


def main():
    # 1) 橙色渐变圆角底(与品牌一致)
    bg = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bg)
    top = (251, 146, 60, 255)      # #fb923c
    bottom = (234, 88, 12, 255)    # #ea580c
    for y in range(S):
        t = y / S
        r = int(top[0] + (bottom[0] - top[0]) * t)
        g = int(top[1] + (bottom[1] - top[1]) * t)
        b = int(top[2] + (bottom[2] - top[2]) * t)
        bd.line([(0, y), (S, y)], fill=(r, g, b, 255))
    mask = Image.new('L', (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S, S], radius=58, fill=255)
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    img.paste(bg, (0, 0), mask)
    d = ImageDraw.Draw(img)

    # 2) 向上蒸腾的热气(3 条波浪线,象征刚出锅的热餐)
    draw_steam(d, 104, 130, scale=1.0, alpha=200, width=6)
    draw_steam(d, 128, 124, scale=1.15, alpha=235, width=7)
    draw_steam(d, 152, 130, scale=1.0, alpha=200, width=6)

    # 3) 白色餐碗(椭圆碗口 + 圆角碗身)
    rim = [52, 140, 204, 186]
    d.ellipse(rim, fill=(255, 255, 255, 255))
    body = [68, 152, 188, 216]
    d.rounded_rectangle(body, radius=26, fill=(255, 255, 255, 255))

    # 4) 碗口内的餐食(橙色汤/饭,营造"热餐"感)
    d.ellipse([72, 154, 184, 184], fill=(255, 237, 213, 255))
    # 点缀两粒米(白色反光)
    d.ellipse([100, 162, 112, 174], fill=(255, 255, 255, 235))
    d.ellipse([140, 160, 152, 172], fill=(255, 255, 255, 235))

    # 5) 碗身高光(左上到右下的柔和反光,增加立体感)
    d.ellipse([78, 178, 150, 216], fill=(255, 245, 235, 90))

    # 抗锯齿缩放
    img = img.resize((S, S), Image.LANCZOS)
    img.save(IMG_PATH)
    img.save(ICO_PATH, sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
    print('terminal_icon.png ->', IMG_PATH)
    print('terminal_icon.ico ->', ICO_PATH)


if __name__ == '__main__':
    main()