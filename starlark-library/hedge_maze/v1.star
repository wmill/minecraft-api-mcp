load("../lib/random.star", "random_cycle", "random_number")
load("../lib/fixtures.star", "Chest", "Sign", "Bench")


def maze_passages(n, seed=0):
    """Perfect maze on an n x n cell grid via randomized Kruskal. Returns a dict
    of open passages keyed (i, j, "e") for cell (i,j)->(i+1,j) and (i, j, "s")
    for (i,j)->(i,j+1)."""
    cyc = random_cycle(seed * 131 + 7)
    edges = []
    for i in range(n):
        for j in range(n):
            if i + 1 < n:
                edges.append([random_number(cyc), i, j, "e"])
            if j + 1 < n:
                edges.append([random_number(cyc), i, j, "s"])
    edges = sorted(edges)
    parent = list(range(n * n))
    open_ = {}
    for e in edges:
        i = e[1]
        j = e[2]
        a = i * n + j
        b = (i + 1) * n + j if e[3] == "e" else i * n + j + 1
        ra = a
        for _ in range(n * n):
            if parent[ra] == ra:
                break
            ra = parent[ra]
        rb = b
        for _ in range(n * n):
            if parent[rb] == rb:
                break
            rb = parent[rb]
        if ra != rb:
            parent[ra] = rb
            open_[(i, j, e[3])] = True
    return open_


def GoldenCheese():
    """5x4x5 maze prize: gold-and-yellow cheese wedge on a quartz plinth with a
    cake, a reward chest and four lanterns."""
    ops = [
        fill_region([0, 0, 0], [5, 1, 5], block("minecraft:quartz_block")),
        fill_region([1, 1, 1], [4, 2, 4], block("minecraft:yellow_concrete")),
        fill_region([1, 2, 1], [3, 3, 4], block("minecraft:yellow_concrete")),
        place_block([1, 3, 1], block("minecraft:gold_block")),
        place_block([1, 3, 2], block("minecraft:gold_block")),
        place_block([1, 3, 3], block("minecraft:gold_block")),
        place_block([3, 2, 2], block("minecraft:cake"), phase="fixture"),
    ]
    for p in [[0, 0], [4, 0], [0, 4], [4, 4]]:
        ops.append(place_block([p[0], 1, p[1]], block("minecraft:lantern"), phase="fixture"))
    ops.append(at([2, 1, 4], Chest(items=["minecraft:cake", "minecraft:golden_apple", "minecraft:diamond", "minecraft:firework_rocket"])))
    return component(name="GoldenCheese", props={}, min_size=[5, 4, 5], body=group(ops))


def HedgeMaze(n=9, seed=0, height=3, hedge="minecraft:oak_leaves",
              bloom="minecraft:flowering_azalea_leaves", path="minecraft:dirt_path",
              base="minecraft:grass_block"):
    """Seeded hedge maze, (3n+1) x (height+2) x (3n+1). Two-wide dirt
    paths between one-thick leaf hedges; entrance mid-south, exit mid-north, and an
    open 3x3-cell plaza at the centre holding the GoldenCheese. Lanterns sit on
    every third hedge post. Floor is local y=0; clears terrain in the paths."""
    size = 3 * n + 1
    passages = maze_passages(n, seed)
    cyc = random_cycle(seed * 17 + 3)
    c = n // 2
    def plaza(i, j):
        return i >= c - 1 and i <= c + 1 and j >= c - 1 and j <= c + 1
    hedges = {}
    for x in range(0, size, 3):
        for z in range(0, size, 3):
            hedges[(x, z)] = True
    for i in range(n):
        for j in range(n):
            # wall on the east side of cell (i, j)
            if i + 1 < n and not passages.get((i, j, "e")) and not (plaza(i, j) and plaza(i + 1, j)):
                hedges[(3 * i + 3, 3 * j + 1)] = True
                hedges[(3 * i + 3, 3 * j + 2)] = True
            if j + 1 < n and not passages.get((i, j, "s")) and not (plaza(i, j) and plaza(i, j + 1)):
                hedges[(3 * i + 1, 3 * j + 3)] = True
                hedges[(3 * i + 2, 3 * j + 3)] = True
    for i in range(n):
        if i != c:
            hedges[(3 * i + 1, 0)] = True
            hedges[(3 * i + 2, 0)] = True
            hedges[(3 * i + 1, size - 1)] = True
            hedges[(3 * i + 2, size - 1)] = True
        hedges[(0, 3 * i + 1)] = True
        hedges[(0, 3 * i + 2)] = True
        hedges[(size - 1, 3 * i + 1)] = True
        hedges[(size - 1, 3 * i + 2)] = True
    # interior posts inside the plaza are removed so it is fully open
    for x in range(3 * (c - 1) + 3, 3 * (c + 1) + 1, 3):
        for z in range(3 * (c - 1) + 3, 3 * (c + 1) + 1, 3):
            hedges.pop((x, z), None)
    p0 = 3 * c - 3 + 1
    ops = []
    for x in range(size):
        for z in range(size):
            if hedges.get((x, z)):
                ops.append(place_block([x, 0, z], block(base)))
                for y in range(1, height + 1):
                    m = bloom if random_number(cyc) < 0.18 else hedge
                    ops.append(place_block([x, y, z], block(m, {"persistent": "true"})))
                if x % 3 == 0 and z % 3 == 0 and (x + z) % 9 == 0:
                    ops.append(place_block([x, height + 1, z], block("minecraft:lantern"), phase="fixture"))
            else:
                ops.append(place_block([x, 0, z], block(path)))
                in_prize = x >= p0 + 1 and x < p0 + 6 and z >= p0 + 1 and z < p0 + 6
                if not in_prize:
                    ops.append(carve_region([x, 1, z], [x + 1, height + 2, z + 1]))
    ops.append(at([p0 + 1, 1, p0 + 1], GoldenCheese()))
    ops.append(at([p0 + 3 - 1, 1, p0 + 7], Sign(lines=["", "The Golden", "Cheese", ""], glowing=True)))
    return component(name="HedgeMaze", props={"n": n, "seed": seed, "height": height},
                     min_size=[size, height + 4, size], body=group(ops))


def build(n=9, seed=0):
    size = 3 * n + 1
    return component(name="HedgeMazeGarden", props={"n": n, "seed": seed},
                     min_size=[size, 7, size], metadata={"ground_level": 1},
                     body=HedgeMaze(n, seed))
