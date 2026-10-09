load("../lib/roofs.star", "GableRoof")
load("../lib/openings.star", "SingleDoor", "Window")
load("../lib/fixtures.star", "Barrel", "Chest", "LanternPost", "WallSign", "Bench")


_PROFILE = [0, 1, 1, 2, 2, 2, 2, 2, 2, 1, 1, 0]


def Sailboat(hull="minecraft:blue_terracotta", deck="minecraft:spruce_slab",
             keel="minecraft:dark_oak_planks", sail="minecraft:white_wool",
             mast="minecraft:spruce_log"):
    """Block-built sailboat, 5x12x12, bow toward +Z. Local y=0 is the waterline
    (place it on the water surface with ground_level 1). Mast amidships with a
    triangular fore-and-aft sail and a striped pennant."""
    ops = []
    n = len(_PROFILE)
    for z in range(n):
        w = _PROFILE[z]
        for dx in range(-w, w + 1):
            x = 2 + dx
            ops.append(place_block([x, 0, z], block(keel)))
            if abs(dx) == w:
                ops.append(place_block([x, 1, z], block(hull)))
                ops.append(place_block([x, 2, z], block(deck)))
    # stern seat and cargo
    ops.append(place_block([2, 1, 2], block("minecraft:spruce_stairs", {"facing": "north"}), phase="fixture"))
    ops.append(place_block([2, 1, 3], block("minecraft:barrel", {"facing": "up"}), phase="fixture"))
    # mast + sail
    mz = 6
    for y in range(1, 12):
        ops.append(place_block([2, y, mz], block(mast)))
    for y in range(3, 11):
        span = (11 - y) // 2 + 1
        for z in range(mz - span, mz):
            if z >= 1:
                ops.append(place_block([2, y, z], block(sail)))
    ops.append(place_block([2, 11, mz + 1], block("minecraft:red_wool")))
    return component(name="Sailboat", props={}, min_size=[5, 12, n], body=group(ops))


def FishingHut(wall="minecraft:spruce_planks", trim="minecraft:stripped_spruce_log",
               roof="minecraft:dark_oak_stairs", ridge="minecraft:dark_oak_planks"):
    """7x8x7 fisher's hut with a south door, side windows, gable roof, barrels,
    a chest of fishing gear and a hanging lantern. Floor at y=0."""
    ops = [
        fill_region([0, 0, 0], [7, 1, 7], block("minecraft:spruce_planks")),
        fill_region([1, 1, 0], [6, 4, 1], block(wall)),
        fill_region([1, 1, 6], [6, 4, 7], block(wall)),
        fill_region([0, 1, 1], [1, 4, 6], block(wall)),
        fill_region([6, 1, 1], [7, 4, 6], block(wall)),
    ]
    for p in [[0, 0], [6, 0], [0, 6], [6, 6]]:
        ops.append(fill_region([p[0], 1, p[1]], [p[0] + 1, 4, p[1] + 1], block(trim, {"axis": "y"})))
    ops.append(at([3, 1, 6], SingleDoor(material="minecraft:spruce_door")))
    ops.append(at([0, 2, 3], Window(), rotation=90))
    ops.append(at([6, 2, 3], Window(), rotation=270))
    ops.append(at([0, 4, 0], GableRoof(7, 7, stair=roof, ridge=ridge, gable=wall)))
    ops.append(at([1, 1, 1], Barrel(items=["minecraft:cod", "minecraft:salmon", "minecraft:salmon"])))
    ops.append(at([5, 1, 1], Chest(items=["minecraft:fishing_rod", "minecraft:fishing_rod", "minecraft:cod_bucket", "minecraft:kelp"])))
    ops.append(place_block([4, 1, 1], block("minecraft:smoker", {"facing": "south"}), phase="fixture"))
    ops.append(place_block([3, 3, 3], block("minecraft:lantern", {"hanging": "true"}), phase="fixture"))
    return component(name="FishingHut", props={}, min_size=[7, 8, 7], body=group(ops))


def Pier(length=24, depth=6, piling="minecraft:spruce_log", deck="minecraft:spruce_planks",
         rail="minecraft:spruce_fence", head=True):
    """Wooden pier running toward +Z: a 5-wide boardwalk on log pilings `depth`
    deep, fence rails, lantern posts every 6 blocks, and (head=True) a 15-wide
    T-head with a fishing hut, benches and barrels. Size [15, depth+9, length];
    the deck is local y=depth (use ground_level=depth+1 for the walking plane).
    Entry is the open z=0 end."""
    ops = []
    x0 = 5
    x1 = 10
    body_end = length - 9 if head else length
    ops.append(fill_region([x0, depth, 0], [x1, depth + 1, body_end], block(deck)))
    for z in range(2, body_end, 4):
        for x in [x0, x1 - 1]:
            ops.append(fill_region([x, 0, z], [x + 1, depth, z + 1], block(piling, {"axis": "y"})))
    for z in range(1, body_end):
        if z % 6 == 3:
            ops.append(at([x0, depth + 1, z], LanternPost(2, post=rail)))
            ops.append(at([x1 - 1, depth + 1, z], LanternPost(2, post=rail)))
        else:
            ops.append(place_block([x0, depth + 1, z], block(rail), phase="fixture"))
            ops.append(place_block([x1 - 1, depth + 1, z], block(rail), phase="fixture"))
    if head:
        hz = length - 9
        ops.append(fill_region([0, depth, hz], [15, depth + 1, length], block(deck)))
        for p in [[0, hz], [14, hz], [0, length - 1], [14, length - 1], [7, length - 1], [4, hz + 3], [10, hz + 3]]:
            ops.append(fill_region([p[0], 0, p[1]], [p[0] + 1, depth, p[1] + 1], block(piling, {"axis": "y"})))
        # rails around the head, gap at the boardwalk joint
        for x in range(15):
            ops.append(place_block([x, depth + 1, length - 1], block(rail), phase="fixture"))
            if x < x0 or x >= x1:
                ops.append(place_block([x, depth + 1, hz], block(rail), phase="fixture"))
        for z in range(hz + 1, length - 1):
            ops.append(place_block([0, depth + 1, z], block(rail), phase="fixture"))
            ops.append(place_block([14, depth + 1, z], block(rail), phase="fixture"))
        ops.append(at([1, depth, hz + 1], FishingHut(), rotation=90))
        ops.append(at([10, depth + 1, length - 2], Bench(3), rotation=180))
        ops.append(at([12, depth + 1, hz + 1], Barrel(items=["minecraft:cod"])))
        ops.append(at([13, depth + 1, hz + 1], Barrel()))
        ops.append(at([13, depth + 1, hz + 2], LanternPost(2, post=rail)))
    return component(name="Pier", props={"length": length, "depth": depth, "head": head},
                     min_size=[15, depth + 9, length], body=group(ops))


def build(length=24, depth=6, boats=True):
    """Pier with two moored sailboats (boats=False for the bare pier, 15 wide).
    Walking plane = pier deck (ground_level=depth+1); boats sit at the waterline
    one block below the deck. The open entry end is z=0."""
    if not boats:
        return component(name="HarbourPier", props={"length": length, "depth": depth, "boats": boats},
                         min_size=[15, depth + 9, length], metadata={"ground_level": depth + 1},
                         body=Pier(length, depth))
    return component(name="HarbourPier", props={"length": length, "depth": depth, "boats": boats},
                     min_size=[27, depth + 12, length], metadata={"ground_level": depth + 1},
                     body=group([
                         at([6, 0, 0], Pier(length, depth)),
                         at([0, depth - 1, 4], Sailboat()),
                         at([22, depth - 1, 6], Sailboat(hull="minecraft:red_terracotta", sail="minecraft:yellow_wool")),
                     ]))


def boat(style=0):
    """A single Sailboat for placing on open water: ground_level 1 puts the
    waterline (local y=0) one below the given walking plane, so place with
    y = water surface + 1. style 0-3 picks hull/sail colours."""
    looks = [
        ["minecraft:blue_terracotta", "minecraft:white_wool"],
        ["minecraft:red_terracotta", "minecraft:yellow_wool"],
        ["minecraft:green_terracotta", "minecraft:white_wool"],
        ["minecraft:white_terracotta", "minecraft:red_wool"],
    ]
    lk = looks[style % len(looks)]
    return component(name="MooredSailboat", props={"style": style}, min_size=[5, 12, 12],
                     metadata={"ground_level": 1}, body=Sailboat(hull=lk[0], sail=lk[1]))
