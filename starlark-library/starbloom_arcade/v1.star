
load("../lib/fixtures.star", "Bench")

def _box(a, b, material):
    return fill_region(a, b, block(material), phase="fixture")

def _put(p, material, states={}):
    return place_block(p, block(material, states), phase="fixture")

def StarKiosk(canopy="minecraft:oxidized_cut_copper", crystal="minecraft:amethyst_block"):
    # Open south-facing 7x11x7 kiosk. Sign sits on the back counter, away from entry.
    ops = []
    for x in [0,6]:
        for z in [0,6]:
            ops.append(_box([x,0,z],[x+1,5,z+1],"minecraft:polished_deepslate"))
            ops.append(_put([x,5,z],"minecraft:sea_lantern"))
    for y,lo,hi in [[5,1,6],[6,0,7],[7,1,6],[8,2,5]]:
        ops.append(_box([lo,y,lo],[hi,y+1,hi],canopy))
    ops.extend([
        _put([3,9,3],"minecraft:sea_lantern"),
        _put([3,10,3],crystal),
        _box([1,0,0],[6,1,1],"minecraft:chiseled_bookshelf"),
        _box([1,1,0],[6,2,1],"minecraft:dark_oak_planks"),
        _put([1,2,0],"minecraft:brewing_stand"),
        _put([5,2,0],"minecraft:flower_pot"),
        _put([2,0,1],"minecraft:barrel",{"facing":"up"}),
        _put([4,0,1],"minecraft:crafting_table"),
        place_block([3,2,0], block("minecraft:dark_oak_sign", {"rotation":"0"}, nbt=sign_nbt(["STAR BLOOM","Potions & Charms"],color="cyan",glowing=True)),phase="fixture"),
    ])
    return component(name="StarKiosk",props={"canopy":canopy,"crystal":crystal},min_size=[7,11,7],body=group(ops))

def RuneArch(stone="minecraft:polished_deepslate"):
    ops=[]
    for x in [0,5]:
        ops.append(_box([x,0,0],[x+2,5,3],stone))
        ops.append(_put([x,5,1],"minecraft:sea_lantern"))
        ops.append(_put([x+1,5,1],"minecraft:amethyst_block"))
    ops.extend([
        _box([1,6,0],[6,7,3],"minecraft:oxidized_cut_copper"),
        _box([2,5,0],[5,6,3],stone),
        _put([3,7,1],"minecraft:sea_lantern"),
    ])
    return component(name="RuneArch",props={"stone":stone},min_size=[7,8,3],body=group(ops))

def CrystalObelisk(crystal="minecraft:amethyst_block"):
    ops=[_box([0,0,0],[5,1,5],"minecraft:polished_deepslate"),
         _box([1,1,1],[4,2,4],"minecraft:chiseled_quartz_block"),
         _put([2,2,2],"minecraft:sea_lantern")]
    for y,r in [[4,0],[5,1],[6,1],[7,0]]:
        for x in range(2-r,3+r):
            for z in range(2-r,3+r):
                if abs(x-2)+abs(z-2)<=r:
                    ops.append(_put([x,y,z],crystal))
    return component(name="CrystalObelisk",props={"crystal":crystal},min_size=[5,8,5],body=group(ops))

def StarbloomArcade():
    ops=[]
    # Support piles embed in the existing hillside. Clear only the terrace above its floor.
    for x in [0,6,12,18,24]:
        for z in [2,8,14,20,24]:
            ops.append(fill_region([x,0,z],[x+1,6,z+1],block("minecraft:stone_bricks")))
    ops.append(carve_region([0,6,2],[25,31,25]))
    ops.append(carve_region([9,4,0],[16,31,2]))
    for x in range(25):
        for z in range(2,25):
            mat="minecraft:polished_diorite"
            if x in [0,24] or z in [2,24]:
                mat="minecraft:polished_deepslate"
            elif x in [11,12,13] or z in [12,13,14]:
                mat="minecraft:purpur_block" if (x+z)%3 else "minecraft:amethyst_block"
            ops.append(_put([x,6,z],mat))
    # North approach rises one block to the terrace; all seven entry cells remain unobstructed.
    ops.append(_box([9,4,0],[16,6,1],"minecraft:stone_bricks"))
    ops.append(_box([9,4,1],[16,6,2],"minecraft:stone_bricks"))
    for x in range(9,16):
        ops.append(_put([x,6,1],"minecraft:stone_brick_stairs",{"facing":"south"}))
    ops.extend([
        at([9,7,3],RuneArch(),rotation=180),
        at([1,7,9],StarKiosk(),rotation=270),
        at([17,7,9],StarKiosk(canopy="minecraft:waxed_weathered_cut_copper"),rotation=90),
        at([10,7,18],CrystalObelisk()),
        at([3,7,21],Bench(5,stair="minecraft:dark_oak_stairs")),
        at([17,7,21],Bench(5,stair="minecraft:dark_oak_stairs")),
    ])
    for x in [2,22]:
        for z in [5,18]:
            ops.extend([_put([x,7,z],"minecraft:chiseled_quartz_block"),
                        _put([x,8,z],"minecraft:sea_lantern"),
                        _put([x,9,z],"minecraft:amethyst_block")])
    for x in [4,19]:
        ops.extend([_box([x,7,23],[x+2,8,24],"minecraft:moss_block"),
                    _put([x,8,23],"minecraft:azalea"),
                    _put([x+1,8,23],"minecraft:flowering_azalea")])
    # Rear railing, side rails interrupted by the kiosks' public approach.
    for x in range(25):
        ops.append(_put([x,7,24],"minecraft:polished_deepslate_wall"))
    for x in [0,24]:
        for z in range(3,24):
            ops.append(_put([x,7,z],"minecraft:polished_deepslate_wall"))
    return component(name="StarbloomArcade",props={},min_size=[25,31,25],body=group(ops))

def build():
    return component(name="StarbloomArcadeSite",props={},min_size=[25,31,25],metadata={"ground_level":7},body=StarbloomArcade())
