import os
from PIL import Image, ImageDraw
T="../src/main/resources/assets/berlords_carving/textures/item"
mats=["wood","stone","flint","bone"]; tools=["pickaxe","axe","shovel","hoe","sword"]
SC=8; pad=6; lab=64; cell=16*SC
W=lab+len(tools)*(cell+pad)+pad; H=pad+len(mats)*(cell+pad)
img=Image.new("RGBA",(W,H),(140,140,140,255)); d=ImageDraw.Draw(img)
for ri,m in enumerate(mats):
    y0=pad+ri*(cell+pad); d.text((4,y0+cell//2-6),m,fill=(0,0,0,255))
    for ci,t in enumerate(tools):
        im=Image.open(os.path.join(T,f"{m}_{t}_head.png")).convert("RGBA").resize((cell,cell),Image.NEAREST)
        img.alpha_composite(im,(lab+ci*(cell+pad),y0))
os.makedirs("out",exist_ok=True); img.convert("RGB").save("out/baked20.png"); print("saved out/baked20.png",img.size)
