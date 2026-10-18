load("../lib/shapes.star", "Cylinder", "Disc", "ring_cells", "fill_cells")
load("../lib/roofs.star", "ConeRoof")
load("../lib/openings.star", "SingleDoor")
def Tower(h):
    p = [Cylinder(3,h,material="minecraft:quartz_bricks"), at([-1,h, -1], ConeRoof(4,stair="minecraft:prismarine_stairs",cap="minecraft:dark_prismarine",steep=True))]
    p = [at([1,0,1],p[0]), at([0,h,0],ConeRoof(4,stair="minecraft:prismarine_stairs",cap="minecraft:dark_prismarine",steep=True))]
    p.append(at([4,0,7],SingleDoor(material="minecraft:birch_door")))
    for y in [4,10,16]:
        if y+3<h:
            for x,z in [[4,1],[1,4],[7,4]]:
                p.append(carve_region([x,y,z],[x+1,y+3,z+1]))
                p.append(fill_region([x,y,z],[x+1,y+3,z+1],block("minecraft:light_blue_stained_glass"),phase="fixture"))
    for y in range(h):
        p.append(place_block([4,y,2],block("minecraft:ladder",{"facing":"south"}),phase="fixture"))
    for y in [8,14,20]:
        if y<h:
            p.append(fill_region([3,y,3],[6,y+1,6],block("minecraft:birch_planks")))
    p.append(place_block([4,h+9,4],block("minecraft:gold_block")))
    p.append(place_block([4,h+10,4],block("minecraft:end_rod",{"facing":"up"}),phase="fixture"))
    return component(name="ElvenTower",props={},min_size=[9,h+11,9],body=group(p))
def build():
    p=[fill_region([0,0,0],[25,1,25],block("minecraft:smooth_quartz")),at([1,1,2],Tower(25)),at([15,1,2],Tower(19))]
    for x in [2,18]:
        p.append(fill_region([x,1,17],[x+5,2,22],block("minecraft:grass_block")))
        for dx,dz in [[0,0],[2,0],[4,0],[0,4],[2,4],[4,4]]:
            p.append(place_block([x+dx,2,17+dz],block("minecraft:azure_bluet"),phase="fixture"))
    p.append(at([10,1,15],Disc(2,"minecraft:quartz_bricks")))
    p.append(at([10,2,15],group(fill_cells(ring_cells(2),0,1,block("minecraft:quartz_bricks"))),size=[5,1,5]))
    for x,z in [[11,16],[12,16],[13,16],[11,17],[13,17],[11,18],[12,18],[13,18]]:
        p.append(place_block([x,2,z],block("minecraft:water"),phase="fixture"))
    p.append(place_block([12,2,17],block("minecraft:sea_lantern"),phase="fixture"))
    for x,z in [[0,0],[24,0],[0,24],[24,24],[12,3],[12,23]]:
        p.append(fill_region([x,1,z],[x+1,3,z+1],block("minecraft:quartz_pillar")))
        p.append(place_block([x,3,z],block("minecraft:lantern"),phase="fixture"))
    return component(name="HighElfSpireCourt",props={},min_size=[25,38,25],metadata={"ground_level":1},body=group(p))
