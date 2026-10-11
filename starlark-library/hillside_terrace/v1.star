load("../lib/shapes.star", "RoundedCuboid", "fill_cells")

# Hillside terrace: grass-capped dirt embankment below the walking plane plus a
# rounded-cuboid scoop carved out of the slope above it.
#
# Local layout (ground_level = fill_height + 1 = the walking plane):
#   y in [0, fill_height)            dirt body, bottom-rounded
#   y = fill_height                  grass cap (radius-1 rounded rectangle)
#   y in [fill_height+1, +carve_height)  air scoop, bottom-rounded
# Flat usable floor: central (width-2r) x (length-2r) rectangle at the walking plane.
# Place the terrace first and build on it in a separate placement; the carve
# phase erases structures inside its volume.


def rounded_rect_cells(w, l, r):
    cells = []
    for x in range(w):
        for z in range(l):
            dx = max(r - x, 0, x - (w - 1 - r))
            dz = max(r - z, 0, z - (l - 1 - r))
            if dx * dx + dz * dz <= r * r + r:
                cells.append([x, z])
    return cells


def HillsideTerrace(width, length, radius=6, fill_height=13, carve_height=14):
    cap_r = max(radius - 1, 0)
    cap = group(fill_cells(rounded_rect_cells(width, length, cap_r),
                           fill_height, fill_height + 1,
                           block("minecraft:grass_block")))
    parts = [
        RoundedCuboid(width, fill_height, length, radius=radius,
                      material="minecraft:dirt", rounding="bottom"),
        cap,
        at([0, fill_height + 1, 0],
           RoundedCuboid(width, carve_height, length, radius=radius,
                         material="minecraft:air", rounding="bottom")),
    ]
    return component(
        name="HillsideTerrace",
        props={"width": width, "length": length, "radius": radius,
               "fill_height": fill_height, "carve_height": carve_height},
        min_size=[width, fill_height + 1 + carve_height, length],
        metadata={"ground_level": fill_height + 1},
        body=group(parts),
    )


def build(width=31, length=25, radius=6, fill_height=13, carve_height=14):
    return HillsideTerrace(width, length, radius, fill_height, carve_height)
