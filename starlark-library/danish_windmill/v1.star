load("../lib/shapes.star", "ring_cells", "disc_cells", "fill_cells")
load("../lib/roofs.star", "ConeRoof")
load("../lib/openings.star", "SingleDoor")
load("../lib/fixtures.star", "Ladder", "Barrel", "Sign", "LanternPost")
load("../lib/outdoor.star", "CropPlot", "HayBaleStack")


def _shift(cells, dx, dz):
    return [[c[0] + dx, c[1] + dz] for c in cells]


def _sails(cx, hy, z, arm, sail, length=8):
    """Four pinwheel sails in the X-Y plane at depth z, hub at (cx, hy)."""
    ops = []
    log_v = block(arm, {"axis": "y"})
    log_h = block(arm, {"axis": "x"})
    ops.append(place_block([cx, hy, z], block(arm, {"axis": "z"})))
    for i in range(1, length + 1):
        ops.append(place_block([cx, hy + i, z], log_v))
        ops.append(place_block([cx, hy - i, z], log_v))
        ops.append(place_block([cx + i, hy, z], log_h))
        ops.append(place_block([cx - i, hy, z], log_h))
    for i in range(2, length + 1):
        m = "minecraft:spruce_planks" if i % 3 == 0 else sail
        for w in [1, 2]:
            ops.append(place_block([cx + w, hy + i, z], block(m)))   # up arm, east side
            ops.append(place_block([cx + i, hy - w, z], block(m)))   # east arm, below
            ops.append(place_block([cx - w, hy - i, z], block(m)))   # down arm, west side
            ops.append(place_block([cx - i, hy + w, z], block(m)))   # west arm, above
    return group(ops)


def Windmill(base="minecraft:white_terracotta", upper="minecraft:dark_oak_planks",
             roof_stair="minecraft:dark_oak_stairs", roof_cap="minecraft:dark_oak_planks",
             sail="minecraft:white_wool", arm="minecraft:stripped_spruce_log",
             deck="minecraft:spruce_slab", rail="minecraft:spruce_fence"):
    """Danish gallery windmill, 17x21x12. Door faces south at (8,1,9); the sails
    turn in the plane z=11 in front of the door. Floor is local y=0."""
    cx = 8
    cz = 5
    parts = []
    # floor + lower body (radius 4) with a gallery (radius 5) at y=6
    parts.extend(fill_cells(_shift(disc_cells(3), cx - 3, cz - 3), 0, 1, block("minecraft:spruce_planks")))
    parts.extend(fill_cells(_shift(ring_cells(4), cx - 4, cz - 4), 0, 7, block(base)))
    parts.extend(fill_cells(_shift(disc_cells(3), cx - 3, cz - 3), 6, 7, block("minecraft:spruce_planks")))
    parts.extend(fill_cells(_shift(ring_cells(5), cx - 5, cz - 5), 6, 7, block(deck, {"type": "top"})))
    parts.extend(fill_cells(_shift(ring_cells(5), cx - 5, cz - 5), 7, 8, block(rail), phase="fixture"))
    # upper body (radius 3) and cone cap
    parts.extend(fill_cells(_shift(ring_cells(3), cx - 3, cz - 3), 7, 13, block(upper)))
    parts.append(at([cx - 3, 13, cz - 3], ConeRoof(3, stair=roof_stair, cap=roof_cap, steep=True)))
    # doors: ground and gallery
    parts.append(at([cx, 1, cz + 4], SingleDoor(material="minecraft:spruce_door")))
    parts.append(at([cx, 7, cz + 3], SingleDoor(material="minecraft:spruce_door")))
    carve_cells = [[cx, 7, cz + 4], [cx, 8, cz + 4], [cx, 7, cz + 5]]
    for c in carve_cells:
        parts.append(carve_region(c, [c[0] + 1, c[1] + 1, c[2] + 1]))
    # windows
    for p in [[cx - 4, 3, cz], [cx + 4, 3, cz], [cx, 3, cz - 4], [cx - 3, 9, cz], [cx + 3, 9, cz], [cx, 9, cz - 3]]:
        parts.append(carve_region(p, [p[0] + 1, p[1] + 2, p[2] + 1]))
        parts.append(place_block(p, block("minecraft:glass_pane"), phase="fixture"))
        parts.append(place_block([p[0], p[1] + 1, p[2]], block("minecraft:glass_pane"), phase="fixture"))
    # ladder through a hatch in the loft floor
    parts.append(carve_region([cx, 6, cz - 2], [cx + 1, 7, cz - 1]))
    parts.append(at([cx, 1, cz - 2], Ladder(6)))
    # interior: grindstone "millstone", flour barrels, lantern
    parts.append(place_block([cx, 1, cz], block("minecraft:grindstone", {"face": "floor", "facing": "south"}), phase="fixture"))
    parts.append(at([cx - 2, 1, cz - 1], Barrel(items=["minecraft:wheat", "minecraft:bread"])))
    parts.append(at([cx + 2, 1, cz - 1], Barrel(items=["minecraft:wheat"])))
    parts.append(place_block([cx - 2, 1, cz + 1], block("minecraft:hay_block"), phase="fixture"))
    parts.append(place_block([cx, 5, cz], block("minecraft:lantern", {"hanging": "true"}), phase="fixture"))
    parts.append(place_block([cx, 11, cz], block("minecraft:lantern", {"hanging": "true"}), phase="fixture"))
    # axle and sails
    parts.append(place_block([cx, 12, cz + 4], block("minecraft:dark_oak_log", {"axis": "z"})))
    parts.append(place_block([cx, 12, cz + 5], block("minecraft:dark_oak_log", {"axis": "z"})))
    parts.append(_sails(cx, 12, cz + 6, arm, sail))
    return component(name="Windmill", props={}, min_size=[17, 21, 12], body=group(parts))


def build(fields=True):
    """Windmill between two wheat fields with hay and a sign. Walking plane y=1."""
    w = 35 if fields else 17
    ox = 9 if fields else 0
    parts = [
        at([ox, 0, 0], Windmill()),
        fill_region([ox + 7, 0, 12], [ox + 10, 1, 16], block("minecraft:dirt_path")),
        at([ox + 11, 1, 13], Sign(lines=["", "Cheesedanish", "Mølle", ""], glowing=True)),
    ]
    if fields:
        parts.append(at([0, 0, 1], CropPlot(8, 13)))
        parts.append(at([w - 8, 0, 1], CropPlot(8, 13, crop="minecraft:carrots")))
        parts.append(at([ox, 1, 13], HayBaleStack(3, 2, 2)))
        parts.append(at([ox + 13, 1, 13], LanternPost(3)))
    return component(name="WindmillFarm", props={"fields": fields}, min_size=[w, 22, 16],
                     metadata={"ground_level": 1}, body=group(parts))
