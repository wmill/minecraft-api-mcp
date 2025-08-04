import nbtlib
from nbtlib import schema
from nbtlib.tag import Compound, List, String, Int, IntArray

# Define the NBT structure schema
Structure = schema('Structure', {
    'DataVersion': Int,
    'size': IntArray,
    'palette': List[Compound],
    'blocks': List[Compound],
    'entities': List[Compound]
})

# Initialize the block palette
palette = List([
    Compound({
        'Name': String('minecraft:stone')
    }),
    Compound({
        'Name': String('minecraft:oak_door'),
        'Properties': Compound({
            'facing': String('north'),
            'half': String('lower'),
            'hinge': String('left'),
            'open': String('false')
        })
    }),
    Compound({
        'Name': String('minecraft:oak_door'),
        'Properties': Compound({
            'facing': String('north'),
            'half': String('upper'),
            'hinge': String('left'),
            'open': String('false')
        })
    })
])

# Initialize the blocks list explicitly as List[Compound]
blocks = List[Compound]()

# Add stone blocks (3x4x1, excluding door positions)
for x in range(3):
    for y in range(4):
        for z in range(1):
            if x == 1 and y in (1, 2) and z == 0:
                continue  # Skip door positions
            block = Compound({
                'pos': IntArray([x, y, z]),
                'state': Int(0)  # Stone block
            })
            blocks.append(block)

# Add oak door blocks
blocks.append(Compound({
    'pos': IntArray([1, 1, 0]),
    'state': Int(1)  # Lower door
}))
blocks.append(Compound({
    'pos': IntArray([1, 2, 0]),
    'state': Int(2)  # Upper door
}))

# Create the structure NBT
structure = Structure({
    'DataVersion': Int(3953),  # Minecraft 1.21 data version
    'size': IntArray([3, 4, 1]),  # 3 wide, 4 high, 1 deep
    'palette': palette,
    'blocks': blocks,
    'entities': List[Compound]([])  # No entities
})

# Save to NBT file with error handling
try:
    nbt_file = nbtlib.File(structure)
    nbt_file.save('stone_wall_with_door.nbt')
    print("NBT file saved successfully as 'stone_wall_with_door.nbt'")
except Exception as e:
    print(f"Error saving NBT file: {e}")