package ca.waltermiller.mcpapi.terrain;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TerrainRewriterTest {
    /** Records writes as "x,y,z=id" (air as "air", vegetation clears as "-x,y,z"). */
    static final class Recording implements TerrainAccess {
        final List<String> writes = new ArrayList<>();
        public boolean chunkLoaded(int chunkX, int chunkZ) { return true; }
        public GroundColumn sample(int x, int z) { throw new UnsupportedOperationException(); }
        public void clearVegetation(int x, int y, int z) { writes.add("-" + x + "," + y + "," + z); }
        public void setTerrain(int x, int y, int z, String id) {
            writes.add(x + "," + y + "," + z + "=" + (id == null ? "air" : id));
        }
        public NbtCompound capture(AreaBounds bounds) { return null; }
    }

    private static final GroundColumn GRASS = new GroundColumn(GroundColumn.Kind.GROUND, 65, 65, "grass", "dirt");

    @Test void cutClearsDownAndResurfaces() {
        var world = new Recording();
        TerrainRewriter.reshape(world, 0, 0, GRASS, 63);
        assertThat(world.writes).containsExactly("0,64,0=air", "0,63,0=air", "0,62,0=grass");
    }

    @Test void fillBuriesOldTopAndResurfaces() {
        var world = new Recording();
        TerrainRewriter.reshape(world, 0, 0, GRASS, 68);
        assertThat(world.writes).containsExactly("0,64,0=dirt", "0,65,0=dirt", "0,66,0=dirt", "0,67,0=grass");
    }

    @Test void unchangedColumnIsNotWritten() {
        var world = new Recording();
        TerrainRewriter.reshape(world, 0, 0, GRASS, 65);
        assertThat(world.writes).isEmpty();
    }

    @Test void clearsVegetationFirstAndSkipsBuiltColumns() {
        var world = new Recording();
        GroundColumn tree = new GroundColumn(GroundColumn.Kind.GROUND, 65, 68, "grass", "dirt");
        GroundColumn house = new GroundColumn(GroundColumn.Kind.BUILT, 70, 72, "oak_planks", null);
        GroundColumn pond = new GroundColumn(GroundColumn.Kind.FLUID, 63, 64, "water", null);
        var written = TerrainRewriter.apply(world, 10, 20,
            new GroundColumn[][] {{tree, house, pond}}, new int[][] {{66, 60, 50}});
        assertThat(written.vegetationColumns()).isEqualTo(2);
        assertThat(written.vegetationBlocks()).isEqualTo(4);
        assertThat(world.writes).containsExactly(
            "-10,67,20", "-10,66,20", "-10,65,20", "-10,63,22",
            "10,64,20=dirt", "10,65,20=grass");
    }

    @Test void boundsCoverBuriedTopAndVegetation() {
        GroundColumn tree = new GroundColumn(GroundColumn.Kind.GROUND, 65, 75, "grass", "dirt");
        AreaBounds b = TerrainRewriter.bounds(5, 6, new GroundColumn[][] {{GRASS, tree}}, new int[][] {{62, 66}});
        assertThat(b).isEqualTo(new AreaBounds(5, 61, 6, 5, 74, 7));
    }
}
