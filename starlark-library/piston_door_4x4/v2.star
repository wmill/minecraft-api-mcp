# Circuit by Shaweyy, transcribed from the Dieux Litematica capture.
# https://www.reddit.com/r/redstone/comments/1v852k1/fast_and_compact_slimeless_4x4_piston_door_160/
# Each row runs west to east. Three rows per layer, from north to south.

_PALETTE = [
    block("minecraft:air"),
    block("minecraft:lever", {"face": "wall", "powered": "false", "facing": "north"}),
    block("minecraft:cyan_wool"),
    block("minecraft:observer", {"powered": "false", "facing": "east"}),
    block("minecraft:note_block", {"note": "0", "powered": "false", "instrument": "basedrum"}),
    block("minecraft:observer", {"powered": "false", "facing": "west"}),
    block("minecraft:redstone_wire", {"east": "side", "south": "none", "north": "up", "west": "none", "power": "0"}),
    block("minecraft:repeater", {"delay": "1", "powered": "false", "facing": "west", "locked": "false"}),
    block("minecraft:repeater", {"delay": "4", "powered": "false", "facing": "west", "locked": "false"}),
    block("minecraft:sticky_piston", {"facing": "up", "extended": "false"}),
    block("minecraft:repeater", {"delay": "4", "powered": "false", "facing": "east", "locked": "false"}),
    block("minecraft:repeater", {"delay": "1", "powered": "false", "facing": "east", "locked": "false"}),
    block("minecraft:redstone_wire", {"east": "none", "south": "none", "north": "up", "west": "side", "power": "0"}),
    block("minecraft:redstone_wire", {"east": "side", "south": "side", "north": "none", "west": "none", "power": "0"}),
    block("minecraft:redstone_wire", {"east": "side", "south": "none", "north": "none", "west": "side", "power": "0"}),
    block("minecraft:observer", {"powered": "false", "facing": "down"}),
    block("minecraft:redstone_wire", {"east": "none", "south": "side", "north": "side", "west": "none", "power": "0"}),
    block("minecraft:white_stained_glass"),
    block("minecraft:white_stained_glass"),
    block("minecraft:quartz_block"),
    block("minecraft:sticky_piston", {"facing": "east", "extended": "false"}),
    block("minecraft:sticky_piston", {"facing": "west", "extended": "false"}),
    block("minecraft:redstone_wire", {"east": "side", "south": "side", "north": "side", "west": "side", "power": "0"}),
    block("minecraft:iron_door", {"hinge": "left", "half": "lower", "powered": "false", "facing": "north", "open": "false"}),
    block("minecraft:iron_door", {"hinge": "left", "half": "upper", "powered": "false", "facing": "north", "open": "false"}),
    block("minecraft:iron_door", {"hinge": "right", "half": "lower", "powered": "false", "facing": "west", "open": "false"}),
    block("minecraft:observer", {"powered": "false", "facing": "up"}),
    block("minecraft:sticky_piston", {"facing": "down", "extended": "false"}),
    block("minecraft:iron_door", {"hinge": "left", "half": "lower", "powered": "false", "facing": "east", "open": "false"}),
    block("minecraft:observer", {"powered": "false", "facing": "south"}),
    block("minecraft:repeater", {"delay": "2", "powered": "false", "facing": "west", "locked": "false"}),
    block("minecraft:iron_door", {"hinge": "right", "half": "upper", "powered": "false", "facing": "west", "open": "false"}),
    block("minecraft:iron_door", {"hinge": "left", "half": "upper", "powered": "false", "facing": "east", "open": "false"}),
]

_ROWS = [
    [0, 0, 0, 0, 0, 1, 0, 0],
    [2, 2, 2, 3, 4, 4, 5, 2],
    [6, 7, 8, 9, 9, 10, 11, 12],
    [0, 0, 0, 0, 0, 0, 0, 0],
    [13, 14, 14, 2, 15, 0, 0, 16],
    [17, 15, 15, 9, 9, 15, 15, 17],
    [0, 0, 0, 0, 0, 0, 0, 0],
    [17, 0, 18, 18, 19, 18, 0, 17],
    [16, 20, 19, 19, 19, 19, 21, 16],
    [0, 0, 0, 0, 0, 0, 0, 0],
    [16, 18, 0, 0, 0, 0, 18, 16],
    [20, 19, 0, 0, 0, 0, 19, 21],
    [0, 0, 0, 0, 0, 0, 0, 0],
    [15, 18, 0, 0, 0, 0, 18, 15],
    [20, 19, 0, 0, 0, 0, 19, 21],
    [0, 0, 0, 0, 0, 0, 0, 0],
    [2, 18, 0, 0, 0, 0, 18, 2],
    [20, 19, 0, 0, 0, 0, 19, 21],
    [0, 0, 0, 0, 0, 0, 0, 0],
    [22, 19, 0, 0, 0, 0, 18, 22],
    [20, 19, 0, 0, 0, 0, 19, 21],
    [0, 0, 0, 0, 0, 0, 0, 0],
    [15, 23, 18, 18, 18, 18, 0, 15],
    [2, 20, 19, 19, 19, 19, 21, 2],
    [0, 0, 0, 0, 0, 0, 0, 0],
    [2, 24, 5, 2, 22, 2, 3, 2],
    [25, 26, 26, 27, 27, 26, 26, 28],
    [0, 0, 0, 0, 0, 0, 0, 0],
    [0, 29, 5, 30, 5, 7, 27, 0],
    [31, 5, 8, 27, 27, 10, 3, 32],
]

# Observers, in the order they must go in (local [x, y, z], facing). Pasting
# the whole door at once fires the circuit before it is complete, so stage 0
# builds the open door with glass in these cells (which also props up the
# repeaters until the observers arrive), and stage i + 1 swaps placeholder i
# for its observer. Placement installs the stages a few ticks apart.
_INSTALL_ORDER = [
    ([0, 5, 5], "down"),
    ([0, 8, 5], "down"),
    ([1, 2, 6], "down"),
    ([1, 10, 6], "west"),
    ([1, 9, 6], "up"),
    ([1, 10, 5], "south"),
    ([2, 2, 6], "down"),
    ([2, 9, 5], "west"),
    ([2, 9, 6], "up"),
    ([2, 10, 5], "west"),
    ([3, 1, 5], "east"),
    ([4, 2, 5], "down"),
    ([4, 10, 5], "west"),
    ([5, 2, 6], "down"),
    ([5, 9, 6], "up"),
    ([6, 1, 5], "west"),
    ([6, 2, 6], "down"),
    ([6, 9, 5], "east"),
    ([6, 10, 6], "east"),
    ([6, 9, 6], "up"),
    ([7, 5, 5], "down"),
    ([7, 8, 5], "down"),
]

_OBSERVER_STATES = [3, 5, 15, 26, 29]

def PistonDoor4x4():
    """Slimeless Java door, initially open. The north service pit holds the lever.

    Local walking plane y=4; passage x=2..5, y=4..7, z=5..6.
    Lever at [5, 1, 4], accessible from the north service steps.
    Staged: stage 0 is the door with glass observer placeholders, then one
    observer per stage (1..22) in _INSTALL_ORDER.
    """
    glass = block("minecraft:white_stained_glass")
    parts = [
        fill_region([0, 0, 4], [8, 1, 7], block("minecraft:quartz_block")),
    ]
    for row in range(len(_ROWS)):
        y = row // 3 + 1
        z = row % 3 + 4
        for x in range(8):
            state = _ROWS[row][x]
            if z == 4 and y == 3 and x < 5:
                parts.append(place_block([x, y, z], glass))
            elif state in _OBSERVER_STATES:
                parts.append(place_block([x, y, z], glass))
            elif state == 0:
                parts.append(carve_region([x, y, z], [x + 1, y + 1, z + 1]))
            else:
                phase = "fixture" if state in [1, 6, 7, 8, 10, 11, 12, 13, 14, 16, 22, 30] else "structure"
                parts.append(place_block([x, y, z], _PALETTE[state], phase=phase))

    for i in range(len(_INSTALL_ORDER)):
        pos, facing = _INSTALL_ORDER[i]
        observer = block("minecraft:observer", {"powered": "false", "facing": facing})
        parts.append(place_block(pos, observer, stage=i + 1))

    # Stained glass substitutes for the four top slabs in the reference
    # and preserves the upward-only dust routing.
    # Full-block steps down to the lever. Keep the main approach on either side.
    for z in range(4):
        floor = max(0, 3 - z)
        parts.append(fill_region([5, floor, z], [7, floor + 1, z + 1], block("minecraft:quartz_block")))
        parts.append(carve_region([5, floor + 1, z], [7, 7, z + 1]))
    return component(name="PistonDoor4x4", props={}, min_size=[8, 11, 7], body=group(parts))

def build():
    return component(
        name="PistonDoor4x4Demo",
        props={},
        min_size=[8, 11, 7],
        metadata={"ground_level": 4, "stage_delay_ticks": 2},
        body=PistonDoor4x4(),
    )
