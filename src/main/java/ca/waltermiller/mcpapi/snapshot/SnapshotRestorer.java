package ca.waltermiller.mcpapi.snapshot;

import net.minecraft.nbt.NbtCompound;

import java.util.UUID;

/** Reversible region restore. Call on the server thread inside the area lock. */
public final class SnapshotRestorer {
    @FunctionalInterface
    public interface Capture { NbtCompound run() throws Exception; }
    @FunctionalInterface
    public interface Restore { boolean run(NbtCompound snapshot) throws Exception; }
    @FunctionalInterface
    public interface Commit { void run() throws Exception; }

    private final SnapshotStore store;

    public SnapshotRestorer(SnapshotStore store) {
        this.store = store;
    }

    public void restore(UUID id, boolean redo, Capture capture, Restore restore, Commit commit) throws Exception {
        if (store.pending(id)) {
            throw new IllegalStateException("An earlier restore is unresolved; inspect the world and saved snapshots before recovery");
        }
        NbtCompound target = store.load(id, redo).orElseThrow(() -> new IllegalArgumentException(
            "No " + (redo ? "redo" : "undo") + " snapshot exists for this build"));
        // Preserve the target and persist the reverse operation before changing any blocks.
        store.save(id, !redo, capture.run());
        store.beginRestore(id, redo);
        // Any failure from here leaves both snapshots and the pending marker for recovery.
        if (!restore.run(target)) {
            throw new IllegalStateException("Snapshot restore failed; inspect the world before recovery");
        }
        commit.run();
        store.finishRestore(id);
    }
}
