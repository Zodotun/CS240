# Problem 4

from PIL import Image

def convert(code):
    if code == "(237, 28,36)": return (237, 28,36)
    elif code == "(0,0,0)": return (0,0,0)
    elif code == "(255,242,0)": return (255,242,0)
    else: return code

# image = Image.open("").convert("RGBA")
input_text_file = open("awesome_picture.txt", "r")
lines = input_text_file.readlines()
h, w = len(lines), lines[0].count(" ")
image = Image.new(mode= "RGB", size=(h,w), color=(0,0,0))

for y in range(h):
    pixels = lines[y].split(" ")
    print(pixels)
    for x in range(w):
        pixel = pixels[x]
        image.putpixel((x, y), convert(pixel))

image.save("smileytafdsioijodf.png")

