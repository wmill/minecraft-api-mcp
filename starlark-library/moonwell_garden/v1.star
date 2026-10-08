load("../lib/shapes.star", "disc_cells", "ring_cells", "fill_cells")
load("../lib/fixtures.star", "Bench", "Sign")
load("../lib/outdoor.star", "FlowerBed")

def RunePylon(height=8, stone="minecraft:polished_deepslate", crystal="minecraft:amethyst_block"):
    ops = [fill_region([0,0,0],[3,1,3],block(stone)),
           fill_region([1,1,1],[2,height-3,2],block("minecraft:chiseled_quartz_block")),
           fill_region([0,height-3,0],[3,height-2,3],block("minecraft:waxed_oxidized_cut_copper")),
           place_block([1,height-2,1],block("minecraft:sea_lantern")),
           place_block([1,height-1,1],block(crystal)),
           place_block([1,height,1],block("minecraft:amethyst_cluster",{"facing":"up"}))]
    for y in range(2,height-3,2):
        ops.append(place_block([1,y,2],block("minecraft:amethyst_block")))
    return component(name="RunePylon",props={"height":height},min_size=[3,height+1,3],body=group(ops))

def Moonwell(rim="minecraft:quartz_bricks", crystal="minecraft:amethyst_block"):
    outer = ring_cells(4)
    inner = [[c[0]+1,c[1]+1] for c in disc_cells(3)]
    ops = [group(fill_cells(disc_cells(4),0,1,block("minecraft:sea_lantern"))),
           group(fill_cells(outer,1,3,block(rim))),
           group(fill_cells(inner,1,2,block("minecraft:water"))),
           place_block([4,4,4],block(crystal)),
           fill_region([3,5,3],[6,6,6],block(crystal)),
           place_block([4,6,4],block("minecraft:sea_lantern")),
           place_block([4,7,4],block("minecraft:amethyst_cluster",{"facing":"up"}))]
    for p in [[4,0],[0,4],[8,4],[4,8]]:
        ops.append(place_block([p[0],3,p[1]],block("minecraft:waxed_oxidized_cut_copper")))
    return component(name="Moonwell",props={"rim":rim,"crystal":crystal},min_size=[9,8,9],body=group(ops))

def MoonwellGarden():
    ops = [fill_region([0,0,0],[23,4,19],block("minecraft:deepslate_bricks"))]
    for x in range(23):
        for z in range(19):
            material = "minecraft:smooth_quartz"
            if x in [0,22] or z in [0,18]:
                material = "minecraft:polished_deepslate"
            elif (x + z) % 5 == 0:
                material = "minecraft:calcite"
            if x in [10,11,12] or z in [8,9,10]:
                material = "minecraft:waxed_oxidized_cut_copper" if (x+z)%4 == 0 else "minecraft:quartz_bricks"
            ops.append(place_block([x,4,z],block(material)))
    ops.append(at([7,5,4],Moonwell()))
    for p in [[1,1],[19,1],[1,14],[19,14]]:
        ops.append(at([p[0],5,p[1]],RunePylon()))
    for x in [3,17]:
        ops.append(at([x,5,6],FlowerBed(3,6,flower_a="minecraft:allium",flower_b="minecraft:cornflower",border="minecraft:polished_deepslate")))
    for x in [6,14]:
        ops.append(at([x,5,15],Bench(3,stair="minecraft:quartz_stairs"),rotation=180))
    ops.append(at([15,5,17],Sign(lines=["THE MOONWELL","Wish beneath","the violet star",""],material="minecraft:warped_sign",color="white",glowing=True)))
    for x in range(4,19):
        ops.append(place_block([x,5,0],block("minecraft:quartz_slab",{"type":"bottom"})))
    return component(name="MoonwellGarden",props={},min_size=[23,14,19],metadata={"ground_level":5},body=group(ops))

def build():
    return MoonwellGarden()
