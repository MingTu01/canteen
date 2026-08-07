# -*- coding: utf-8 -*-
"""生成读卡助手图标 icon.png 与 icon.ico(纯 Python + PIL,无需 PowerShell)。
图标概念:绿色圆角底 + 白色卡片 + 非接读卡曲线(RFID 感应)。
注意:此图标供「读卡助手」使用;X86 取餐终端图标见 make_terminal_icon.py(terminal_icon.png/.ico)。
"""
import os
from PIL import Image, ImageDraw

S = 256
IMG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'icon.png')
ICO_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'icon.ico')


def main():
    # 1) 橙色渐变圆角底
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

    # 2) 卡片上方的非接读卡曲线(3 条同心弧,开口向上)
    wave_cy = 78
    for r, w in ((26, 9), (44, 9), (62, 9)):
        d.arc([S / 2 - r, wave_cy - r, S / 2 + r, wave_cy + r],
              start=200, end=340, fill=(255, 255, 255, 235), width=w)

    # 3) 白色卡片
    card = [44, 104, S - 44, 204]
    d.rounded_rectangle(card, radius=18, fill=(255, 255, 255, 255))

    # 4) 卡片上的感应波纹(橙色,示意刷卡感应)
    d.rounded_rectangle([S - 92, 128, S - 64, 140], radius=6, fill=(255, 237, 213, 255))
    d.rounded_rectangle([S - 112, 128, S - 86, 140], radius=6, fill=(255, 237, 213, 200))
    d.rounded_rectangle([S - 132, 128, S - 108, 140], radius=6, fill=(255, 237, 213, 150))

    # 5) 金色芯片
    d.rounded_rectangle([66, 132, 98, 164], radius=6, fill=(245, 158, 11, 255))

    # 抗锯齿缩放
    img = img.resize((S, S), Image.LANCZOS)
    img.save(IMG_PATH)
    img.save(ICO_PATH, sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
    print('icon.png ->', IMG_PATH)
    print('icon.ico ->', ICO_PATH)


if __name__ == '__main__':
    main()