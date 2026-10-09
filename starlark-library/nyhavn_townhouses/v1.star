load("../lib/roofs.star", "GableRoof")
load("../lib/openings.star", "SingleDoor")
load("../lib/fixtures.star", "Ladder", "Bed", "Chest", "Table", "Chair", "LanternPost")


def NyhavnHouse(color="minecraft:orange_terracotta", floors=3, width=5, depth=7,
                trim="minecraft:white_concrete", roof="minecraft:brick_stairs",
                ridge="minecraft:bricks", door="minecraft:dark_oak_door"):
    """Copenhagen-harbour townhouse, width x (4*floors + (width+1)//2 + 2) x depth.
    Street gable faces south; white string courses at each floor, 1x2 windows
    every other column, a door at width//2, brick gable roof and chimney. Inside:
    plank floors joined by a ladder on the north wall, a bed, chest and lantern on
    each storey. Floor at local y=0. width must be odd (>=5)."""
    top = 4 * floors
    ops = [
        fill_region([0, 0, 0], [width, 1, depth], block("minecraft:stone_bricks")),
        fill_region([1, 1, 0], [width - 1, top, 1], block(color)),
        fill_region([0, 1, 0], [1, top, depth], block(color)),
        fill_region([width - 1, 1, 0], [width, top, depth], block(color)),
        at([0, top, 0], GableRoof(width, depth, stair=roof, ridge=ridge, gable=color)),
    ]
    for y in range(1, top):
        m = trim if y % 4 == 0 else color
        ops.append(fill_region([1, y, depth - 1], [width - 1, y + 1, depth], block(m)))
    for f in range(1, floors):
        y = 4 * f
        ops.append(fill_region([1, y, 1], [width - 1, y + 1, depth - 1], block("minecraft:spruce_planks")))
        ops.append(carve_region([width // 2, y, 1], [width // 2 + 1, y + 1, 2]))
    ops.append(at([width // 2, 1, 1], Ladder(top - 1)))
    # street-face windows (1x2, every other column) and side windows
    for f in range(floors):
        y0 = 4 * f + 1
        for x in range(1, width - 1, 2):
            if f == 0 and x == width // 2:
                continue
            ops.append(carve_region([x, y0 + 1, depth - 1], [x + 1, y0 + 3, depth]))
            ops.append(place_block([x, y0 + 1, depth - 1], block("minecraft:glass_pane"), phase="fixture"))
            ops.append(place_block([x, y0 + 2, depth - 1], block("minecraft:glass_pane"), phase="fixture"))
        # side windows
        ops.append(carve_region([0, y0 + 1, depth // 2], [1, y0 + 3, depth // 2 + 1]))
        ops.append(carve_region([width - 1, y0 + 1, depth // 2], [width, y0 + 3, depth // 2 + 1]))
        for x in [0, width - 1]:
            ops.append(place_block([x, y0 + 1, depth // 2], block("minecraft:glass_pane"), phase="fixture"))
            ops.append(place_block([x, y0 + 2, depth // 2], block("minecraft:glass_pane"), phase="fixture"))
        ops.append(place_block([width // 2, 4 * f + 3, depth // 2], block("minecraft:lantern", {"hanging": "true"}), phase="fixture"))
        if f > 0:
            ops.append(at([1, 4 * f + 1, depth - 3], Bed(material="minecraft:white_bed")))
            ops.append(at([width - 2, 4 * f + 1, depth - 2], Chest(), rotation=180))
    ops.append(at([width // 2, 1, depth - 1], SingleDoor(material=door)))
    ops.append(at([1, 1, 2], Table()))
    ops.append(at([1, 1, 3], Chair(), rotation=180))
    # chimney through the back of the roof
    ops.append(fill_region([1, top + 2, 1], [2, top + (width + 1) // 2 + 2, 2], block(ridge)))
    return component(name="NyhavnHouse",
                     props={"floors": floors, "width": width, "depth": depth},
                     min_size=[width, top + (width + 1) // 2 + 2, depth],
                     body=group(ops))


_PALETTE = [
    ["minecraft:orange_terracotta", 3, 5],
    ["minecraft:light_blue_terracotta", 4, 5],
    ["minecraft:yellow_terracotta", 3, 7],
    ["minecraft:red_terracotta", 4, 5],
    ["minecraft:cyan_terracotta", 3, 5],
    ["minecraft:white_terracotta", 2, 7],
    ["minecraft:pink_terracotta", 3, 5],
]


def NyhavnRow(count=6, depth=7, quay=5):
    """A terrace of `count` NyhavnHouse fronts in rotating colours, heights and
    widths, all facing a cobbled quay (`quay` deep, toward +Z) with bollards and
    lantern posts. Size [sum widths, 21, depth+quay]; floor at local y=0."""
    ops = []
    x = 0
    for i in range(count):
        p = _PALETTE[i % len(_PALETTE)]
        ops.append(at([x, 0, 0], NyhavnHouse(p[0], p[1], p[2], depth)))
        x += p[2]
    total = x
    ops.append(fill_region([0, 0, depth], [total, 1, depth + quay - 1], block("minecraft:cobblestone")))
    ops.append(fill_region([0, 0, depth + quay - 1], [total, 1, depth + quay], block("minecraft:stone_bricks")))
    for bx in range(2, total - 4, 6):
        ops.append(place_block([bx, 1, depth + quay - 1], block("minecraft:polished_deepslate_wall"), phase="fixture"))
        ops.append(at([bx + 3, 1, depth + 1], LanternPost(3, post="minecraft:dark_oak_fence")))
    return component(name="NyhavnRow", props={"count": count, "depth": depth, "quay": quay},
                     min_size=[total, 21, depth + quay], body=group(ops))


def build(count=6):
    """Terrace of six harbour townhouses facing a quay to the south. ground_level 1."""
    total = 0
    for i in range(count):
        total += _PALETTE[i % len(_PALETTE)][2]
    return component(name="NyhavnQuay", props={"count": count}, min_size=[total, 21, 12],
                     metadata={"ground_level": 1}, body=NyhavnRow(count))
