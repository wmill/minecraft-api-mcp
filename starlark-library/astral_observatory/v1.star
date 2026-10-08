# Astral Observatory: calcite-and-glass rotunda with a stained glass dome,
# four arched entrances, and a floating orrery of crystals. ground_level=1.
R = 9
WALL_H = 8

def ring_r(y, r):
    budget = r * r - y * y
    if budget < 0:
        return -1
    for c in range(r, -1, -1):
        if c * c <= budget:
            return c
    return 0

def ab(v):
    return -v if v < 0 else v

def b(n, st=None):
    return block("minecraft:" + n, st or {})

def build():
    D = 2 * R + 1
    parts = []
    for dx in range(-R, R + 1):
        for dz in range(-R, R + 1):
            d2 = dx * dx + dz * dz
            if d2 > R * R:
                continue
            x, z = R + dx, R + dz
            ring = (d2 // 9) % 2
            parts.append(place_block([x, 0, z], b("polished_deepslate" if ring == 0 else "purpur_block")))
            if d2 > (R - 1) * (R - 1):
                door = (ab(dx) <= 1 or ab(dz) <= 1)
                pillar = (dx + dz) % 3 == 0
                for y in range(1, WALL_H + 1):
                    if door and y <= 4:
                        continue
                    if y == WALL_H:
                        m = "purpur_block"
                    elif pillar:
                        m = "calcite"
                    else:
                        m = "purple_stained_glass" if y % 2 == 0 else "magenta_stained_glass"
                    parts.append(place_block([x, y, z], b(m)))
    top = WALL_H + 1
    for layer in range(R + 1):
        r = ring_r(layer, R)
        if r < 0:
            continue
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                d2 = dx * dx + dz * dz
                if d2 <= r * r and d2 > (r - 1) * (r - 1):
                    rib = dx == 0 or dz == 0
                    m = "purpur_pillar" if rib else ("light_blue_stained_glass" if (layer % 3 == 0) else "tinted_glass" if False else "purple_stained_glass")
                    parts.append(place_block([R + dx, top + layer, R + dz], b(m)))
    # orrery: central crystal pedestal with floating rings of end rods
    for y in range(1, 4):
        parts.append(place_block([R, y, R], b("amethyst_block")))
    parts.append(place_block([R, 4, R], b("beacon")))
    for dx in range(-1, 2):
        for dz in range(-1, 2):
            if dx != 0 or dz != 0:
                parts.append(place_block([R + dx, 1, R + dz], b("chiseled_quartz_block")))
    for dx, dz in [[3, 0], [-3, 0], [0, 3], [0, -3], [2, 2], [-2, -2], [2, -2], [-2, 2]]:
        parts.append(place_block([R + dx, 6, R + dz], b("pearlescent_froglight" if dx * dz == 0 else "sea_lantern")))
        parts.append(place_block([R + dx, 7, R + dz], b("amethyst_cluster", {"facing": "up"}), phase="fixture"))
    for dx, dz in [[5, 5], [-5, 5], [5, -5], [-5, -5]]:
        parts.append(place_block([R + dx, 1, R + dz], b("lectern", {"facing": "north"}), phase="fixture"))
        parts.append(fill_region([R + dx, 1, R + dz + (1 if dz > 0 else -1)], [R + dx + 1, 3, R + dz + (1 if dz > 0 else -1) + 1], b("bookshelf")))
    return component(name="AstralObservatory", props={}, min_size=[D, top + R + 1, D],
                     metadata={"ground_level": 1}, body=group(parts))
