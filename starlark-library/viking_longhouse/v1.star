load("../lib/openings.star", "SingleDoor")
load("../lib/fixtures.star", "Chest", "Barrel", "Table")
load("../lib/outdoor.star", "Tree")


def _half(z, length):
    """Bowed long walls: half-width 3 near the gables, 4 amidships."""
    if z < 3 or z > length - 4:
        return 3
    return 4


def Longhouse(length=21, wall="minecraft:dark_oak_planks", post="minecraft:dark_oak_log",
              roof="minecraft:spruce_stairs", ridge="minecraft:spruce_planks",
              base="minecraft:cobblestone", floor="minecraft:spruce_planks"):
    """Viking longhouse, 11 x 11 x length, ridge along +Z. Bowed walls give a curved
    ridge line; crossed dragon boards top both gables. Doors: south gable (5,1,length-1)
    and both long sides amidships. Inside: long hearth of campfires, wall benches,
    a high seat at the north end, chests, barrels and hanging lanterns. Floor y=0."""
    cx = 5
    ops = []
    mid = length // 2
    for z in range(length):
        w = _half(z, length)
        wn = min(_half(max(z - 1, 0), length), _half(min(z + 1, length - 1), length))
        gable = z == 0 or z == length - 1
        for dx in range(-w, w + 1):
            x = cx + dx
            edge = gable or abs(dx) >= min(w, wn)
            ops.append(place_block([x, 0, z], block(base if edge else floor)))
            if edge:
                m = post if (abs(dx) == w and (z % 4 == 0 or gable)) else wall
                for y in range(1, 4):
                    st = {"axis": "y"} if m == post else {}
                    ops.append(place_block([x, y, z], block(m, st)))
        # roof slopes + ridge
        for i in range(w + 1):
            ops.append(place_block([cx - (w + 1) + i, 3 + i, z], block(roof, {"facing": "east", "half": "bottom"})))
            ops.append(place_block([cx + (w + 1) - i, 3 + i, z], block(roof, {"facing": "west", "half": "bottom"})))
        ops.append(place_block([cx, 4 + w, z], block(ridge)))
        if gable:
            for i in range(1, w + 1):
                for dx in range(-(w - i), w - i + 1):
                    ops.append(place_block([cx + dx, 3 + i, z], block(wall)))
            for k in [1, 2]:
                ops.append(place_block([cx - k, 4 + w + k - 1, z], block(post, {"axis": "y"})))
                ops.append(place_block([cx + k, 4 + w + k - 1, z], block(post, {"axis": "y"})))
        elif z % 5 == 0:
            ops.append(place_block([cx, 3 + w, z], block("minecraft:lantern", {"hanging": "true"}), phase="fixture"))
    # doors
    ops.append(at([cx, 1, length - 1], SingleDoor(material="minecraft:spruce_door")))
    ops.append(at([cx - 4, 1, mid], SingleDoor(material="minecraft:spruce_door"), rotation=90))
    ops.append(at([cx + 4, 1, mid], SingleDoor(material="minecraft:spruce_door"), rotation=270))
    # long hearth
    for z in range(mid - 3, mid + 4, 2):
        ops.append(place_block([cx, 1, z], block("minecraft:campfire", {"facing": "south", "lit": "true"}), phase="fixture"))
    for z in range(mid - 2, mid + 3, 2):
        ops.append(place_block([cx, 1, z], block("minecraft:stone_brick_slab"), phase="fixture"))
    # wall benches (face inward), skipping door bays
    for z in range(4, length - 4):
        if abs(z - mid) <= 1:
            continue
        ops.append(place_block([cx - 3, 1, z], block("minecraft:spruce_stairs", {"facing": "west"}), phase="fixture"))
        ops.append(place_block([cx + 3, 1, z], block("minecraft:spruce_stairs", {"facing": "east"}), phase="fixture"))
    # high seat at the north gable, flanked by lanterns and gold
    ops.append(place_block([cx, 1, 1], block("minecraft:dark_oak_stairs", {"facing": "north"}), phase="fixture"))
    ops.append(place_block([cx - 1, 1, 1], block("minecraft:gold_block"), phase="fixture"))
    ops.append(place_block([cx + 1, 1, 1], block("minecraft:gold_block"), phase="fixture"))
    ops.append(place_block([cx - 1, 2, 1], block("minecraft:lantern"), phase="fixture"))
    ops.append(place_block([cx + 1, 2, 1], block("minecraft:lantern"), phase="fixture"))
    ops.append(at([cx - 2, 1, 1], Chest(items=["minecraft:iron_axe", "minecraft:shield", "minecraft:gold_ingot", "minecraft:honey_bottle"])))
    ops.append(at([cx + 2, 1, 1], Barrel(items=["minecraft:honey_bottle", "minecraft:honey_bottle", "minecraft:bread"])))
    ops.append(at([cx - 2, 1, length - 3], Barrel(items=["minecraft:cod", "minecraft:salmon"])))
    ops.append(place_block([cx + 2, 1, length - 3], block("minecraft:crafting_table"), phase="fixture"))
    ops.append(at([cx - 1, 1, mid - 5], Table()))
    ops.append(at([cx + 1, 1, mid + 5], Table()))
    return component(name="Longhouse", props={"length": length},
                     min_size=[11, 11, length], body=group(ops))


def RuneStone(stone="minecraft:stone", rune="minecraft:polished_blackstone", moss="minecraft:mossy_cobblestone"):
    """Jelling-style runestone, 3x6x2: a tapered slab of stone with dark carved
    rune bands on the south face, a mossy base and a tiny dandelion offering."""
    ops = []
    rows = [[0, 3], [0, 3], [0, 3], [0, 3], [0, 2], [1, 2]]
    for y in range(len(rows)):
        for x in range(rows[y][0], rows[y][1]):
            for z in [0, 1]:
                m = moss if y == 0 else stone
                if z == 1 and y in [1, 3] and x != 1 or z == 1 and y == 2 and x == 1 or z == 1 and y == 4 and x == 1:
                    m = rune
                ops.append(place_block([x, y, z], block(m)))
    return component(name="RuneStone", props={}, min_size=[3, 6, 2], body=group(ops))


def build(length=21):
    """Longhouse on a cobble pad with a runestone, wood pile and two spruces
    out front. ground_level 1; door faces south."""
    L = length
    return component(name="LonghouseYard", props={"length": length},
                     min_size=[21, 11, L + 7], metadata={"ground_level": 1},
                     body=group([
                         at([5, 0, 0], Longhouse(L)),
                         fill_region([9, 0, L], [12, 1, L + 7], block("minecraft:dirt_path")),
                         at([14, 1, L + 3], RuneStone()),
                         at([0, 1, L + 1], Tree(6, log="minecraft:spruce_log", leaves="minecraft:spruce_leaves")),
                         at([17, 1, 2], Tree(7, log="minecraft:spruce_log", leaves="minecraft:spruce_leaves")),
                         fill_region([1, 1, 4], [3, 3, 9], block("minecraft:spruce_log", {"axis": "z"})),
                         place_block([6, 1, L + 2], block("minecraft:oak_log", {"axis": "y"})),
                         place_block([6, 2, L + 2], block("minecraft:oak_pressure_plate"), phase="fixture"),
                     ]))
