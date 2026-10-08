
load("../lib/outdoor.star", "Well", "MarketStall", "Pergola", "FlowerBed", "RoundTree")
load("../lib/fixtures.star", "Bench", "LanternPost")

def build():
    children = [
        fill_region([15, 0, 0], [19, 1, 10], block("minecraft:dirt_path")),
        fill_region([15, 0, 18], [19, 1, 28], block("minecraft:dirt_path")),
        fill_region([0, 0, 12], [13, 1, 16], block("minecraft:dirt_path")),
        fill_region([21, 0, 12], [34, 1, 16], block("minecraft:dirt_path")),
        fill_region([13, 0, 10], [21, 1, 18], block("minecraft:cobblestone")),
        transform([15, 1, 12], 0, [3, 4, 3], Well(material="minecraft:cobblestone", post="minecraft:spruce_fence", roof="minecraft:spruce_slab")),
        transform([2, 1, 3], 0, [5, 4, 3], MarketStall(canopy="minecraft:green_wool", accent="minecraft:white_wool", post="minecraft:spruce_fence")),
        transform([27, 1, 3], 0, [5, 4, 3], MarketStall(canopy="minecraft:yellow_wool", accent="minecraft:white_wool", post="minecraft:oak_fence")),
        transform([2, 1, 22], 180, [5, 4, 3], MarketStall(canopy="minecraft:red_wool", accent="minecraft:white_wool", post="minecraft:dark_oak_fence")),
        transform([26, 1, 20], 0, [6, 5, 6], Pergola(width=6, depth=6, height=4, post="minecraft:stripped_spruce_log", beam="minecraft:spruce_log", slat="minecraft:spruce_slab")),
        transform([5, 1, 8], 90, [4, 1, 1], Bench(length=4, stair="minecraft:spruce_stairs")),
        transform([28, 1, 9], 270, [4, 1, 1], Bench(length=4, stair="minecraft:spruce_stairs")),
        transform([4, 1, 18], 0, [5, 2, 4], FlowerBed(width=5, length=4, flower_a="minecraft:poppy", flower_b="minecraft:cornflower", border="minecraft:cobblestone")),
        transform([20, 1, 21], 0, [5, 2, 4], FlowerBed(width=5, length=4, flower_a="minecraft:azure_bluet", flower_b="minecraft:dandelion", border="minecraft:mossy_cobblestone")),
        transform([9, 1, 21], 0, [5, 8, 5], RoundTree(trunk_height=7, log="minecraft:oak_log", leaves="minecraft:oak_leaves")),
        transform([2, 1, 9], 0, [1, 5, 1], LanternPost(height=4, post="minecraft:spruce_fence", lantern="minecraft:lantern")),
        transform([31, 1, 9], 0, [1, 5, 1], LanternPost(height=4, post="minecraft:spruce_fence", lantern="minecraft:lantern")),
        transform([2, 1, 18], 0, [1, 5, 1], LanternPost(height=4, post="minecraft:spruce_fence", lantern="minecraft:lantern")),
        transform([31, 1, 18], 0, [1, 5, 1], LanternPost(height=4, post="minecraft:spruce_fence", lantern="minecraft:lantern")),
        place_block([17, 1, 3], block("minecraft:oak_sign", {"rotation": "8"}, nbt=sign_nbt(lines=["Southwatch", "Market Green", "Trade & Rest", "Welcome"], color="dark_green", glowing=True)), phase="fixture"),
    ]
    return component(name="SouthwatchMarketGreen", props={}, min_size=[34, 9, 28], metadata={"ground_level": 1}, body=group(children))
