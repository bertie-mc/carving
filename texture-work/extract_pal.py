import zipfile, os
jar = r"C:/Users/berlord/AppData/Roaming/PrismLauncher/instances/full test/.minecraft/mods/Slag-n-Embers-1.21.1-1.1a.jar"
z = zipfile.ZipFile(jar)
dest = "slag-ref/palettes"
os.makedirs(dest, exist_ok=True)
for m in ["base_palette","wooden","stone","flint","bone"]:
    with open(os.path.join(dest, m+".png"),"wb") as f:
        f.write(z.read(f"assets/slag/textures/color_palettes/{m}.png"))
print("extracted palettes:", os.listdir(dest))
