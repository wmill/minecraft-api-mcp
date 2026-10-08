load("../lib/openings.star", "SingleDoor")
load("../lib/fixtures.star", "Bed", "Chest", "BookshelfWall", "Sign")

def build():
    vox = {}
    extras = []
    def put(x, y, z, material, states={}):
        vox[(x, y, z)] = block("minecraft:" + material, states)
    def box(x1, y1, z1, x2, y2, z2, material, states={}):
        for x in range(x1, x2):
            for y in range(y1, y2):
                for z in range(z1, z2):
                    put(x, y, z, material, states)
    def lamp(x, z):
        box(x, 10, z, x+1, 14, z+1, "polished_deepslate_wall")
        put(x, 14, z, "sea_lantern")
        put(x, 15, z, "amethyst_block")
    # Stepped stone island, inset corners, luminous promenade.
    for x in range(63):
        for z in range(63):
            if (x < 3 or x > 59) and (z < 3 or z > 59):
                continue
            box(x, 0, z, x+1, 9, z+1, "deepslate_bricks")
            mat = "smooth_quartz" if x % 8 == 0 or z % 8 == 0 else "polished_deepslate"
            if (28 <= x and x <= 34) or (30 <= z and z <= 34):
                mat = "calcite" if (x+z)%4 else "amethyst_block"
            put(x, 9, z, mat)
            if x in [0,62] or z in [0,62]:
                if not (28 <= x and x <= 34 and z == 62):
                    put(x, 10, z, "polished_blackstone_brick_wall")
            if (x in [2,60] and z%5 == 0) or (z in [2,60] and x%5 == 0):
                put(x, 9, z, "sea_lantern")
    # Six colorful, furnished guild houses with deep eaves and glowing dormers.
    labels = ["Moonlit Library", "Alchemist's Rest", "Starlight Inn", "Crystal Atelier", "Astral Cartography", "Enchanter's House"]
    homes = [[9,11],[42,11],[9,28],[42,28],[9,45],[42,45]]
    for n in range(len(homes)):
        x,z = homes[n]
        roof = "warped_planks" if n%2 else "purpur_block"
        box(x,10,z,x+11,16,z+11,"calcite")
        box(x+1,10,z+1,x+10,15,z+10,"air")
        box(x,10,z,x+11,11,z+11,"dark_oak_planks")
        box(x+1,11,z+1,x+10,15,z+10,"air")
        for xx in [x,x+10]:
            for zz in [z,z+10]:
                box(xx,10,zz,xx+1,16,zz+1,"stripped_dark_oak_log")
        for xx in [x+2,x+7]:
            box(xx,12,z,xx+2,14,z+1,"cyan_stained_glass")
            box(xx,12,z+10,xx+2,14,z+11,"purple_stained_glass")
        for zz in [z+3,z+7]:
            box(x,12,zz,x+1,14,zz+1,"cyan_stained_glass")
            box(x+10,12,zz,x+11,14,zz+1,"cyan_stained_glass")
        for level in range(7):
            box(x-1+level,16+level,z-1,x+12-level,17+level,z+12,roof)
        box(x+5,23,z+1,x+6,24,z+10,"sea_lantern")
        for zz in [z-1,z+11]:
            put(x+5,24,zz,"amethyst_block")
        # Self-carving entry and compact interior furnishings.
        put(x+5,10,z+11,"quartz_stairs",{"facing":"north"})
        extras.append(transform([x+5,11,z+10],0,[1,2,1],SingleDoor("minecraft:dark_oak_door")))
        extras.append(transform([x+2,11,z+2],0,[1,1,2],Bed("minecraft:purple_bed")))
        extras.append(transform([x+8,11,z+2],0,[1,1,1],Chest(items=[{"id":"minecraft:amethyst_shard","count":16}])))
        extras.append(transform([x+4,11,z+1],0,[3,2,1],BookshelfWall(3,2)))
        put(x+5,14,z+5,"sea_lantern")
        box(x+4,11,z+5,x+7,12,z+8,"purple_carpet")
        box(x+4,9,z+11,x+7,10,min(z+14,62),"chiseled_quartz_block")
        extras.append(transform([x+8,11,z+9],0,[1,1,1],Sign(lines=[labels[n],"Moonspire Guild", "Visitors welcome"],color="purple",glowing=True)))
    # Four circular guardian towers, airy interiors and tapered turquoise spires.
    for cx,cz in [[4,4],[58,4],[4,58],[58,58]]:
        for y in range(10,26):
            for dx in range(-3,4):
                for dz in range(-3,4):
                    d=dx*dx+dz*dz
                    if d<=10:
                        mat="smooth_quartz" if d>=5 else "air"
                        if y in [10,19,25]:
                            mat="purpur_block"
                        elif y%5 in [3,4] and ((dx==0 and abs(dz)==3) or (dz==0 and abs(dx)==3)):
                            mat="sea_lantern"
                        put(cx+dx,y,cz+dz,mat)
        for layer in range(9):
            r=4-layer//2
            for dx in range(-r,r+1):
                for dz in range(-r,r+1):
                    if dx*dx+dz*dz<=r*r+1:
                        put(cx+dx,26+layer,cz+dz,"warped_planks" if layer%2==0 else "oxidized_copper")
        put(cx,35,cz,"sea_lantern")
        put(cx,36,cz,"amethyst_block")
        box(cx,11,cz+2,cx+1,14,cz+4,"air")
    # Northern arcane observatory: hollow octagonal tower with luminous bands.
    cx,cz=31,18
    for y in range(10,40):
        for dx in range(-6,7):
            for dz in range(-6,7):
                d=dx*dx+dz*dz
                if d<=40:
                    mat="air"
                    if d>=25:
                        mat="quartz_bricks"
                        if y in [10,20,30,39]:
                            mat="purpur_block"
                        elif y%10 in [4,5,6,7] and (abs(dx)<=1 or abs(dz)<=1):
                            mat="light_blue_stained_glass"
                    if y==10:
                        mat="chiseled_quartz_block"
                    put(cx+dx,y,cz+dz,mat)
    box(30,11,23,33,15,25,"air")
    put(31,11,18,"enchanting_table")
    for xx in [27,34]:
        box(xx,11,15,xx+1,13,21,"bookshelf")
    for y in [19,29,38]:
        for dx in range(-7,8):
            for dz in range(-7,8):
                d=dx*dx+dz*dz
                if 35<=d and d<=53:
                    put(cx+dx,y,cz+dz,"sea_lantern" if (dx+dz)%3==0 else "purpur_block")
    for layer in range(14):
        r=7-layer//2
        for dx in range(-r,r+1):
            for dz in range(-r,r+1):
                if dx*dx+dz*dz<=r*r:
                    put(cx+dx,40+layer,cz+dz,"purpur_block" if layer%2 else "amethyst_block")
    box(31,54,18,32,57,19,"sea_lantern")
    put(31,57,18,"end_rod")
    # Crystal fountain in the southern plaza, clear walking space all around.
    for dx in range(-7,8):
        for dz in range(-7,8):
            d=dx*dx+dz*dz
            if d<=49:
                put(31+dx,9,45+dz,"sea_lantern" if (dx+dz)%3==0 else "prismarine_bricks")
                put(31+dx,10,45+dz,"smooth_quartz" if d>=36 else "water")
    box(30,10,44,33,13,47,"amethyst_block")
    for y in range(14,24):
        r=min(y-14,23-y)//2+1
        for dx in range(-r,r+1):
            for dz in range(-r,r+1):
                if abs(dx)+abs(dz)<=r:
                    put(31+dx,y,45+dz,"sea_lantern" if dx==0 and dz==0 else "amethyst_block")
    # Suspended crystal satellites.
    for xx,zz in [[24,41],[38,49],[24,49],[38,41]]:
        put(xx,18,zz,"end_rod")
        put(xx,19,zz,"sea_lantern")
        put(xx,20,zz,"amethyst_block")
        put(xx,21,zz,"end_rod")
    # Flower gardens and glowing trees between houses and the central boulevard.
    for xx in [23,39]:
        for zz in [10,29,55]:
            box(xx-1,9,zz-1,xx+2,10,zz+2,"moss_block")
            box(xx,10,zz,xx+1,14,zz+1,"cherry_log")
            for dx in range(-2,3):
                for dz in range(-2,3):
                    if abs(dx)+abs(dz)<=3:
                        put(xx+dx,14,zz+dz,"cherry_leaves",{"persistent":"true"})
                        if abs(dx)+abs(dz)<=2:
                            put(xx+dx,15,zz+dz,"cherry_leaves",{"persistent":"true"})
            put(xx,14,zz,"sea_lantern")
        for zz in [17,23,38,49]:
            box(xx-1,9,zz-1,xx+2,10,zz+2,"moss_block")
            for dx,dz in [[-1,0],[1,0],[0,-1],[0,1]]:
                put(xx+dx,10,zz+dz,"allium")
    for xx in [26,36]:
        for zz in [6,28,36,56]:
            lamp(xx,zz)
    # Grand south approach drops from walking Y=72 down to Y=68.
    for step in range(5):
        zz=62+step
        box(28,0,zz,35,9-step,zz+1,"deepslate_bricks")
        box(28,9-step,zz,35,10-step,zz+1,"quartz_stairs",{"facing":"north"})
    extras.append(transform([36,10,59],0,[1,1,1],Sign(lines=["MOONSPIRE","City of Starlight","Built for","cheesedanish"],color="cyan",glowing=True)))
    body=[place_block(list(pos),mat) for pos,mat in vox.items()]
    return component(name="MoonspireMagicalCity",props={},min_size=[63,59,67],metadata={"ground_level":10},body=group(body+extras))
