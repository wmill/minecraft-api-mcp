load("../library/arcane_observatory_tower/v1.star", "GlassDome")
load("../lib/openings.star", "SingleDoor")
load("../lib/fixtures.star", "Ladder")

def AstralArmillary(ring="minecraft:waxed_cut_copper", crystal="minecraft:amethyst_block"):
    cells = {}
    for a in range(9):
        for b in range(9):
            d = (a-4)*(a-4)+(b-4)*(b-4)
            if d <= 20 and d >= 12:
                cells[(a,b,4)] = ring
                cells[(4,a,b)] = ring
                cells[(a,4,b)] = ring
    cells[(4,4,4)] = "minecraft:sea_lantern"
    for y in [2,3,5,6]:
        cells[(4,y,4)] = crystal
    return component(name="AstralArmillary", props={}, min_size=[9,9,9],
        body=group([place_block(list(p), block(m)) for p,m in cells.items()]))

def AstralObservatory(stone="minecraft:calcite", trim="minecraft:polished_deepslate"):
    cells = {}
    # Deep, bevelled podium embeds safely on sloped terrain.
    for x in range(23):
        for z in range(23):
            if abs(x-11)+abs(z-11) <= 19:
                for y in range(8):
                    cells[(x,y,z)] = trim if y < 7 else "minecraft:smooth_stone"
    # South access walk reaches the component edge.
    for x in range(9,14):
        for z in range(17,23):
            for y in range(8):
                cells[(x,y,z)] = trim if y < 7 else "minecraft:chiseled_quartz_block"
    # Octagonal chamber; continuous wall columns and two floor levels.
    for x in range(6,17):
        for z in range(6,17):
            dx = abs(x-11)
            dz = abs(z-11)
            if dx+dz <= 8:
                for y in [7,19]:
                    cells[(x,y,z)] = trim
                if dx == 5 or dz == 5 or dx+dz == 8:
                    for y in range(8,24):
                        cells[(x,y,z)] = stone if y % 6 != 0 else "minecraft:waxed_cut_copper"
    # Four high amethyst lancets on cardinal faces.
    for x,z in [(6,11),(16,11),(11,6),(11,16)]:
        for y in range(13,17):
            cells[(x,y,z)] = "minecraft:purple_stained_glass"
    # Observation platform expands beyond the tower.
    for x in range(4,19):
        for z in range(4,19):
            if abs(x-11)+abs(z-11) <= 11:
                cells[(x,19,z)] = "minecraft:polished_deepslate"
                if abs(x-11)+abs(z-11) == 11 or x in [4,18] or z in [4,18]:
                    cells[(x,20,z)] = "minecraft:polished_deepslate_wall"
    # Four decorative buttresses and lit porch pillars.
    for x,z in [(7,7),(15,7),(7,15),(15,15)]:
        for y in range(8,19):
            cells[(x,y,z)] = "minecraft:waxed_cut_copper" if y in [11,17] else trim
    for x,z in [(7,19),(15,19),(3,11),(19,11)]:
        for y in range(8,11):
            cells[(x,y,z)] = trim
        cells[(x,11,z)] = "minecraft:amethyst_block"
        cells[(x,12,z)] = "minecraft:sea_lantern"
        cells[(x,13,z)] = "minecraft:waxed_cut_copper"
    # Furnished lower study, leaving a straight entry-to-ladder aisle.
    for x in range(8,11):
        cells[(x,8,8)] = "minecraft:bookshelf"
        cells[(x,9,8)] = "minecraft:bookshelf"
    cells[(14,8,9)] = "minecraft:cartography_table"
    cells[(14,8,13)] = "minecraft:lectern"
    cells[(8,8,13)] = "minecraft:enchanting_table"
    for x,z in [(9,10),(13,10),(9,14),(13,14)]:
        cells[(x,7,z)] = "minecraft:sea_lantern"
    # Ladder access through both decks, attached to the north wall.
    for y in range(8,24):
        cells.pop((11,y,7),None)
    cells.pop((11,19,7),None)
    ops = [place_block(list(p),block(m)) for p,m in cells.items()]
    # Clear the chamber and construction air explicitly, preserving all geometry.
    for x in range(23):
        for z in range(23):
            for y in range(8,24):
                if (x,y,z) not in cells:
                    ops.append(carve_region([x,y,z],[x+1,y+1,z+1]))
    # Upper porch access on south side; door carved independently.
    ops.append(at([11,8,16],SingleDoor("minecraft:dark_oak_door")))
    ops.append(carve_region([11,20,16],[12,23,17]))
    ops.append(at([11,8,7],Ladder(16)))
    ops.append(at([6,24,6],GlassDome(11,"minecraft:cyan_stained_glass")))
    ops.append(at([7,30,7],AstralArmillary()))
    # Sky-window light well and copper finial connecting armillary to dome.
    return component(name="AstralObservatory",props={},min_size=[23,39,23],body=group(ops))

def build():
    return component(name="WestFacingAstralObservatory",props={},min_size=[23,39,23],
        metadata={"ground_level":8},body=at([0,0,0],AstralObservatory(),rotation=90))
