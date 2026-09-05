# Problem 3
from PIL import Image

def convert(pixel):
    if pixel == "(237, 28,36)": return "R"
    elif pixel == "(0,0,0)": return "B"
    elif pixel == "(255,242,0)": return "Y"
    else: return pixel

image = Image.open("").convert("RGBA")
output_text_file = open("awesome_picture.txt", "w")

for y in range(image.height):
    for x in range(image.width):
        r, g, b, _ = image.getpixel((x, y))
        pixel = f"({convert(r)}, {convert(g)}, {convert(b)})"
        pixel = convert(pixel)
        output_text_file.write(pixel)
        output_text_file.write(" ")
    output_text_file.write("\n")
output_text_file.close()
