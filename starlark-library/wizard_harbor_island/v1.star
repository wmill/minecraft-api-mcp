load("../lib/dwellings.star", "GuestHouse")
load("../lib/outdoor.star", "MarketStall", "FlowerBed", "RoundTree")
load("../lib/fixtures.star", "LanternPost", "Sign", "Bench", "Chest")
load("../lib/roofs.star", "PyramidRoof")

def at(x,y,z,w,h,d,node,rot=0):
    return transform([x,y,z],rot,[w,h,d],node)

def tower():
    a=[]
    for y in range(25):
        for x in range(19):
            for z in range(19):
                dx=x-9
                dz=z-9
                r=dx*dx+dz*dz
                if r<=49:
                    if y in [0,8,16,24]:
                        mat="polished_deepslate" if y==0 else "dark_oak_planks"
                    elif r>=36:
                        mat="smooth_quartz"
                        if y in [1,7,9,15,17,23]:
                            mat="chiseled_quartz_block"
                        elif (abs(dx)<=1 or abs(dz)<=1) and y%8 in [3,4,5]:
                            mat="cyan_stained_glass"
                    else:
                        mat="air"
                    a.append(place_block([x,y,z],block("minecraft:"+mat)))
    for y in range(25,43):
        r=9-(y-25)//2
        for x in range(19):
            for z in range(19):
                d=(x-9)*(x-9)+(z-9)*(z-9)
                if d<=r*r and d>max(0,(r-2)*(r-2)):
                    mat="dark_prismarine" if y%2==0 else "amethyst_block"
                    a.append(place_block([x,y,z],block("minecraft:"+mat)))
    a.append(fill_region([9,42,9],[10,46,10],block("minecraft:sea_lantern")))
    # floating crystal above the spire
    for y in range(48,55):
        r=min(y-48,54-y)
        for x in range(9-r,10+r):
            for z in range(9-r,10+r):
                if abs(x-9)+abs(z-9)<=r:
                    a.append(place_block([x,y,z],block("minecraft:sea_lantern" if x==9 and z==9 else "minecraft:purple_stained_glass")))
    a.append(carve_region([8,1,14],[11,5,17]))
    a.append(carve_region([9,1,4],[10,25,5]))
    for y in range(1,25):
        a.append(place_block([9,y,4],block("minecraft:ladder",{"facing":"south"}),phase="fixture"))
    for y in [1,9,17]:
        for x in [6,7,11,12]:
            a.append(fill_region([x,y,5],[x+1,y+3,6],block("minecraft:bookshelf"),phase="fixture"))
        a.append(place_block([9,y,9],block("minecraft:enchanting_table"),phase="fixture"))
        for x,z in [(5,9),(13,9),(9,13)]:
            a.append(place_block([x,y,z],block("minecraft:sea_lantern"),phase="fixture"))
    return component(props={},name="MoonspireLibrary",min_size=[19,55,19],body=group(a))

def build():
    a=[]
    # Distinct inlaid pavement; one material per cell.
    for x in range(59):
        for z in range(89):
            mat="smooth_stone"
            if x in [0,1,57,58] or z in [0,1,87,88]:
                mat="polished_deepslate"
            elif abs(x-29)<=2 or abs(z-45)<=2 or x in [17,18,19,39,40,41] or z in [18,19,20,67,68,69]:
                mat="smooth_quartz" if (x+z)%6 else "sea_lantern"
            elif (x+z)%9==0:
                mat="polished_andesite"
            a.append(place_block([x,0,z],block("minecraft:"+mat)))
    # Harbor balustrade with central entrances.
    for x in range(59):
        if abs(x-29)>3:
            for z in [0,88]:
                a.append(place_block([x,1,z],block("minecraft:polished_deepslate_wall")))
    for z in range(1,88):
        if abs(z-45)>4:
            for x in [0,58]:
                a.append(place_block([x,1,z],block("minecraft:polished_deepslate_wall")))
    # Six furnished wizard homes, pastel plaster and purple/blue roofs.
    for i,p in enumerate([(4,5),(4,25),(4,59),(4,76),(45,59),(45,76)]):
        roof="minecraft:purpur_stairs" if i%2==0 else "minecraft:dark_prismarine_stairs"
        a.append(at(p[0],1,p[1],9,12,9,GuestHouse(width=9,depth=9,wall_height=6,log="minecraft:stripped_warped_stem",infill="minecraft:calcite",door="minecraft:warped_door",roof_stair=roof,roof_ridge="minecraft:amethyst_block",bed="minecraft:purple_bed")))
        # Front doorstep and branch path to the promenade.
        a.append(fill_region([p[0]+3,1,p[1]+9],[p[0]+6,2,p[1]+10],block("minecraft:quartz_slab")))
    a.append(at(20,1,36,19,55,19,tower()))
    # Northern domed star pavilion.
    for x in range(19,36):
        for z in range(3,18):
            dx=x-27
            dz=z-10
            if dx*dx+dz*dz<=49:
                a.append(place_block([x,1,z],block("minecraft:quartz_block")))
    for x,z in [(22,6),(32,6),(22,14),(32,14)]:
        a.append(fill_region([x,2,z],[x+1,10,z+1],block("minecraft:quartz_pillar")))
    for y in range(8):
        for x in range(-8,9):
            for z in range(-8,9):
                d=x*x+z*z+y*y
                if d>=49 and d<=64:
                    a.append(place_block([27+x,10+y,10+z],block("minecraft:cyan_stained_glass" if (x+z)%4 else "minecraft:sea_lantern")))
    a.append(at(25,2,9,5,1,1,Bench(5,stair="minecraft:quartz_stairs")))
    # Four potion and spell market stalls.
    for x,z,c in [(44,27,"purple"),(44,36,"cyan"),(23,60,"magenta"),(32,60,"blue")]:
        a.append(at(x,1,z,7,4,4,MarketStall(width=7,depth=4,canopy="minecraft:"+c+"_wool",accent="minecraft:white_wool",post="minecraft:warped_fence")))
        a.append(at(x+2,1,z+1,1,1,1,Chest(items=[{"id":"minecraft:amethyst_shard","count":16},{"id":"minecraft:glow_berries","count":16}])))
    for x,z in [(22,23),(31,23),(22,75),(31,75)]:
        a.append(at(x,1,z,5,2,6,FlowerBed(5,6,flower_a="minecraft:allium",flower_b="minecraft:azure_bluet",border="minecraft:quartz_block")))
    for x,z in [(4,43),(46,47)]:
        a.append(fill_region([x,1,z],[x+5,2,z+5],block("minecraft:grass_block")))
        a.append(at(x,2,z,5,9,5,RoundTree(trunk_height=8,log="minecraft:cherry_log",leaves="minecraft:cherry_leaves")))
    # Moon gate on the south promenade, walkable beneath its arch.
    for x in [23,35]:
        a.append(fill_region([x,1,84],[x+1,10,85],block("minecraft:quartz_pillar")))
        a.append(place_block([x,10,84],block("minecraft:sea_lantern")))
    a.append(fill_region([24,9,84],[35,10,85],block("minecraft:amethyst_block")))
    for x in range(25,34):
        y=10+min(x-25,33-x)
        a.append(place_block([x,y,84],block("minecraft:quartz_block")))
    for x,z in [(16,4),(40,4),(16,23),(40,23),(16,38),(40,40),(16,56),(40,56),(16,73),(40,73),(21,86),(37,86)]:
        a.append(at(x,1,z,1,5,1,LanternPost(4,post="minecraft:warped_fence",lantern="minecraft:soul_lantern")))
    a.append(at(26,1,83,1,1,1,Sign(lines=["MOONSPIRE","City of Tides","& Starlight","for cheesedanish"],color="purple",glowing=True)))
    a.append(at(28,1,55,1,1,1,Sign(lines=["LUNAR ARCHIVE","Enchant below","Climb to study","Follow the stars"],color="cyan",glowing=True)))
    return component(props={},name="MoonspireCity",min_size=[59,57,89],metadata={"ground_level":1},body=group(a))
