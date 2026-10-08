package ca.waltermiller.mcpapi.snapshot;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotRestorerTest {
    @TempDir Path dir;

    private NbtCompound region(String blocks, String chest) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("blocks", blocks);
        NbtCompound blockEntity = new NbtCompound();
        blockEntity.putString("Items", chest);
        nbt.put("block_entity", blockEntity);
        return nbt;
    }

    @Test
    void repeatedUndoRedoPreservesEditsAndSurvivesRestart() throws Exception {
        UUID id = UUID.randomUUID();
        SnapshotStore store = new SnapshotStore(dir, 1000);
        NbtCompound terrain = region("grass,air", "original");
        NbtCompound editedBuild = region("stone,glass", "edited inventory");
        AtomicReference<NbtCompound> world = new AtomicReference<>(editedBuild);
        store.save(id, terrain); // legacy undo filename remains supported
        new SnapshotRestorer(store).restore(id, false, world::get, nbt -> { world.set(nbt); return true; }, () -> {});
        assertThat(world.get()).isEqualTo(terrain);
        assertThat(store.pending(id)).isFalse();

        NbtCompound editedTerrain = region("grass,flower", "new inventory");
        world.set(editedTerrain);
        SnapshotStore restarted = new SnapshotStore(dir, 1000);
        new SnapshotRestorer(restarted).restore(id, true, world::get, nbt -> { world.set(nbt); return true; }, () -> {});
        assertThat(world.get()).isEqualTo(editedBuild);
        new SnapshotRestorer(restarted).restore(id, false, world::get, nbt -> { world.set(nbt); return true; }, () -> {});
        assertThat(world.get()).isEqualTo(editedTerrain);
    }

    @Test
    void databaseFailureRetainsBothSnapshotsAndBlocksFurtherRestoresAcrossRestart() throws Exception {
        UUID id = UUID.randomUUID();
        SnapshotStore store = new SnapshotStore(dir, 1000);
        NbtCompound terrain = region("grass", "old"), build = region("stone", "new");
        store.save(id, terrain);
        AtomicReference<NbtCompound> world = new AtomicReference<>(build);
        assertThrows(SQLException.class, () -> new SnapshotRestorer(store).restore(id, false, world::get,
            nbt -> { world.set(nbt); return true; }, () -> { throw new SQLException("offline"); }));
        assertThat(world.get()).isEqualTo(terrain);
        assertThat(store.load(id)).contains(terrain);
        assertThat(store.load(id, true)).contains(build);
        SnapshotRestorer restarted = new SnapshotRestorer(new SnapshotStore(dir, 1000));
        for (boolean redo : new boolean[] {false, true}) {
            assertThrows(IllegalStateException.class, () -> restarted.restore(id, redo,
                () -> { throw new AssertionError("must not capture again"); }, nbt -> true, () -> {}));
        }
    }

    @Test
    void missingSnapshotOrReverseSnapshotWriteFailureNeverChangesWorld() throws Exception {
        UUID id = UUID.randomUUID();
        SnapshotStore store = new SnapshotStore(dir, 1000);
        SnapshotRestorer restorer = new SnapshotRestorer(store);
        SnapshotRestorer.Restore forbidden = nbt -> { throw new AssertionError("must not restore"); };
        assertThrows(IllegalArgumentException.class, () -> restorer.restore(id, true,
            () -> new NbtCompound(), forbidden, () -> {}));
        store.save(id, region("grass", "old"));
        Files.createDirectory(dir.resolve(id + ".redo.nbt"));
        assertThrows(IOException.class, () -> restorer.restore(id, false,
            () -> region("stone", "new"), forbidden, () -> {}));
        assertThat(store.pending(id)).isFalse();
        assertThat(store.load(id)).isPresent();
    }

    @Test
    void partialWorldFailureDoesNotCommitAndLeavesRecoveryMarker() throws Exception {
        UUID id = UUID.randomUUID();
        SnapshotStore store = new SnapshotStore(dir, 1000);
        store.save(id, region("grass", "old"));
        assertThrows(IllegalStateException.class, () -> new SnapshotRestorer(store).restore(id, false,
            () -> region("stone", "new"), nbt -> false,
            () -> { throw new AssertionError("must not commit"); }));
        assertThat(store.pending(id)).isTrue();
        assertThat(store.load(id, true)).isPresent();
    }
}
