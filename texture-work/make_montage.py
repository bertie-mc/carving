import os
from PIL import Image, ImageDraw
D = "slag-ref"
variants = ["soft","base","metal","shiny"]
heads = ["pickaxe_head","axe_head","hoe_head","shovel_head","sword_blade"]
SC=8; pad=6; labelw=70
cellw=16*SC
W = labelw + len(heads)*(cellw+pad)+pad
H = pad + len(variants)*(cellw+pad)
img = Image.new("RGBA",(W,H),(150,150,150,255))
d = ImageDraw.Draw(img)
def avg(im):
    px=im.load(); rs=gs=bs=n=0
    for y in range(im.height):
        for x in range(im.width):
            r,g,b,a=px[x,y]
            if a>32: rs+=r;gs+=g;bs+=b;n+=1
    return (rs//n,gs//n,bs//n) if n else (0,0,0)
for ri,v in enumerate(variants):
    y0 = pad + ri*(cellw+pad)
    d.text((4,y0+cellw//2-6), v, fill=(0,0,0,255))
    sums=[]
    for ci,h in enumerate(heads):
        p=os.path.join(D,f"dyn_{v}_{h}.png")
        im=Image.open(p).convert("RGBA")
        sums.append(avg(im))
        big=im.resize((cellw,cellw),Image.NEAREST)
        x0=labelw+ci*(cellw+pad)
        img.alpha_composite(big,(x0,y0))
    a=tuple(sum(c[i] for c in sums)//len(sums) for i in range(3))
    print(f"{v:6s} avg RGB = {a}")
os.makedirs("out",exist_ok=True)
img.convert("RGB").save("out/variants_montage.png")
print("saved out/variants_montage.png", img.size)
