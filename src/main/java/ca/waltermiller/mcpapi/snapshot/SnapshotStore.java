package ca.waltermiller.mcpapi.snapshot;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/** Pre-placement region snapshots on disk, one gzipped structure NBT file per build. */
public final class SnapshotStore {
    public static final long DEFAULT_MAX_VOLUME = 2_000_000L;

    private final Path dir;
    private final long maxVolume;

    public SnapshotStore(Path dir, long maxVolume) {
        if (maxVolume <= 0) throw new IllegalArgumentException("BUILD_SNAPSHOT_MAX_VOLUME must be positive");
        this.dir = dir;
        this.maxVolume = maxVolume;
    }

    /** BUILD_SNAPSHOT_DIR (default {@code <runDir>/build-snapshots}) and BUILD_SNAPSHOT_MAX_VOLUME. */
    public static SnapshotStore fromEnvironment(Path runDir) {
        String dir = System.getenv("BUILD_SNAPSHOT_DIR");
        String volume = System.getenv("BUILD_SNAPSHOT_MAX_VOLUME");
        return new SnapshotStore(dir == null || dir.isBlank() ? runDir.resolve("build-snapshots") : Path.of(dir),
            volume == null || volume.isBlank() ? DEFAULT_MAX_VOLUME : Long.parseLong(volume.trim()));
    }

    public long maxVolume() { return maxVolume; }

    public boolean fits(AreaBounds bounds) {
        return volume(bounds) <= maxVolume;
    }

    public static long volume(AreaBounds b) {
        return ((long) b.max_x() - b.min_x() + 1) * ((long) b.max_y() - b.min_y() + 1) * ((long) b.max_z() - b.min_z() + 1);
    }

    public void save(UUID buildId, NbtCompound snapshot) throws IOException {
        Files.createDirectories(dir);
        Path target = path(buildId);
        Path temp = Files.createTempFile(dir, buildId + ".", ".tmp");
        try {
            NbtIo.writeCompressed(snapshot, temp);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public Optional<NbtCompound> load(UUID buildId) throws IOException {
        Path file = path(buildId);
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes()));
    }

    public boolean exists(UUID buildId) {
        return Files.isRegularFile(path(buildId));
    }

    public void delete(UUID buildId) throws IOException {
        Files.deleteIfExists(path(buildId));
    }

    Path path(UUID buildId) {
        return dir.resolve(buildId + ".nbt");
    }
}
