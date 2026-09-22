#!/usr/bin/env python3
"""Generate demo cheque images for Rajesh Kumar (unit A-103, monthly lease).

One cheque per remaining 2026 installment (Jun-Dec), AED 10,250.00 each
(10,000 rent + 250 parking), drawn on Dubai Islamic Bank. Output goes to
~/Desktop/rentaxis-demo-cheques/rajesh for the web bulk-upload demo; use
adb push to stage them on the phone for the scan-wizard demo.
"""
import os
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.expanduser("~/Desktop/rentaxis-demo-cheques/rajesh")
os.makedirs(OUT, exist_ok=True)

ARIAL = "/System/Library/Fonts/Supplemental/Arial.ttf"
ARIAL_B = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
COURIER_B = "/System/Library/Fonts/Supplemental/Courier New Bold.ttf"


def f(p, s):
    return ImageFont.truetype(p, s)


PAYER = "Rajesh Kumar"
BANK = "Dubai Islamic Bank"
ACCOUNT = "3071 5520 8841 22"
AMOUNT = "10,250.00"
WORDS = "AED Ten Thousand Two Hundred Fifty only"

# (number, dd, mm, yyyy, memo)
CHEQUES = [(f"500{m:02d}", "01", f"{m:02d}", "2026",
            f"Rent + Parking — {m:02d}/2026") for m in range(6, 13)]

W, H = 1400, 620
BG = (235, 243, 240)
INK = (22, 40, 60)
ACCENT = (0, 105, 92)


def draw(num, dd, mm, yyyy, memo, idx):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, 90], fill=ACCENT)
    d.text((40, 22), BANK, font=f(ARIAL_B, 40), fill=(255, 255, 255))
    d.text((40, 64), "Al Barsha Branch, Dubai, UAE", font=f(ARIAL, 18),
           fill=(220, 235, 230))
    d.text((W - 360, 30), f"Cheque No: {num}", font=f(COURIER_B, 26),
           fill=(255, 255, 255))

    # date boxes
    d.text((W - 420, 120), "DATE", font=f(ARIAL, 16), fill=INK)
    date_str = f"{dd}{mm}{yyyy}"
    for i, ch in enumerate(date_str):
        x = W - 360 + i * 38
        d.rectangle([x, 110, x + 32, 152], outline=INK, width=2)
        d.text((x + 8, 117), ch, font=f(COURIER_B, 26), fill=INK)

    d.text((40, 180), "PAY TO THE ORDER OF", font=f(ARIAL, 16), fill=INK)
    d.text((40, 210), "Sample Properties LLC", font=f(ARIAL_B, 34), fill=INK)
    d.line([(40, 252), (W - 380, 252)], fill=INK, width=2)

    d.text((40, 280), "THE SUM OF", font=f(ARIAL, 16), fill=INK)
    d.text((40, 308), WORDS, font=f(ARIAL, 28), fill=INK)
    d.line([(40, 348), (W - 60, 348)], fill=INK, width=2)

    d.rectangle([W - 350, 270, W - 60, 340], outline=INK, width=3)
    d.text((W - 335, 288), f"AED {AMOUNT}", font=f(COURIER_B, 34), fill=INK)

    d.text((40, 400), f"MEMO  {memo}", font=f(ARIAL, 20), fill=INK)
    d.text((40, 440), f"A/C  {ACCOUNT}", font=f(COURIER_B, 22), fill=INK)

    d.line([(W - 420, 480), (W - 60, 480)], fill=INK, width=2)
    d.text((W - 380, 490), PAYER, font=f(ARIAL, 22), fill=INK)
    d.text((W - 420, 452), "Rajesh Kumar", font=f(ARIAL, 30), fill=(60, 70, 120))

    d.text((40, 545), f"⑆ 023801 ⑆ {ACCOUNT.replace(' ', '')} ⑈ {num} ⑈",
           font=f(COURIER_B, 28), fill=INK)

    p = os.path.join(OUT, f"rajesh-cheque-{yyyy}-{mm}.jpg")
    img.save(p, "JPEG", quality=92)
    return p


paths = [draw(*c, i) for i, c in enumerate(CHEQUES)]
print("\n".join(paths))
