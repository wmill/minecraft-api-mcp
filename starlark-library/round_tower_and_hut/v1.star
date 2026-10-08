load("../lib/shapes.star", "Cylinder")
load("../lib/roofs.star", "ConeRoof", "HipRoof")
load("../lib/openings.star", "SingleDoor")

def hut():
    return group([
        fill_region([0, 0, 0], [4, 3, 4], block("minecraft:spruce_planks")),
        carve_region([1, 1, 1], [3, 3, 3]),
        transform([1, 1, 3], 0, [1, 2, 1], SingleDoor()),
        transform([0, 3, 0], 0, [4, 2, 4], HipRoof(4, 4, stair="minecraft:dark_oak_stairs", ridge="minecraft:dark_oak_planks")),
    ])

def build():
    pane = block("minecraft:glass_pane")
    parts = [
        Cylinder(4, 12, material="minecraft:stone_bricks", floor="minecraft:spruce_planks"),
        transform([0, 12, 0], 0, [9, 9, 9], ConeRoof(4, stair="minecraft:dark_oak_stairs", cap="minecraft:dark_oak_planks", steep=True)),
        transform([4, 1, 8], 0, [1, 2, 1], SingleDoor()),
        carve_region([4, 5, 0], [5, 8, 1]),
        carve_region([0, 5, 4], [1, 8, 5]),
        carve_region([8, 5, 4], [9, 8, 5]),
        transform([9, 0, 0], 0, [4, 5, 4], hut()),
    ]
    for p in [[4, 5, 0], [4, 6, 0], [4, 7, 0], [0, 5, 4], [0, 6, 4], [0, 7, 4], [8, 5, 4], [8, 6, 4], [8, 7, 4]]:
        parts.append(place_block(p, pane, phase="fixture"))
    return component(name="RoundTowerAndHut", props={}, min_size=[13, 21, 13],
                     metadata={"ground_level": 0}, body=group(parts))
