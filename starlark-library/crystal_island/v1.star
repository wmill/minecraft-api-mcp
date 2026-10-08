load("../lib/outdoor.star", "RoundTree")
# Floating crystal island: jagged inverted cone of stone and amethyst under a
# grass top with a cherry tree, glowing cave vines dangling below.
R = 7
DEPTH = 9
BASE = 6          # vine room below the island
RADII = [7, 7, 6, 6, 5, 4, 3, 2, 1]

def h(x, y, z, seed):
    return ((x * 374761393 + z * 668265263 + y * 144269504 + seed * 1013904223) % 1000003) % 100

def b(n, st=None):
    return block("minecraft:" + n, st or {})

def build(seed=1):
    D = 2 * R + 1
    top = BASE + DEPTH - 1
    parts = []
    for k in range(DEPTH):
        y = top - k
        r = RADII[k]
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                d2 = dx * dx + dz * dz
                if d2 > r * r:
                    continue
                x, z = R + dx, R + dz
                if d2 > (r - 1) * (r - 1) and h(x, y, z, seed) < 35 and k > 0:
                    continue
                if k == 0:
                    m = "grass_block"
                elif k <= 2:
                    m = "dirt"
                else:
                    roll = h(x, y, z, seed + 7)
                    m = "amethyst_block" if roll < 14 else "calcite" if roll < 24 else "deepslate" if roll < 40 else "stone"
                parts.append(place_block([x, y, z], b(m)))
    parts.append(place_block([R, BASE - 1, R], b("amethyst_cluster", {"facing": "down"}), phase="fixture"))
    # dangling glow berries from the underside rim
    n = 0
    for dx, dz, k in [[4, 1, 4], [-3, 3, 4], [1, -4, 4], [-4, -2, 4], [2, 4, 4]]:
        y0 = top - k
        length = 3 + (h(dx, k, dz, seed) % 4)
        for i in range(1, length + 1):
            st = {"berries": "true"}
            nm = "cave_vines" if i == length else "cave_vines_plant"
            parts.append(place_block([R + dx, y0 - i, R + dz], b(nm, st), phase="fixture"))
    # surface: tree, flowers, crystal shrine
    tx = 2 if seed % 2 == 0 else 8
    parts.append(transform([tx, top + 1, 5], 0, [5, 9, 5],
                           RoundTree(trunk_height=8, log="minecraft:cherry_log", leaves="minecraft:cherry_leaves")))
    sx = 11 if tx == 2 else 3
    for y in range(top + 1, top + 4):
        parts.append(place_block([sx, y, R], b("amethyst_block")))
    parts.append(place_block([sx, top + 4, R], b("end_rod", {"facing": "up"}), phase="fixture"))
    for dx, dz in [[-1, -3], [1, 3], [3, -1], [-3, 2], [0, -5], [4, 3], [-5, 0]]:
        x, z = R + dx, R + dz
        if (x >= tx and x < tx + 5 and z >= 5 and z < 10) or (x == sx and z == R):
            continue
        parts.append(place_block([x, top + 1, z], b(["allium", "blue_orchid", "lily_of_the_valley"][(dx + dz) % 3]), phase="fixture"))
    return component(name="FloatingIsland", props={"seed": seed}, min_size=[D, top + 10, D], body=group(parts))
