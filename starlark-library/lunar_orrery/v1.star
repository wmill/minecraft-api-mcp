def build():
    a=[]
    for x in range(29):
        for z in range(29):
            dx=x-14
            dz=z-14
            d=dx*dx+dz*dz
            if d>=156 and d<=182:
                y=7+dx//3
                a.append(place_block([x,y,z],block("minecraft:sea_lantern" if (x+z)%7==0 else "minecraft:gold_block")))
    for cx,cz in [(14,1),(27,14),(14,27),(1,14)]:
        base=10+(cx-14)//3
        for y in range(5):
            r=min(y,4-y)
            for dx in range(-r,r+1):
                for dz in range(-r,r+1):
                    if abs(dx)+abs(dz)<=r and cx+dx>=0 and cx+dx<29 and cz+dz>=0 and cz+dz<29:
                        a.append(place_block([cx+dx,base+y,cz+dz],block("minecraft:amethyst_block" if dx==0 and dz==0 else "minecraft:cyan_stained_glass")))
    return component(name="LunarOrrery",props={},min_size=[29,20,29],body=group(a))
