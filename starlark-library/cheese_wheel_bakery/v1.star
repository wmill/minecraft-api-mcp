load("../lib/openings.star", "SingleDoor")
load("../lib/fixtures.star", "KitchenCounter", "Furnace", "Chest", "Barrel", "DiningTable", "Chair", "Carpet", "LanternPost", "Sign", "Bench")
load("../lib/outdoor.star", "MarketStall", "FlowerBed", "Pergola", "RoundTree", "Tree", "Well")

W = 31
L = 17
H = 14
CX = 10
CZ = 8
R = 7


def d2(x, z):
    return (x - CX) * (x - CX) + (z - CZ) * (z - CZ)


def is_extra(x, z):
    return z == 15 and (x == 9 or x == 10 or x == 11)


def is_footprint(x, z):
    return d2(x, z) <= R * R or is_extra(x, z)


def is_interior(x, z):
    return d2(x, z) <= (R - 1) * (R - 1)


def is_path(x, z):
    if z == 16 and x >= 2 and x <= 28:
        return True
    if z == 7 and x >= 19 and x <= 29:
        return True
    if x == 20 and z >= 7 and z <= 16:
        return True
    return False


def pad_block(x, z):
    if is_interior(x, z):
        return "minecraft:spruce_planks"
    if is_path(x, z):
        return "minecraft:dirt_path"
    return "minecraft:grass_block"


def wall_material(x, y, z):
    if y == 1 or y == 6:
        return "minecraft:orange_terracotta"
    if (x * 5 + z * 3 + y * 7) % 9 == 0:
        return "minecraft:yellow_stained_glass"
    if (x * 3 + z * 5 + y * 11) % 7 == 0:
        return "minecraft:yellow_terracotta"
    return "minecraft:yellow_concrete"


def build():
    parts = []

    # ground pad (local y=0), wheel floor inside the disc
    for x in range(W):
        for z in range(L):
            parts.append(place_block([x, 0, z], block(pad_block(x, z))))

    # cheese wheel walls, y=1..6
    for x in range(CX - R, CX + R + 1):
        for z in range(CZ - R, CZ + R + 1):
            if is_footprint(x, z) and not is_interior(x, z):
                for y in range(1, 7):
                    m = wall_material(x, y, z)
                    if is_extra(x, z):
                        m = "minecraft:orange_terracotta" if (y == 1 or y == 6) else "minecraft:yellow_concrete"
                    parts.append(place_block([x, y, z], block(m)))

    # roof disc y=7, rind on the outer ring, plus a low dome
    for x in range(CX - R, CX + R + 1):
        for z in range(CZ - R, CZ + R + 1):
            if is_footprint(x, z):
                m = "minecraft:yellow_concrete" if is_interior(x, z) else "minecraft:orange_terracotta"
                parts.append(place_block([x, 7, z], block(m)))
            d = d2(x, z)
            if d <= 25:
                parts.append(place_block([x, 8, z], block("minecraft:yellow_concrete")))
            if d <= 9:
                parts.append(place_block([x, 9, z], block("minecraft:orange_terracotta")))

    # front door
    parts.append(transform([10, 1, 15], 0, [1, 2, 1], SingleDoor()))

    # interior: counter, oven, storage, tables, carpet, chandeliers
    parts.append(transform([8, 1, 3], 0, [5, 2, 1], KitchenCounter(5)))
    parts.append(transform([10, 1, 2], 0, [1, 1, 1], Furnace(items=["minecraft:dough"] if False else None)))
    parts.append(transform([7, 1, 4], 0, [1, 1, 1],
                           Chest(items=["minecraft:bread", "minecraft:cake", "minecraft:pumpkin_pie", "minecraft:cookie"])))
    parts.append(transform([13, 1, 4], 0, [1, 1, 1], Barrel(items=["minecraft:milk_bucket", "minecraft:sweet_berries"])))
    parts.append(transform([14, 1, 5], 0, [1, 1, 1], Barrel(items=["minecraft:wheat", "minecraft:sugar"])))
    parts.append(transform([8, 1, 9], 0, [3, 2, 1], DiningTable(3)))
    parts.append(transform([12, 1, 9], 0, [3, 2, 1], DiningTable(3)))
    for cx in [8, 10, 12, 14]:
        parts.append(transform([cx, 1, 10], 180, [1, 1, 1], Chair()))
    parts.append(transform([9, 1, 11], 0, [3, 1, 3], Carpet(3, 3, "minecraft:orange_carpet")))
    lantern = block("minecraft:lantern", {"hanging": "true"})
    for p in [[10, 6, 8], [7, 6, 6], [13, 6, 6], [7, 6, 11], [13, 6, 11]]:
        parts.append(place_block(p, lantern, phase="fixture"))

    # outdoors
    parts.append(transform([8, 1, 16], 0, [1, 4, 1], LanternPost()))
    parts.append(transform([12, 1, 16], 0, [1, 4, 1], LanternPost()))
    parts.append(transform([14, 1, 16], 0, [1, 1, 1],
                           Sign(["", "Cheesedanish's", "Cheese & Danish", "Bakery"], color="orange", glowing=True)))
    parts.append(transform([21, 1, 3], 0, [5, 4, 3],
                           MarketStall(width=5, depth=3, canopy="minecraft:orange_wool", accent="minecraft:yellow_wool")))
    parts.append(transform([24, 1, 8], 0, [5, 5, 4], Pergola(5, 4, 4)))
    parts.append(transform([25, 1, 9], 0, [3, 1, 1], Bench(3)))
    parts.append(transform([21, 1, 12], 0, [3, 4, 3], Well()))
    parts.append(transform([26, 1, 12], 0, [5, 6, 5], RoundTree(5)))
    parts.append(transform([0, 1, 7], 0, [3, 6, 3], Tree(5)))
    parts.append(transform([0, 1, 1], 0, [3, 2, 3], FlowerBed(3, 3)))
    parts.append(transform([0, 1, 12], 0, [3, 2, 3], FlowerBed(3, 3)))
    parts.append(transform([27, 1, 0], 0, [3, 2, 3], FlowerBed(3, 3)))

    return component(
        name="CheeseWheelBakery",
        props={},
        min_size=[W, H, L],
        metadata={"ground_level": 1},
        body=group(parts),
    )
