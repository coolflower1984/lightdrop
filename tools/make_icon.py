from PIL import Image, ImageDraw
S = 512
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
# gradient background
grad = Image.new("RGB", (S, S))
d = ImageDraw.Draw(grad)
for y in range(S):
    for_x = y / S
    r = int(0x4C + (0x2A - 0x4C) * for_x)
    g = int(0x7D + (0x55 - 0x7D) * for_x)
    b = int(0xF8 + (0xD0 - 0xF8) * for_x)
    d.line([(0, y), (S, y)], fill=(r, g, b))
# diagonal shading overlay
mask = Image.new("L", (S, S), 0)
md = ImageDraw.Draw(mask)
md.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.24), fill=255)
img.paste(grad, (0, 0), mask)

# paper plane
scale = S / 100.0
main = [(14, 50), (88, 15), (62, 87)]
fold = [(14, 50), (52, 61), (62, 87)]
d2 = ImageDraw.Draw(img)
d2.polygon([(x * scale, y * scale) for x, y in main], fill=(255, 255, 255, 255))
d2.polygon([(x * scale, y * scale) for x, y in fold], fill=(255, 255, 255, 165))

sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for name, px in sizes.items():
    img.resize((px, px), Image.LANCZOS).save(
        f"app/src/main/res/mipmap-{name}/ic_launcher.png")
print("ok")
