# Arcanum Spire: round tower with a twisting amethyst helix, enchanting hall,
# ladder to a crowned balcony, and a magenta beacon beam. ground_level=1.
S = 13
C = 6
TOP = 34

def ab(v):
    return -v if v < 0 else v

def b(name, state=None):
    return block("minecraft:" + name, state or {})

def pseudo_angle(dx, dz):
    # diamond angle in [0, 4), monotonic with true angle
    s = ab(dx) + ab(dz)
    if s == 0:
        return 0.0
    if dz >= 0:
        return (1.0 - dx / s) if dx >= 0 else (1.0 - dx / s)
    return 3.0 + dx / s if dx >= 0 else 3.0 + dx / s

def build():
    parts = []
    for dx in range(-C, C + 1):
        for dz in range(-C, C + 1):
            d2 = dx * dx + dz * dz
            x, z = C + dx, C + dz
            door_axis = ab(dx) <= 1 or ab(dz) <= 1
            if d2 <= 20:
                parts.append(place_block([x, 0, z], b("calcite" if d2 > 12 else "chiseled_quartz_block" if d2 == 0 else "smooth_quartz")))
            if d2 > 20 and d2 <= 30:
                for y in range(1, 3):
                    if not door_axis:
                        parts.append(place_block([x, y, z], b("deepslate_tiles")))
                parts.append(place_block([x, TOP, z], b("purpur_block")))
                parts.append(place_block([x, TOP + 1, z], b("magenta_stained_glass")))
            if d2 > 12 and d2 <= 20:
                pa = pseudo_angle(dx, dz)
                for y in range(1, TOP):
                    if door_axis and y <= 4:
                        continue
                    stripe = int(pa * 6 + y / 3.0) % 6
                    diag = ab(dx) == ab(dz) or (ab(dx) == 3 and ab(dz) == 2) or (ab(dx) == 2 and ab(dz) == 3)
                    if y % 7 in [3, 4] and diag and y > 5:
                        m = "magenta_stained_glass"
                    elif stripe == 0:
                        m = "amethyst_block"
                    elif y % 7 == 0:
                        m = "end_stone_bricks"
                    else:
                        m = "purpur_block"
                    parts.append(place_block([x, y, z], b(m)))
                parts.append(place_block([x, TOP, z], b("purpur_block")))
            if d2 <= 12:
                ladder = dx == 2 and dz == -2
                for fy in [12, 24, TOP]:
                    if not ladder:
                        parts.append(place_block([x, fy, z], b("purpur_block")))
                if ladder:
                    for y in range(1, TOP + 1):
                        parts.append(place_block([x, y, z], b("ladder", {"facing": "south"}), phase="fixture"))
                elif d2 >= 8 and not door_axis:
                    for y in [1, 2]:
                        parts.append(place_block([x, y, z], b("bookshelf")))
    # enchanting hall centerpiece
    parts.append(place_block([C, 1, C], b("enchanting_table"), phase="fixture"))
    for dx, dz in [[2, 2], [-2, 2], [-2, -2]]:
        parts.append(place_block([C + dx, 3, C + dz], b("amethyst_cluster", {"facing": "up"}), phase="fixture"))
    for y in [11, 23]:
        parts.append(place_block([C, y, C], b("pearlescent_froglight"), phase="fixture"))
    # crown: pillars, beacon on iron, glass spire tinting the beam
    for dx, dz in [[-4, -4], [4, -4], [-4, 4], [4, 4]]:
        for y in range(TOP + 1, TOP + 6):
            parts.append(place_block([C + dx, y, C + dz], b("purpur_pillar")))
        parts.append(place_block([C + dx, TOP + 6, C + dz], b("amethyst_block")))
        parts.append(place_block([C + dx, TOP + 7, C + dz], b("end_rod", {"facing": "up"}), phase="fixture"))
    parts.append(fill_region([C - 1, TOP + 1, C - 1], [C + 2, TOP + 2, C + 2], b("iron_block")))
    parts.append(place_block([C, TOP + 2, C], b("beacon")))
    for y in range(TOP + 3, TOP + 9):
        parts.append(place_block([C, y, C], b("magenta_stained_glass")))
    for dx, dz in [[1, 0], [-1, 0], [0, 1], [0, -1]]:
        for y in range(TOP + 3, TOP + 6):
            parts.append(place_block([C + dx, y, C + dz], b("purple_stained_glass")))
    return component(
        name="ArcanumSpire",
        props={},
        min_size=[S, TOP + 10, S],
        metadata={"ground_level": 1},
        body=group(parts),
    )
