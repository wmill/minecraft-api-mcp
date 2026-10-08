
load("../lib/structural.star", "Foundation", "Floor")
load("../lib/openings.star", "Window", "Archway")
load("../lib/fixtures.star", "Barrel", "Chest", "LanternPost")

def _smithy():
    children = [
        Foundation(20, 16, depth=1, material="minecraft:cobblestone"),
        transform([7, 1, 0], 0, [13, 1, 16], Floor(13, 16, material="minecraft:polished_andesite")),
        fill_region([7, 2, 0], [20, 7, 1], block("minecraft:stone_bricks")),
        fill_region([7, 2, 15], [20, 7, 16], block("minecraft:stone_bricks")),
        fill_region([19, 2, 1], [20, 7, 15], block("minecraft:stone_bricks")),
        fill_region([7, 2, 1], [8, 7, 5], block("minecraft:cobblestone")),
        fill_region([7, 2, 11], [8, 7, 15], block("minecraft:cobblestone")),
        transform([7, 2, 5], 90, [6, 5, 1], Archway(width=6, height=5, stair="minecraft:stone_brick_stairs")),
        transform([10, 3, 0], 180, [3, 2, 1], Window(width=3, height=2, pane="minecraft:iron_bars")),
        transform([10, 3, 15], 0, [3, 2, 1], Window(width=3, height=2, pane="minecraft:glass_pane")),
        fill_region([7, 7, 0], [20, 8, 2], block("minecraft:dark_oak_slab", {"type": "top"})),
        fill_region([7, 7, 2], [16, 8, 5], block("minecraft:dark_oak_slab", {"type": "top"})),
        fill_region([19, 7, 2], [20, 8, 5], block("minecraft:dark_oak_slab", {"type": "top"})),
        fill_region([7, 7, 5], [20, 8, 16], block("minecraft:dark_oak_slab", {"type": "top"})),
        fill_region([16, 2, 2], [19, 13, 5], block("minecraft:stone_bricks")),
        place_block([17, 13, 3], block("minecraft:campfire", {"lit": "true"}), phase="fixture"),
        fill_region([0, 1, 3], [7, 2, 13], block("minecraft:cobblestone")),
        fill_region([1, 2, 4], [2, 6, 5], block("minecraft:stripped_spruce_log")),
        fill_region([1, 2, 11], [2, 6, 12], block("minecraft:stripped_spruce_log")),
        fill_region([5, 2, 4], [6, 6, 5], block("minecraft:stripped_spruce_log")),
        fill_region([5, 2, 11], [6, 6, 12], block("minecraft:stripped_spruce_log")),
        fill_region([1, 6, 4], [6, 7, 12], block("minecraft:spruce_slab", {"type": "top"})),
        place_block([2, 2, 6], block("minecraft:anvil", {"facing": "south"}), phase="fixture"),
        place_block([4, 2, 6], block("minecraft:grindstone", {"face": "floor", "facing": "south"}), phase="fixture"),
        place_block([2, 2, 9], block("minecraft:smithing_table"), phase="fixture"),
        place_block([4, 2, 9], block("minecraft:blast_furnace", {"facing": "west", "lit": "false"}), phase="fixture"),
        place_block([6, 2, 8], block("minecraft:cauldron"), phase="fixture"),
        place_block([9, 2, 3], block("minecraft:furnace", {"facing": "west", "lit": "false"}), phase="fixture"),
        place_block([10, 2, 3], block("minecraft:blast_furnace", {"facing": "west", "lit": "false"}), phase="fixture"),
        transform([9, 2, 12], 0, [1, 1, 1], Barrel(items=["minecraft:coal", "minecraft:charcoal", "minecraft:raw_iron"])),
        transform([11, 2, 12], 0, [1, 1, 1], Chest(items=["minecraft:iron_ingot", "minecraft:iron_pickaxe", "minecraft:iron_axe"])),
        transform([2, 2, 2], 0, [1, 5, 1], LanternPost(height=4, post="minecraft:spruce_fence", lantern="minecraft:lantern")),
        transform([5, 2, 13], 0, [1, 5, 1], LanternPost(height=4, post="minecraft:spruce_fence", lantern="minecraft:lantern")),
        place_block([6, 4, 12], block("minecraft:oak_wall_sign", {"facing": "west"}, nbt=sign_nbt(lines=["Ash & Anvil", "Smithy", "Repairs & Tools"], color="black", glowing=True)), phase="fixture"),
        place_block([13, 6, 8], block("minecraft:lantern", {"hanging": "true"}), phase="fixture"),
    ]
    children.extend([
        fill_region([2, 1, 2], [3, 2, 3], block("minecraft:cobblestone")),
        fill_region([5, 1, 13], [6, 2, 14], block("minecraft:cobblestone")),
    ])
    return component(name="AshAndAnvilSmithy", props={}, min_size=[20, 14, 16], body=group(children))

# South-facing forge entrance and walking plane at local Y=2.
def AshAndAnvilSmithy():
    return component(name="AshAndAnvilSmithySouth", props={}, min_size=[16, 14, 20], body=at([0, 0, 0], _smithy(), rotation=270))

def build():
    return component(name="AshAndAnvilSmithyBuild", props={}, min_size=[16,14,20], metadata={"ground_level": 2}, body=AshAndAnvilSmithy())
