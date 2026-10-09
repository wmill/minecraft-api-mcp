load("../lib/shapes.star", "sphere_layer_cells", "disc_cells", "fill_cells")


def HotAirBalloon(color_a="minecraft:red_wool", color_b="minecraft:yellow_wool",
                  stripes=8, radius=5, basket="minecraft:spruce_planks"):
    """Striped hot-air balloon, (2r+1) x (2r+7) x (2r+1). A hollow envelope with
    `stripes` vertical gores alternating color_a/color_b, an open mouth and skirt,
    chain rigging, a fenced wicker basket with a hanging soul-lantern burner.
    No ground: place it in the air (basket floor is local y=0)."""
    c = radius
    d = 2 * radius + 1
    ops = []
    # basket
    ops.append(fill_region([c - 1, 0, c - 1], [c + 2, 1, c + 2], block(basket)))
    for dx in [-1, 0, 1]:
        for dz in [-1, 0, 1]:
            if dx != 0 or dz != 0:
                ops.append(place_block([c + dx, 1, c + dz], block("minecraft:spruce_fence"), phase="fixture"))
    ops.append(place_block([c, 1, c], block("minecraft:barrel", {"facing": "up"})))
    # rigging + skirt + burner
    for dx in [-1, 1]:
        for dz in [-1, 1]:
            for y in range(2, 5):
                ops.append(place_block([c + dx, y, c + dz], block("minecraft:chain")))
    ops.extend(fill_cells([[p[0] + c - 2, p[1] + c - 2] for p in disc_cells(2)], 5, 6, block(color_b)))
    ops.append(place_block([c, 4, c], block("minecraft:soul_lantern", {"hanging": "true"}), phase="fixture"))
    # envelope: center at y = 6 + r - 1, skip the bottom-most slice to leave a mouth
    cy = 5 + radius
    for dy in range(-(radius - 1), radius + 1):
        for p in sphere_layer_cells(radius, dy, 1):
            dx = p[0] - c
            dz = p[1] - c
            sector = int((atan2(dz, dx) + PI) / (2 * PI) * stripes) % stripes
            m = color_a if sector % 2 == 0 else color_b
            if dy == radius:
                m = color_a
            ops.append(place_block([p[0], cy + dy, p[1]], block(m)))
    return component(name="HotAirBalloon",
                     props={"radius": radius, "stripes": stripes},
                     min_size=[d, cy + radius + 1, d], body=group(ops))


def build():
    """Three balloons drifting at different heights (no ground; place in the sky)."""
    return component(name="BalloonFlotilla", props={}, min_size=[37, 30, 24],
                     body=group([
                         at([0, 6, 0], HotAirBalloon()),
                         at([13, 0, 11], HotAirBalloon("minecraft:light_blue_wool", "minecraft:white_wool", 10)),
                         at([26, 12, 3], HotAirBalloon("minecraft:magenta_wool", "minecraft:lime_wool", 6)),
                     ]))
