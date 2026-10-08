load("../lib/roofs.star", "PyramidRoof")
load("../lib/outdoor.star", "RoundTree")

# Arcanum city shell: 83x83 footprint, ground_level=2.
# local y0 = foundation, y1 = paving/surface, y2 = walking plane.
N = 83
C = 41
WALL_H = 8          # wall solid y1..WALL_H-1, trim at WALL_H-? see below
TOWER = 9

def ab(v):
    return -v if v < 0 else v

def b(name, state=None):
    return block("minecraft:" + name, state or {})

def plaza_parts(parts):
    for x in range(C - 14, C + 15):
        for z in range(C - 14, C + 15):
            dx = x - C
            dz = z - C
            d2 = dx * dx + dz * dz
            if d2 >= 196:
                continue
            bridge = ab(dx) <= 1 or ab(dz) <= 1
            if d2 < 36:
                m = "purpur_block"
            elif d2 < 64:
                if bridge:
                    m = "purpur_block"
                else:
                    parts.append(place_block([x, 0, z], b("sea_lantern")))
                    m = "water"
            elif d2 < 81:
                m = "calcite"
            elif d2 < 100:
                m = "amethyst_block"
            elif d2 < 169:
                if dx == 0 or dz == 0 or ab(dx) == ab(dz):
                    m = "purpur_block"
                else:
                    m = "smooth_quartz"
            else:
                m = "polished_deepslate"
            parts.append(place_block([x, 1, z], b(m)))

def avenue_cell(parts, a, t, horizontal):
    # a = across-avenue coordinate, t = along-avenue coordinate
    pos = [t, 1, a] if horizontal else [a, 1, t]
    return pos

def avenues(parts):
    for t in range(3, N - 3):
        dt = t - C
        for a in range(C - 3, C + 4):
            da = a - C
            if dt * dt + da * da < 196:
                continue
            if ab(da) == 3:
                m = "calcite"
            elif da == 0:
                m = "purpur_block"
            else:
                m = "polished_deepslate"
            for horizontal in [False, True]:
                # skip the crossing cells already painted by the other avenue
                if horizontal and ab(dt) <= 3:
                    continue
                pos = [t, 1, a] if horizontal else [a, 1, t]
                parts.append(place_block(pos, b(m)))

def lamp_post(parts, x, z):
    for y in range(2, 5):
        parts.append(place_block([x, y, z], b("polished_blackstone_wall", {"up": "true"})))
    parts.append(place_block([x, 5, z], b("pearlescent_froglight")))
    parts.append(place_block([x, 6, z], b("end_rod", {"facing": "up"})))

FLOWERS = ["allium", "blue_orchid", "azure_bluet", "lily_of_the_valley", "cornflower"]

def avenue_dressing(parts):
    for dt in range(15, 38):
        for sign in [-1, 1]:
            t = C + sign * dt
            for side in [-4, 4]:
                a = C + side
                for horizontal in [False, True]:
                    pos = [t, 2, a] if horizontal else [a, 2, t]
                    if dt % 6 == 4:
                        lamp_post(parts, pos[0], pos[2])
                    else:
                        parts.append(place_block(pos, b(FLOWERS[(dt + side + (1 if horizontal else 0)) % 5]), phase="fixture"))

def walls(parts):
    # 3-thick ring, y1..7 deepslate bricks, y8 purpur trim, y9 merlons.
    sides = [
        ([TOWER, 1, 0], [N - TOWER, 8, 3]),
        ([TOWER, 1, N - 3], [N - TOWER, 8, N]),
        ([0, 1, TOWER], [3, 8, N - TOWER]),
        ([N - 3, 1, TOWER], [N, 8, N - TOWER]),
    ]
    for lo, hi in sides:
        parts.append(fill_region(lo, hi, b("deepslate_bricks")))
        parts.append(fill_region([lo[0], 8, lo[2]], [hi[0], 9, hi[2]], b("purpur_block")))
    for i in range(TOWER + 1, N - TOWER, 2):
        for pos in [[i, 9, 0], [i, 9, N - 1], [0, 9, i], [N - 1, 9, i]]:
            parts.append(place_block(pos, b("deepslate_bricks")))
    for i in range(12, N - TOWER, 8):
        for pos in [[i, 10, 0], [i, 10, N - 1], [0, 10, i], [N - 1, 10, i]]:
            parts.append(place_block(pos, b("end_rod", {"facing": "up"}), phase="fixture"))
    # gates at the four avenue ends, 7 wide x 5 tall, flanked by glowing pillars
    for t in [0, N - 3]:
        for horizontal in [False, True]:
            if horizontal:
                parts.append(carve_region([t, 2, C - 3], [t + 3, 7, C + 4]))
            else:
                parts.append(carve_region([C - 3, 2, t], [C + 4, 7, t + 3]))
            for side in [-4, 4]:
                for d in range(3):
                    x, z = (t + d, C + side) if horizontal else (C + side, t + d)
                    for y in range(9, 13):
                        parts.append(place_block([x, y, z], b("purpur_pillar")))
                    if d == 1:
                        parts.append(place_block([x, 13, z], b("amethyst_block")))
                        parts.append(place_block([x, 14, z], b("end_rod", {"facing": "up"}), phase="fixture"))
            # gate road paving through the wall
            for d in range(3):
                for a in range(C - 3, C + 4):
                    x, z = (t + d, a) if horizontal else (a, t + d)
                    parts.append(fill_region([x, 1, z], [x + 1, 2, z + 1], b("deepslate_bricks")))

def towers(parts):
    for cx in [0, N - TOWER]:
        for cz in [0, N - TOWER]:
            s = TOWER
            parts.append(fill_region([cx, 1, cz], [cx + s, 18, cz + 1], b("deepslate_bricks")))
            parts.append(fill_region([cx, 1, cz + s - 1], [cx + s, 18, cz + s], b("deepslate_bricks")))
            parts.append(fill_region([cx, 1, cz + 1], [cx + 1, 18, cz + s - 1], b("deepslate_bricks")))
            parts.append(fill_region([cx + s - 1, 1, cz + 1], [cx + s, 18, cz + s - 1], b("deepslate_bricks")))
            # glowing windows on each face
            for y in [7, 12]:
                parts.append(carve_region([cx + 3, y, cz], [cx + 6, y + 2, cz + 1]))
                parts.append(carve_region([cx + 3, y, cz + s - 1], [cx + 6, y + 2, cz + s]))
                parts.append(carve_region([cx, y, cz + 3], [cx + 1, y + 2, cz + 6]))
                parts.append(carve_region([cx + s - 1, y, cz + 3], [cx + s, y + 2, cz + 6]))
                for pos in [[cx + 3, y, cz], [cx + 3, y, cz + s - 1], [cx, y, cz + 3], [cx + s - 1, y, cz + 3]]:
                    along_x = pos[2] == cz or pos[2] == cz + s - 1
                    hi = [pos[0] + 3, y + 2, pos[2] + 1] if along_x else [pos[0] + 1, y + 2, pos[2] + 3]
                    parts.append(fill_region(pos, hi, b("purple_stained_glass"), phase="fixture"))
            # purpur band + floor inside, glowing core
            parts.append(fill_region([cx + 1, 17, cz + 1], [cx + s - 1, 18, cz + s - 1], b("purpur_block")))
            parts.append(place_block([cx + 4, 16, cz + 4], b("pearlescent_froglight"), phase="fixture"))
            parts.append(transform([cx, 18, cz], 0, [s, (s + 1) // 2, s],
                                   PyramidRoof(s, stair="minecraft:purpur_stairs", cap="minecraft:amethyst_block")))
            parts.append(place_block([cx + 4, 18 + (s + 1) // 2, cz + 4], b("end_rod", {"facing": "up"}), phase="fixture"))

def crystals(parts):
    for dx, dz in [[-9, -9], [9, -9], [-9, 9], [9, 9]]:
        x, z = C + dx, C + dz
        for y in range(2, 5):
            parts.append(place_block([x, y, z], b("amethyst_block")))
        parts.append(place_block([x, 5, z], b("amethyst_cluster", {"facing": "up"}), phase="fixture"))
        for ox, oz, f in [[1, 0, "east"], [-1, 0, "west"], [0, 1, "south"], [0, -1, "north"]]:
            parts.append(place_block([x + ox, 2, z + oz], b("large_amethyst_bud", {"facing": f}), phase="fixture"))

def build():
    parts = []
    plaza_parts(parts)
    avenues(parts)
    walls(parts)
    towers(parts)
    avenue_dressing(parts)
    crystals(parts)
    for dx, dz in [[-12, -12], [12, -12], [-12, 12], [12, 12]]:
        parts.append(transform([C + dx - 2, 2, C + dz - 2], 0, [5, 9, 5],
                               RoundTree(trunk_height=8, log="minecraft:cherry_log", leaves="minecraft:cherry_leaves")))
    return component(
        name="ArcanumShell",
        props={},
        min_size=[N, 26, N],
        metadata={"ground_level": 2},
        body=group(parts),
    )
