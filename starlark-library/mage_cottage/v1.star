load("../lib/dwellings.star", "GuestHouse")
load("../lib/fixtures.star", "Sign")

STYLES = [
    {"log": "minecraft:dark_oak_log", "infill": "minecraft:purpur_block", "door": "minecraft:crimson_door",
     "roof_stair": "minecraft:purpur_stairs", "roof_ridge": "minecraft:amethyst_block", "bed": "minecraft:purple_bed"},
    {"log": "minecraft:warped_stem", "infill": "minecraft:calcite", "door": "minecraft:warped_door",
     "roof_stair": "minecraft:warped_stairs", "roof_ridge": "minecraft:stripped_warped_stem", "bed": "minecraft:cyan_bed"},
    {"log": "minecraft:cherry_log", "infill": "minecraft:white_concrete", "door": "minecraft:cherry_door",
     "roof_stair": "minecraft:cherry_stairs", "roof_ridge": "minecraft:cherry_log", "bed": "minecraft:pink_bed"},
    {"log": "minecraft:stripped_dark_oak_log", "infill": "minecraft:amethyst_block", "door": "minecraft:dark_oak_door",
     "roof_stair": "minecraft:deepslate_tile_stairs", "roof_ridge": "minecraft:purpur_pillar", "bed": "minecraft:magenta_bed"},
]

def build(style=0, name="Mage", title="Enchanter"):
    s = STYLES[style]
    w, d = 7, 6
    h = 1 + 4 + (w + 1) // 2
    parts = [
        transform([0, 0, 0], 0, [w, h, d], GuestHouse(width=w, depth=d, wall_height=4, log=s["log"], infill=s["infill"],
                  door=s["door"], roof_stair=s["roof_stair"], roof_ridge=s["roof_ridge"], bed=s["bed"])),
        fill_region([3, 0, 6], [4, 1, 8], block("minecraft:calcite")),
    ]
    for x in [0, 6]:
        parts.append(fill_region([x, 1, 7], [x + 1, 3, 8], block("minecraft:polished_blackstone_wall", {"up": "true"})))
        parts.append(place_block([x, 3, 7], block("minecraft:pearlescent_froglight")))
        parts.append(place_block([x, 4, 7], block("minecraft:amethyst_cluster", {"facing": "up"}), phase="fixture"))
    parts.append(transform([1, 1, 7], 0, [1, 1, 1], Sign(lines=["~ " + name + " ~", title, "", "Arcanum"],
                 material="minecraft:crimson_sign", color="purple", glowing=True)))
    return component(name="MageCottage", props={"style": style}, min_size=[w, h, d + 2],
                     metadata={"ground_level": 1}, body=group(parts))
