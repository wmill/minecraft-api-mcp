# Ziggy: seated brown toy poodle, front faces local +Z.
def fur(x,y,z,shade=0):
    n=((x//3)*17+(y//3)*31+(z//3)*13)%19
    if shade==1:
        return "brown_terracotta" if n<13 else "stripped_spruce_wood"
    if shade==2:
        return "brown_wool" if n<15 else "brown_terracotta"
    return "brown_wool" if n<11 else ("brown_terracotta" if n<17 else "stripped_spruce_wood")

def ell(v,cx,cy,cz,rx,ry,rz,material="",shade=0):
    for x in range(max(0,cx-rx),min(49,cx+rx+1)):
        for y in range(max(0,cy-ry),min(68,cy+ry+1)):
            for z in range(max(0,cz-rz),min(45,cz+rz+1)):
                d=(x-cx)*(x-cx)/(rx*rx)+(y-cy)*(y-cy)/(ry*ry)+(z-cz)*(z-cz)/(rz*rz)
                if d<=1:
                    v[(x,y,z)]=material if material else fur(x,y,z,shade)

def curly(v,cx,cy,cz,rx,ry,rz,shade=0):
    ell(v,cx,cy,cz,rx,ry,rz,shade=shade)
    for x in range(cx-rx,cx+rx+1,3):
        for y in range(cy-ry,cy+ry+1,3):
            for z in range(cz-rz,cz+rz+1,3):
                d=(x-cx)*(x-cx)/(rx*rx)+(y-cy)*(y-cy)/(ry*ry)+(z-cz)*(z-cz)/(rz*rz)
                if d>=0.80 and d<=1.08:
                    ell(v,x,y,z,2,2,2,shade=shade)

def make_voxels():
    v={}
    # Seated rump and shoulders, with a gently narrower neck.
    curly(v,24,21,17,12,11,10)
    curly(v,24,31,20,10,15,9)
    curly(v,15,17,19,7,8,8,2)
    curly(v,33,17,19,7,8,8,2)
    # Two distinct slender front legs, ending in broad furry paws.
    curly(v,17,19,29,4,13,4)
    curly(v,31,19,29,4,13,4)
    curly(v,16,8,32,5,4,6,1)
    curly(v,32,8,32,5,4,6,1)
    curly(v,24,40,22,8,9,8)
    # Round cranium, hanging ears, and fluffy topknot.
    curly(v,24,51,24,12,11,10)
    curly(v,9,48,23,5,10,6,2)
    curly(v,39,48,23,5,10,6,2)
    for x,y,z,r in [(16,59,25,4),(23,61,23,4),(30,60,24,4),(20,58,31,3),(28,59,30,3)]:
        curly(v,x,y,z,r,3,r,1)
    # Lower jaw and paired rounded muzzle cheeks.
    curly(v,24,43,31,7,5,7,2)
    curly(v,20,47,34,5,5,6,1)
    curly(v,28,47,34,5,5,6,1)
    # Deep, glossy eyes; the white catchlights are deliberately tiny.
    for x in [18,30]:
        ell(v,x,53,33,3,3,2,"brown_concrete")
        ell(v,x,53,36,2,2,1,"black_concrete")
        v[(x,52,37)]="gray_concrete"
        v[(x-1,54,37)]="white_concrete"
    # Nose projects beyond the muzzle, with two dark nostrils.
    ell(v,24,49,40,3,2,2,"brown_concrete")
    v[(22,49,42)]="black_concrete"
    v[(26,49,42)]="black_concrete"
    v[(24,50,42)]="gray_terracotta"
    # Short central philtrum and gentle mouth line.
    for y in [46,47]:
        v[(24,y,40)]="brown_concrete"
    for x in range(21,28):
        v[(x,44 if x in [21,27] else 43,39)]="brown_concrete"
    return v

def build():
    v=make_voxels()
    a=[place_block([p[0],p[1],p[2]],block("minecraft:"+m)) for p,m in v.items()]
    dog=component(name="ZiggySculpture",props={},min_size=[49,68,45],body=group(a))
    return component(name="ZiggyFacingWest",props={},min_size=[45,68,49],body=transform([0,0,0],90,[49,68,45],dog))
