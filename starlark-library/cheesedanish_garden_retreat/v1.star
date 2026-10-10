load("../lib/dwellings.star", "GuestHouse")
load("../lib/outdoor.star", "MarketStall", "Pergola", "FlowerBed")
load("../lib/fixtures.star", "Bench", "Table", "LanternPost", "Sign")

def build():
    parts = [
        carve_region([2,1,2], [9,4,8]),
        at([1, 0, 1], GuestHouse(width=9, depth=8, infill="minecraft:calcite", log="minecraft:stripped_spruce_log", roof_stair="minecraft:dark_oak_stairs", roof_ridge="minecraft:dark_oak_log", bed="minecraft:yellow_bed")),
        at([13, 1, 1], MarketStall(canopy="minecraft:yellow_wool")),
        at([13, 1, 7], Pergola()),
        at([14, 1, 8], Bench(3)),
        at([15, 1, 10], Table()),
        at([1, 0, 12], FlowerBed(7, 3, flower_a="minecraft:cornflower")),
        at([13, 0, 13], FlowerBed(5, 3)),
        at([10, 1, 2], LanternPost()),
        at([10, 1, 13], LanternPost()),
        at([6, 1, 10], Sign(lines=["Cheesedanish", "Guest Cottage", "& Garden Market", "Preview-tested!"])),
    ]
    for x in range(22):
        for z in range(18):
            if not ((1 <= x and x < 10) and (1 <= z and z < 9)) and not ((1 <= x and x < 8) and (12 <= z and z < 15)) and not ((13 <= x and x < 18) and (13 <= z and z < 16)):
                material = "minecraft:stone_bricks" if x in [0,21] or z in [0,17] else ("minecraft:andesite" if (x+z)%3 else "minecraft:mossy_stone_bricks")
                parts.append(place_block([x,0,z], block(material)))
    return component(name="CheesedanishGardenRetreat", props={}, min_size=[22,10,18], metadata={"ground_level":1}, body=group(parts))
