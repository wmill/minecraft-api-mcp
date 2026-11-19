package ca.waltermiller.mcpapi.snapshot;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotStoreTest {
    @TempDir Path dir;

    @Test
    void saveLoadDeleteRoundTrip() throws Exception {
        var store = new SnapshotStore(dir.resolve("nested"), 100);
        UUID id = UUID.randomUUID();
        NbtCompound nbt = new NbtCompound();
        nbt.putInt("DataVersion", 4438);
        nbt.putString("marker", "terrain");

        assertThat(store.load(id)).isEmpty();
        store.save(id, nbt);

        assertThat(store.exists(id)).isTrue();
        assertThat(store.load(id)).hasValueSatisfying(loaded ->
            assertThat(loaded.getString("marker", "")).isEqualTo("terrain"));
        try (var files = Files.list(dir.resolve("nested"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly(id + ".nbt");
        }

        store.delete(id);
        assertThat(store.exists(id)).isFalse();
        store.delete(id);
    }

    @Test
    void templatesAndRevisionBasesAreSeparateFiles() throws Exception {
        var store = new SnapshotStore(dir, 100);
        UUID id = UUID.randomUUID();
        NbtCompound undo = new NbtCompound();
        undo.putString("marker", "undo");
        NbtCompound template = new NbtCompound();
        template.putString("marker", "template");
        NbtCompound base = new NbtCompound();
        base.putString("marker", "base");

        assertThat(store.templateExists(id)).isFalse();
        assertThat(store.baseExists(id)).isFalse();
        store.save(id, undo);
        store.saveTemplate(id, template);
        store.saveBase(id, base);

        assertThat(store.loadTemplate(id)).hasValueSatisfying(n -> assertThat(n.getString("marker", "")).isEqualTo("template"));
        assertThat(store.loadBase(id)).hasValueSatisfying(n -> assertThat(n.getString("marker", "")).isEqualTo("base"));
        assertThat(store.load(id)).hasValueSatisfying(n -> assertThat(n.getString("marker", "")).isEqualTo("undo"));
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                .containsExactlyInAnyOrder(id + ".nbt", id + ".template.nbt", id + ".base.nbt");
        }
    }

    @Test
    void volumeCapIsInclusive() {
        var store = new SnapshotStore(dir, 27);
        assertThat(store.fits(new AreaBounds(0, 0, 0, 2, 2, 2))).isTrue();
        assertThat(store.fits(new AreaBounds(0, 0, 0, 2, 2, 3))).isFalse();
        assertThat(SnapshotStore.volume(new AreaBounds(-5, -64, -5, 4, 319, 4))).isEqualTo(10L * 384 * 10);
    }

    @Test
    void rejectsNonPositiveCap() {
        assertThrows(IllegalArgumentException.class, () -> new SnapshotStore(dir, 0));
    }
}
