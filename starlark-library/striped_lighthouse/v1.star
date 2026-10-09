load("../lib/shapes.star", "ring_cells", "disc_cells", "fill_cells", "Dome")
load("../lib/openings.star", "SingleDoor")
load("../lib/fixtures.star", "Ladder", "Chest", "Bed", "WallSign")
load("../lib/random.star", "random_cycle", "random_number")


def _shift(cells, d):
    return [[c[0] + d, c[1] + d] for c in cells]


def RockIsland(radius=6, depth=5, seed=0):
    """Jagged rock pedestal: (2r+1) x depth x (2r+1). Top layer is a solid
    radius-1 disc so a tower of radius <= r-1 can stand on it. Mixed stone,
    cobble, mossy cobble and andesite; lower layers flare out with ragged edges."""
    cyc = random_cycle(seed * 97)
    mats = ["minecraft:stone", "minecraft:cobblestone", "minecraft:mossy_cobblestone",
            "minecraft:andesite", "minecraft:stone", "minecraft:gravel"]
    ops = []
    for y in range(depth):
        r = radius - 1 if y == depth - 1 else radius
        for c in disc_cells(r):
            dx = c[0] - r
            dz = c[1] - r
            roll = random_number(cyc)
            edge = dx * dx + dz * dz > (r - 1) * (r - 1)
            if edge and y < depth - 1 and roll < 0.3:
                continue
            m = mats[int(random_number(cyc) * len(mats))]
            if y == depth - 1 and m == "minecraft:gravel":
                m = "minecraft:stone"
            ops.append(place_block([c[0] + radius - r, y, c[1] + radius - r], block(m)))
    return component(name="RockIsland", props={"radius": radius, "depth": depth, "seed": seed},
                     min_size=[2 * radius + 1, depth, 2 * radius + 1], body=group(ops))


def Lighthouse(height=22, stripe_a="minecraft:white_concrete", stripe_b="minecraft:red_concrete",
               band=3, roof="minecraft:red_terracotta", glass="minecraft:yellow_stained_glass"):
    """Striped round lighthouse, 11 x (height+12) x 11, floor at local y=0, door
    facing south at (5,1,9). Lower half is 2-thick radius 4, upper half radius 3,
    one shared radius-2 shaft with a ladder up the north side into a glass lamp
    room, a railed gallery deck, and a domed cap with a lightning rod."""
    c = 5
    half = height // 2
    top = height + 1
    ops = []
    ops.extend(fill_cells(_shift(disc_cells(2), c - 2), 0, 1, block("minecraft:spruce_planks")))
    for y in range(1, top):
        m = stripe_a if ((y - 1) // band) % 2 == 0 else stripe_b
        if y <= half:
            ops.extend(fill_cells(_shift(ring_cells(4, thickness=2), c - 4), y, y + 1, block(m)))
        else:
            ops.extend(fill_cells(_shift(ring_cells(3), c - 3), y, y + 1, block(m)))
    # door (south) through the 2-thick wall
    ops.append(carve_region([c, 1, c + 3], [c + 1, 3, c + 4]))
    ops.append(at([c, 1, c + 4], SingleDoor(material="minecraft:spruce_door")))
    # slit windows
    for y in [half - 3, half + 3, height - 3]:
        r = 4 if y <= half else 3
        for d in [[c + r, c], [c - r, c], [c, c + r]]:
            ops.append(carve_region([d[0], y, d[1]], [d[0] + 1, y + 2, d[1] + 1]))
            if r == 4:
                inner = [d[0] - 1 if d[0] > c else (d[0] + 1 if d[0] < c else d[0]), d[1] - 1 if d[1] > c else d[1]]
                ops.append(carve_region([inner[0], y, inner[1]], [inner[0] + 1, y + 2, inner[1] + 1]))
            ops.append(place_block([d[0], y, d[1]], block("minecraft:glass_pane"), phase="fixture"))
            ops.append(place_block([d[0], y + 1, d[1]], block("minecraft:glass_pane"), phase="fixture"))
    # gallery deck (radius 5) with railing, hatch for the ladder
    ops.extend(fill_cells(disc_cells(5), top, top + 1, block("minecraft:smooth_stone")))
    ops.append(carve_region([c, top, c - 2], [c + 1, top + 1, c - 1]))
    ops.extend(fill_cells(ring_cells(5), top + 1, top + 2, block("minecraft:iron_bars"), phase="fixture"))
    ops.append(at([c, 1, c - 2], Ladder(top)))
    # lamp room: glass ring radius 3 with a south opening, glowing core
    ops.extend(fill_cells(_shift(ring_cells(3), c - 3), top + 1, top + 4, block(glass)))
    ops.append(carve_region([c, top + 1, c + 3], [c + 1, top + 3, c + 4]))
    ops.append(place_block([c, top + 1, c], block("minecraft:gold_block")))
    ops.append(place_block([c, top + 2, c], block("minecraft:sea_lantern")))
    ops.append(place_block([c, top + 3, c], block("minecraft:glowstone")))
    # cap
    ops.append(at([c - 3, top + 4, c - 3], Dome(3, material=roof)))
    ops.append(place_block([c, top + 8, c], block("minecraft:lightning_rod")))
    # keeper's corner on the ground floor
    ops.append(place_block([c + 1, 1, c - 1], block("minecraft:lantern"), phase="fixture"))
    ops.append(at([c - 1, 1, c + 1], Chest(items=["minecraft:cod", "minecraft:fishing_rod", "minecraft:spyglass"])))
    return component(name="Lighthouse",
                     props={"height": height},
                     min_size=[11, top + 9, 11], body=group(ops))


def build(height=22, depth=5, seed=1):
    """Lighthouse on a rock islet. Walking plane is the rock top (ground_level=depth);
    place it in shallow water so the rocks break the surface."""
    lh = Lighthouse(height)
    return component(name="LighthouseIslet", props={"height": height, "depth": depth, "seed": seed},
                     min_size=[15, depth + height + 10, 15], metadata={"ground_level": depth},
                     body=group([
                         RockIsland(7, depth, seed),
                         at([2, depth, 2], lh),
                         fill_region([6, depth, 11], [9, depth + 1, 12], block("minecraft:stone_bricks")),
                         fill_region([6, depth, 12], [9, depth + 1, 13], block("minecraft:stone_brick_slab")),
                     ]))
