load("../library/cheese_wheel/v1.star", "CheeseWheel")
load("../lib/fixtures.star", "LanternPost", "Sign")

def build():
    parts = [
        fill_region([0, 0, 9], [25, 1, 11], block("minecraft:dirt_path")),
        transform([0, 0, 0], 0, [9, 8, 9], CheeseWheel(4, 7)),
        transform([10, 0, 2], 0, [7, 6, 7], CheeseWheel(3, 5)),
        transform([18, 0, 4], 0, [5, 5, 5], CheeseWheel(2, 4)),
        transform([2, 1, 9], 0, [1, 4, 1], LanternPost()),
        transform([14, 1, 9], 0, [1, 4, 1], LanternPost()),
        transform([22, 1, 9], 0, [1, 4, 1], LanternPost()),
        transform([8, 1, 10], 0, [1, 1, 1], Sign(["", "Cheese Row", "(library test)", ""], color="orange", glowing=True)),
    ]
    return component(name="CheeseRow", props={}, min_size=[25, 9, 11],
                     metadata={"ground_level": 1}, body=group(parts))
