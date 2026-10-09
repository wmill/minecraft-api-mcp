load("../library/cheese_wheel/v2.star", "CheeseWheel")
load("../library/hot_air_balloon/v1.star", "HotAirBalloon")
load("../library/viking_longhouse/v1.star", "RuneStone")
load("../lib/shapes.star", "disc_cells")
load("../lib/outdoor.star", "MarketStall", "FlowerBed", "RoundTree")
load("../lib/fixtures.star", "Bench", "LanternPost", "Sign")


def FestivalPaving(width, length, skip=None, a="minecraft:stone_bricks", b="minecraft:yellow_terracotta",
                   border="minecraft:polished_andesite"):
    """width x 1 x length paving: border ring, diagonal yellow lattice. `skip` is a
    dict of (x, z) cells to leave unpaved (e.g. under a round building)."""
    ops = []
    for x in range(width):
        for z in range(length):
            if skip != None and skip.get((x, z)):
                continue
            if x == 0 or z == 0 or x == width - 1 or z == length - 1:
                m = border
            elif (x + z) % 4 == 0 or (x - z) % 4 == 0:
                m = b
            else:
                m = a
            ops.append(place_block([x, 0, z], block(m)))
    return component(name="FestivalPaving", props={"width": width, "length": length},
                     min_size=[width, 1, length], body=group(ops))


def TetheredBalloon(height=8, color_a="minecraft:orange_wool", color_b="minecraft:yellow_wool"):
    """Small radius-3 HotAirBalloon moored `height` blocks up on a chain tether
    from a stone bollard. Size [7, height+12, 7]; bollard at local (3,0,3)."""
    return component(name="TetheredBalloon", props={"height": height}, min_size=[7, height + 12, 7],
                     body=group([
                         place_block([3, 0, 3], block("minecraft:polished_deepslate")),
                         fill_region([3, 1, 3], [4, height, 4], block("minecraft:chain")),
                         at([0, height, 0], HotAirBalloon(color_a, color_b, 6, 3)),
                     ]))


def build():
    """Cheesedanish Harbour cheese festival square, 34 x 25 x 19, ground_level 1.
    A CheeseWheel pavilion (from the cheese_wheel library) at the back, two
    tethered balloons (hot_air_balloon library), a runestone (viking_longhouse
    library), three market stalls facing in from the south, benches, flower
    beds, trees and lanterns. Open to the south and west."""
    w = 34
    l = 19
    skip = {}
    for c in disc_cells(4):
        skip[(c[0] + 13, c[1] + 1)] = True
    skip[(4, 4)] = True     # balloon bollards
    skip[(29, 12)] = True
    parts = [
        FestivalPaving(w, l, skip),
        at([13, 0, 1], CheeseWheel(4, 7)),
        at([1, 0, 1], TetheredBalloon(9)),
        at([26, 0, 9], TetheredBalloon(12, "minecraft:red_wool", "minecraft:white_wool")),
        at([29, 1, 2], RuneStone()),
        at([3, 1, 14], MarketStall(canopy="minecraft:yellow_wool"), rotation=180),
        at([14, 1, 14], MarketStall(canopy="minecraft:orange_wool"), rotation=180),
        at([22, 1, 14], MarketStall(canopy="minecraft:red_wool"), rotation=180),
        at([10, 1, 6], FlowerBed(3, 5, "minecraft:dandelion", "minecraft:orange_tulip")),
        at([23, 1, 6], FlowerBed(3, 5, "minecraft:dandelion", "minecraft:orange_tulip")),
        at([7, 1, 1], RoundTree(5)),
        at([24, 1, 1], LanternPost(3)),
        at([12, 1, 11], Bench(4), rotation=180),
        at([19, 1, 11], Bench(4), rotation=180),
        at([9, 1, 11], LanternPost(3)),
        at([24, 1, 11], LanternPost(3)),
        at([17, 1, 11], Sign(["Cheesedanish", "Harbour", "Cheese Festival", ""], color="orange", glowing=True)),
    ]
    return component(name="CheeseFestivalSquare", props={}, min_size=[w, 25, l],
                     metadata={"ground_level": 1}, body=group(parts))
