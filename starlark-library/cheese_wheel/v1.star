load("../lib/shapes.star", "disc_cells", "ring_cells", "fill_cells")
load("../lib/openings.star", "SingleDoor")


def CheeseWheel(radius=4, height=6):
    d = 2 * radius + 1
    ring = ring_cells(radius)
    inner = [[c[0] + 1, c[1] + 1] for c in disc_cells(radius - 1)]
    parts = []
    parts.extend(fill_cells(inner, 0, 1, block("minecraft:spruce_planks")))
    parts.extend(fill_cells(ring, 0, 1, block("minecraft:orange_terracotta")))
    parts.extend(fill_cells(ring, height - 1, height, block("minecraft:orange_terracotta")))
    parts.extend(fill_cells(ring, height, height + 1, block("minecraft:orange_terracotta")))
    parts.extend(fill_cells(inner, height, height + 1, block("minecraft:yellow_concrete")))
    for c in ring:
        x = c[0]
        z = c[1]
        for y in range(1, height - 1):
            if (x * 5 + z * 3 + y * 7) % 9 == 0 and y > 1:
                m = "minecraft:yellow_stained_glass"
            elif (x * 3 + z * 5 + y * 11) % 7 == 0:
                m = "minecraft:yellow_terracotta"
            else:
                m = "minecraft:yellow_concrete"
            parts.append(place_block([x, y, z], block(m)))
    parts.append(transform([radius, 1, d - 1], 0, [1, 2, 1], SingleDoor()))
    return component(name="CheeseWheel", props={"radius": radius, "height": height},
                     min_size=[d, height + 1, d], body=group(parts))


def build():
    return component(name="CheeseWheelDemo", props={}, min_size=[9, 7, 9],
                     metadata={"ground_level": 0}, body=CheeseWheel(4, 6))
