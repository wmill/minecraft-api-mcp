
load("../lib/structural.star", "Foundation", "Floor")
load("../lib/openings.star", "Archway", "Window")
load("../lib/roofs.star", "GableRoof")
load("../lib/outdoor.star", "FenceRing", "HayBaleStack")
load("../lib/fixtures.star", "Barrel", "Chest", "LanternPost", "Ladder")

def build():
    children = [
        transform([0, 0, 0], 0, [13, 1, 17], Foundation(13, 17, depth=1, material="minecraft:cobblestone")),
        transform([0, 1, 0], 0, [13, 1, 17], Floor(13, 17, material="minecraft:spruce_planks")),
        fill_region([0, 2, 0], [13, 7, 1], block("minecraft:dark_oak_log")),
        fill_region([0, 2, 16], [13, 7, 17], block("minecraft:dark_oak_log")),
        fill_region([0, 2, 1], [1, 7, 16], block("minecraft:cobblestone")),
        fill_region([12, 2, 1], [13, 7, 6], block("minecraft:cobblestone")),
        fill_region([12, 2, 11], [13, 7, 16], block("minecraft:cobblestone")),
        transform([12, 2, 6], 90, [5, 5, 1], Archway(width=5, height=5, stair="minecraft:dark_oak_stairs")),
        transform([4, 3, 0], 180, [3, 2, 1], Window(width=3, height=2, pane="minecraft:glass_pane")),
        transform([7, 3, 16], 0, [3, 2, 1], Window(width=3, height=2, pane="minecraft:glass_pane")),
        transform([0, 7, 0], 0, [13, 7, 17], GableRoof(13, 17, stair="minecraft:spruce_stairs", ridge="minecraft:dark_oak_log", gable="minecraft:spruce_planks")),
        fill_region([1, 2, 5], [6, 3, 6], block("minecraft:spruce_fence")),
        fill_region([1, 2, 10], [6, 3, 11], block("minecraft:spruce_fence")),
        place_block([6, 2, 5], block("minecraft:spruce_fence_gate", {"facing": "south", "open": "false"}), phase="fixture"),
        place_block([6, 2, 10], block("minecraft:spruce_fence_gate", {"facing": "south", "open": "false"}), phase="fixture"),
        fill_region([1, 6, 1], [11, 7, 6], block("minecraft:spruce_planks")),
        carve_region([10, 6, 2], [11, 7, 4]),
        transform([10, 2, 2], 0, [1, 5, 1], Ladder(height=5, material="minecraft:ladder")),
        transform([2, 2, 12], 0, [4, 3, 3], HayBaleStack(width=4, height=3, depth=3)),
        transform([8, 2, 13], 0, [1, 1, 1], Barrel(items=["minecraft:wheat", "minecraft:apple", "minecraft:golden_carrot"])),
        transform([10, 2, 13], 0, [1, 1, 1], Chest(items=["minecraft:saddle", "minecraft:lead", "minecraft:iron_horse_armor"])),
        fill_region([14, 0, 1], [29, 1, 16], block("minecraft:coarse_dirt")),
        transform([14, 1, 1], 0, [15, 1, 15], FenceRing(width=15, length=15, fence="minecraft:spruce_fence", gate="minecraft:spruce_fence_gate")),
        fill_region([17, 1, 5], [20, 2, 6], block("minecraft:smooth_stone_slab", {"type": "bottom"})),
        place_block([17, 2, 5], block("minecraft:water_cauldron", {"level": "3"}), phase="fixture"),
        place_block([18, 2, 5], block("minecraft:water_cauldron", {"level": "3"}), phase="fixture"),
        place_block([19, 2, 5], block("minecraft:water_cauldron", {"level": "3"}), phase="fixture"),
        transform([23, 1, 9], 0, [3, 2, 2], HayBaleStack(width=3, height=2, depth=2)),
        transform([15, 1, 0], 0, [1, 5, 1], LanternPost(height=4, post="minecraft:spruce_fence", lantern="minecraft:lantern")),
        transform([27, 1, 0], 0, [1, 5, 1], LanternPost(height=4, post="minecraft:spruce_fence", lantern="minecraft:lantern")),
        place_block([13, 1, 8], block("minecraft:oak_sign", {"rotation": "12"}, nbt=sign_nbt(lines=["Wayfarer", "Stable", "Feed & Tack"], color="dark_green", glowing=True)), phase="fixture"),
        place_block([6, 6, 8], block("minecraft:lantern", {"hanging": "true"}), phase="fixture"),
    ]
    return component(name="WayfarerStable", props={}, min_size=[29, 14, 17], metadata={"ground_level": 1}, body=group(children))
