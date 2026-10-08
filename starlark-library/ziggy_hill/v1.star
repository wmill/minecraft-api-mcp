def build():
    a=[]
    for x in range(45):
        for z in range(49):
            dx=x-22
            dz=z-24
            d=dx*dx/(22*22)+dz*dz/(24*24)
            if d<=1:
                a.append(fill_region([x,0,z],[x+1,19,z+1],block("minecraft:stone")))
                a.append(fill_region([x,19,z],[x+1,21,z+1],block("minecraft:deepslate_tiles")))
                a.append(carve_region([x,24,z],[x+1,35,z+1]))
                if d<=0.91:
                    a.append(fill_region([x,21,z],[x+1,23,z+1],block("minecraft:polished_blackstone")))
                if d<=0.82:
                    a.append(place_block([x,23,z],block("minecraft:gold_block" if d>0.73 else "minecraft:smooth_sandstone")))
    # West approach climbs to the oval's top in one-block steps.
    for x in range(5):
        h=19+x
        a.append(carve_region([x,h,21],[x+1,35,28]))
        a.append(fill_region([x,h,21],[x+1,h+1,28],block("minecraft:polished_blackstone_brick_stairs",{"facing":"east"}),phase="fixture"))
    return component(name="ZiggyHillPlinth",props={},min_size=[45,35,49],body=group(a))
