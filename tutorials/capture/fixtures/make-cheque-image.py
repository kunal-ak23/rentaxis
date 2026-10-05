#!/usr/bin/env python3
"""A fictional cheque photo for tutorial 15's scan (no real bank, payer or account number).

    python3 make-cheque-image.py <cheque-no> <dd/mm/yyyy> <amount> <amount in words> <payee> <payer> <out.png>
"""
import sys
from PIL import Image, ImageDraw, ImageFont

num, date, amt, words, payee, payer, out = sys.argv[1:8]
W, H = 1600, 720
im = Image.new('RGB', (W, H), (240, 236, 222))
d = ImageDraw.Draw(im)


def f(size, bold=False):
    path = '/System/Library/Fonts/Supplemental/Arial Bold.ttf' if bold else '/System/Library/Fonts/Supplemental/Arial.ttf'
    try:
        return ImageFont.truetype(path, size)
    except OSError:
        return ImageFont.load_default()


for y in range(0, H, 6):  # faint security pattern
    d.line([(0, y), (W, y)], fill=(234, 229, 212))
d.rectangle([20, 20, W - 20, H - 20], outline=(70, 90, 110), width=4)
d.text((60, 48), 'GULF CRESCENT BANK', font=f(46, True), fill=(20, 60, 100))
d.text((60, 104), 'Fictional bank for demonstration only', font=f(22), fill=(90, 90, 90))
d.text((1120, 58), 'Date', font=f(28), fill=(70, 70, 70))
d.text((1205, 52), date, font=f(40), fill=(10, 10, 10))
d.text((60, 200), 'Pay', font=f(30), fill=(70, 70, 70))
d.text((140, 190), payee, font=f(44), fill=(10, 10, 10))
d.line([140, 246, 1100, 246], fill=(120, 120, 120), width=2)
d.text((60, 292), 'Dirhams', font=f(30), fill=(70, 70, 70))
d.text((190, 284), words, font=f(36), fill=(10, 10, 10))
d.line([190, 332, 1100, 332], fill=(120, 120, 120), width=2)
d.rectangle([1150, 270, 1550, 344], outline=(60, 60, 60), width=3)
d.text((1170, 284), 'AED ' + amt, font=f(40, True), fill=(10, 10, 10))
d.text((60, 420), payer, font=f(32), fill=(10, 10, 10))
d.text((1130, 512), payer, font=f(42), fill=(30, 30, 120))
d.line([1090, 570, 1550, 570], fill=(80, 80, 80), width=2)
d.text((1180, 580), 'Signature', font=f(22), fill=(90, 90, 90))
d.text((60, 625), f'Cheque No. {num}', font=f(36, True), fill=(30, 30, 30))
im.save(out)
print(out)
