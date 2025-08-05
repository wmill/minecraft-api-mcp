import nbtlib
from nbtlib import schema
from nbtlib.tag import Compound, List, String, Int, IntArray

# Define the NBT structure schema
Structure = schema('Structure', {
    'DataVersion': Int,
    'size': List[Int],
    'palette': List[Compound],
    'blocks': List[Compound],
    'entities': List[Compound]
})

# Initialize the block palette
palette = List([
    Compound({
        'Name': String('minecraft:stone')
    })
])

# Initialize the blocks list explicitly as List[Compound]
blocks = List[Compound]()

# Add stone blocks (3x4x1)
for x in range(3):
    for y in range(4):
        for z in range(1):
            block = Compound({
                'pos': List[Int]([Int(x), Int(y), Int(z)]),
                'state': Int(0)  # Stone block
            })
            blocks.append(block)

# Create the structure NBT
structure = Structure({
    'DataVersion': Int(4002),  # Minecraft 1.21.7 data version
    'size': List[Int]([Int(3), Int(4), Int(1)]),  # 3 wide, 4 high, 1 deep
    'palette': palette,
    'blocks': blocks,
    'entities': List[Compound]([])  # No entities
})

# Save to NBT file with error handling
try:
    nbt_file = nbtlib.File(structure)
    nbt_file.save('stone_wall.nbt', gzipped=False)
    print("NBT file saved successfully as 'stone_wall.nbt'")
except Exception as e:
    print(f"Error saving NBT file: {e}")