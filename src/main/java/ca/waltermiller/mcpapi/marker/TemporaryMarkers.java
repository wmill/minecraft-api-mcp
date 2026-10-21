package ca.waltermiller.mcpapi.marker;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Server-thread lifetime management, separated from Minecraft rendering for deterministic testing. */
final class TemporaryMarkers<T> {
    record Entry<T>(String id, Instant expires, T value) {}
    private final Map<String, Entry<T>> active = new HashMap<>();
    private final Clock clock;
    private final Consumer<T> cleanup;

    TemporaryMarkers(Clock clock, Consumer<T> cleanup) {
        this.clock = clock;
        this.cleanup = cleanup;
    }

    Entry<T> add(int seconds, Supplier<T> factory) {
        expire();
        if (active.size() >= 16) throw new IllegalArgumentException("At most 16 area markers may be active");
        String id = UUID.randomUUID().toString();
        var entry = new Entry<>(id, clock.instant().plusSeconds(seconds), factory.get());
        active.put(id, entry);
        return entry;
    }

    boolean remove(String id) {
        expire();
        var entry = active.remove(id);
        if (entry == null) return false;
        cleanup.accept(entry.value());
        return true;
    }

    void expire() {
        Instant now = clock.instant();
        active.values().removeIf(entry -> {
            if (now.isBefore(entry.expires())) return false;
            cleanup.accept(entry.value());
            return true;
        });
    }

    List<Entry<T>> entries() { return List.copyOf(active.values()); }

    void clear() {
        active.values().forEach(entry -> cleanup.accept(entry.value()));
        active.clear();
    }
}
