
def LanternWalk(levels=[8,8,8,9,9,9,9,9,9,9,9,9,9,9,9,9,10,10,10,10,10,10,10,9,9,9,9,9,9,9,9,9,9], east_exit=True):
    n=len(levels)
    if n < 1:
        fail("LanternWalk needs at least one walking level")
    for i in range(n):
        if levels[i] < 3:
            fail("walking levels must leave three blocks for foundations")
        if i > 0 and abs(levels[i]-levels[i-1]) > 1:
            fail("adjacent walking levels may differ by at most one")
    h=max(levels)+9
    ops=[]
    for z in range(n):
        y=levels[z]
        ops.append(carve_region([0,y,z],[7,h,z+1]))
        hi=7 if east_exit and z in [17,18,19,20,21] else 6
        for x in range(1,hi):
            mat="minecraft:polished_diorite"
            states={}
            if x in [1,5] and not (east_exit and x==5 and z in [17,18,19,20,21]):
                mat="minecraft:polished_deepslate"
            elif z>0 and levels[z-1]<y:
                mat="minecraft:quartz_stairs"
                states={"facing":"south"}
            elif z<n-1 and levels[z+1]<y:
                mat="minecraft:quartz_stairs"
                states={"facing":"north"}
            elif x==3:
                mat="minecraft:oxidized_cut_copper"
            ops.append(place_block([x,y-1,z],block(mat,states)))
            ops.append(fill_region([x,y-3,z],[x+1,y-1,z+1],block("minecraft:stone_bricks")))
        if z%6==0:
            for x in [1,5]:
                ops.append(fill_region([x,0,z],[x+1,y-3,z+1],block("minecraft:stone_bricks")))
    for z in [5,13,27]:
        if z>=n:
            continue
        y=levels[z]
        for x in [0,6]:
            ops.extend([
                fill_region([x,y-3,z],[x+1,y,z+1],block("minecraft:stone_bricks")),
                fill_region([x,y,z],[x+1,y+3,z+1],block("minecraft:polished_deepslate_wall"),phase="fixture"),
                place_block([x,y+3,z],block("minecraft:sea_lantern"),phase="fixture"),
                place_block([x,y+4,z],block("minecraft:amethyst_block"),phase="fixture"),
            ])
    return component(name="LanternWalk",props={"levels":levels,"east_exit":east_exit},min_size=[7,h,n],body=group(ops))

def ObservatoryApproach(length=14):
    if length < 1:
        fail("length must be positive")
    ops=[carve_region([0,10,0],[length,19,5])]
    for x in range(length):
        for z in range(5):
            material="minecraft:polished_deepslate" if z in [0,4] else ("minecraft:oxidized_cut_copper" if z==2 else "minecraft:polished_diorite")
            ops.append(fill_region([x,6,z],[x+1,9,z+1],block("minecraft:stone_bricks")))
            ops.append(place_block([x,9,z],block(material)))
    return component(name="ObservatoryApproach",props={"length":length},min_size=[length,19,5],body=group(ops))

def build(branch=False):
    if branch:
        return component(name="ObservatoryApproachSite",props={},min_size=[14,19,5],metadata={"ground_level":10},body=ObservatoryApproach())
    return component(name="LanternWalkSite",props={},min_size=[7,19,33],metadata={"ground_level":8},body=LanternWalk())
