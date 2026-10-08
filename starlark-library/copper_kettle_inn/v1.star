
load("../lib/structural.star", "Foundation", "Floor", "TimberFrameWall")
load("../lib/openings.star", "SingleDoor", "ShutteredWindow")
load("../lib/roofs.star", "GableRoof")
load("../lib/fixtures.star", "DiningTable", "Chair", "KitchenCounter", "Fireplace", "Bed", "Chest", "Barrel", "BookshelfWall", "Carpet", "Ladder")

def build():
    w = 23
    l = 15
    children = [
        Foundation(w, l, depth=1, material="minecraft:cobblestone"),
        transform([0, 1, 0], 0, [w, 1, l], Floor(w, l, material="minecraft:spruce_planks")),
        transform([0, 2, 0], 0, [w, 5, 1], TimberFrameWall(w, 5, log="minecraft:dark_oak_log", infill="minecraft:white_terracotta")),
        transform([0, 2, l - 1], 0, [w, 5, 1], TimberFrameWall(w, 5, log="minecraft:dark_oak_log", infill="minecraft:white_terracotta")),
        transform([0, 2, 1], 90, [l - 2, 5, 1], TimberFrameWall(l - 2, 5, log="minecraft:dark_oak_log", infill="minecraft:cobblestone")),
        transform([w - 1, 2, 1], 90, [l - 2, 5, 1], TimberFrameWall(l - 2, 5, log="minecraft:dark_oak_log", infill="minecraft:cobblestone")),
        transform([0, 7, 0], 0, [w, 1, l], Floor(w, l, material="minecraft:dark_oak_planks")),
        transform([0, 8, 0], 0, [w, 4, 1], TimberFrameWall(w, 4, log="minecraft:dark_oak_log", infill="minecraft:yellow_terracotta")),
        transform([0, 8, l - 1], 0, [w, 4, 1], TimberFrameWall(w, 4, log="minecraft:dark_oak_log", infill="minecraft:yellow_terracotta")),
        transform([0, 8, 1], 90, [l - 2, 4, 1], TimberFrameWall(l - 2, 4, log="minecraft:dark_oak_log", infill="minecraft:white_terracotta")),
        transform([w - 1, 8, 1], 90, [l - 2, 4, 1], TimberFrameWall(l - 2, 4, log="minecraft:dark_oak_log", infill="minecraft:white_terracotta")),
        transform([0, 12, 0], 90, [15, 8, 23], GableRoof(15, 23, stair="minecraft:dark_oak_stairs", ridge="minecraft:spruce_log", gable="minecraft:white_terracotta")),
        transform([11, 2, 0], 180, [1, 2, 1], SingleDoor(material="minecraft:spruce_door")),
        transform([4, 3, 0], 180, [3, 2, 1], ShutteredWindow(width=1, height=2, glazing="minecraft:glass_pane", shutter="minecraft:spruce_trapdoor")),
        transform([16, 3, 0], 180, [3, 2, 1], ShutteredWindow(width=1, height=2, glazing="minecraft:glass_pane", shutter="minecraft:spruce_trapdoor")),
        transform([6, 3, l - 1], 0, [3, 2, 1], ShutteredWindow(width=1, height=2, glazing="minecraft:glass_pane", shutter="minecraft:spruce_trapdoor")),
        transform([15, 3, l - 1], 0, [3, 2, 1], ShutteredWindow(width=1, height=2, glazing="minecraft:glass_pane", shutter="minecraft:spruce_trapdoor")),
        transform([5, 9, 0], 180, [3, 2, 1], ShutteredWindow(width=1, height=2, glazing="minecraft:glass_pane", shutter="minecraft:spruce_trapdoor")),
        transform([15, 9, l - 1], 0, [3, 2, 1], ShutteredWindow(width=1, height=2, glazing="minecraft:glass_pane", shutter="minecraft:spruce_trapdoor")),
        carve_region([20, 7, 1], [21, 8, 3]),
        transform([20, 2, 1], 0, [1, 6, 1], Ladder(height=6, material="minecraft:ladder")),
        transform([2, 2, 2], 0, [3, 5, 1], Fireplace(height=5, material="minecraft:stone_bricks", fire="minecraft:campfire")),
        transform([8, 2, 5], 0, [5, 2, 1], DiningTable(length=5, leg="minecraft:spruce_fence", top="minecraft:spruce_slab")),
        transform([8, 2, 4], 180, [1, 1, 1], Chair(stair="minecraft:spruce_stairs")),
        transform([12, 2, 6], 0, [1, 1, 1], Chair(stair="minecraft:spruce_stairs")),
        transform([4, 2, 10], 0, [5, 2, 1], KitchenCounter(length=5, cabinet="minecraft:barrel", top="minecraft:polished_andesite_slab")),
        transform([10, 2, 11], 0, [1, 1, 1], Barrel(items=["minecraft:bread", "minecraft:baked_potato", "minecraft:sweet_berries"])),
        transform([18, 2, 11], 0, [1, 1, 1], Chest(items=["minecraft:emerald", "minecraft:map", "minecraft:compass"])),
        transform([3, 8, 3], 0, [1, 1, 2], Bed(material="minecraft:green_bed")),
        transform([7, 8, 3], 0, [1, 1, 2], Bed(material="minecraft:yellow_bed")),
        transform([11, 8, 9], 180, [1, 1, 2], Bed(material="minecraft:red_bed")),
        transform([16, 8, 9], 180, [1, 1, 2], Bed(material="minecraft:blue_bed")),
        transform([2, 8, 12], 0, [5, 2, 1], BookshelfWall(width=5, height=2)),
        transform([8, 8, 5], 0, [7, 1, 3], Carpet(width=7, length=3, material="minecraft:green_carpet")),
        place_block([6, 2, 11], block("minecraft:brewing_stand"), phase="fixture"),
        place_block([8, 2, 11], block("minecraft:loom"), phase="fixture"),
        place_block([11, 2, 1], block("minecraft:oak_sign", {"rotation": "8"}, nbt=sign_nbt(lines=["The Copper", "Kettle Inn", "Beds & Supper"], color="dark_red", glowing=True)), phase="fixture"),
        place_block([7, 6, 7], block("minecraft:lantern", {"hanging": "true"}), phase="fixture"),
        place_block([15, 6, 7], block("minecraft:lantern", {"hanging": "true"}), phase="fixture"),
    ]
    return component(name="CopperKettleInn", props={}, min_size=[23, 20, 15], metadata={"ground_level": 1}, body=group(children))
