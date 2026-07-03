import zipfile, io
from PIL import Image
jar = r"C:/Users/berlord/AppData/Roaming/PrismLauncher/instances/full test/.minecraft/mods/Slag-n-Embers-1.21.1-1.1a.jar"
z = zipfile.ZipFile(jar)
def show(name):
    data = z.read(name)
    im = Image.open(io.BytesIO(data)).convert("RGBA")
    px = im.load()
    print(f"\n{name}  size={im.size}")
    for y in range(im.height):
        row=[]
        for x in range(im.width):
            r,g,b,a=px[x,y]
            row.append(f"({r:3d},{g:3d},{b:3d},{a:3d})")
        print("  "+" ".join(row))
for n in ["assets/slag/textures/color_palettes/base_palette.png",
          "assets/slag/textures/color_palettes/wooden.png",
          "assets/slag/textures/color_palettes/stone.png",
          "assets/slag/textures/color_palettes/flint.png",
          "assets/slag/textures/color_palettes/bone.png"]:
    show(n)
# distinct greys in a template
im = Image.open(io.BytesIO(z.read("assets/slag/textures/item/dynamic_parts/base/pickaxe_head.png"))).convert("RGBA")
px=im.load(); greys=set()
for y in range(im.height):
    for x in range(im.width):
        r,g,b,a=px[x,y]
        if a>16: greys.add((r,g,b,a))
print("\ntemplate base/pickaxe distinct opaque pixels:", sorted(greys))
