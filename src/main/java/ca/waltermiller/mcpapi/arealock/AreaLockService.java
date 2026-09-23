package ca.waltermiller.mcpapi.arealock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Ephemeral reservations shared by all API write paths. World operations run on the server thread. */
public final class AreaLockService {
    public static final String HEADER = "X-Area-Lock-Id";
    private final Map<String, Lease> leases = new LinkedHashMap<>();
    private final Clock clock;
    private final Duration idleTimeout;

    private record Lease(String id, String world, AreaBounds bounds, String label, Instant expiresAt) {}

    public AreaLockService() {
        this(Clock.systemUTC(), Duration.ofSeconds(Long.parseLong(
            System.getenv().getOrDefault("AREA_LOCK_IDLE_SECONDS", "900"))));
    }

    public AreaLockService(Clock clock, Duration idleTimeout) {
        if (idleTimeout.isNegative() || idleTimeout.isZero() || idleTimeout.getSeconds() == 0) {
            throw new IllegalArgumentException("AREA_LOCK_IDLE_SECONDS must be positive");
        }
        this.clock = clock;
        this.idleTimeout = idleTimeout;
    }

    public synchronized Map<String, Object> acquire(String world, AreaBounds bounds, String label) {
        prune();
        checkOverlap(world, bounds, null);
        var lease = new Lease(UUID.randomUUID().toString(), world, bounds,
            label == null ? "" : label, clock.instant().plus(idleTimeout));
        leases.put(lease.id(), lease);
        return describe(lease, true);
    }

    public synchronized Map<String, Object> update(String id, AreaBounds bounds) {
        prune();
        Lease old = require(id);
        AreaBounds replacement = bounds == null ? old.bounds() : bounds;
        checkOverlap(old.world(), replacement, id);
        var lease = new Lease(id, old.world(), replacement, old.label(), clock.instant().plus(idleTimeout));
        leases.put(id, lease);
        return describe(lease, true);
    }

    public synchronized void release(String id) {
        prune();
        leases.remove(id);
    }

    public synchronized List<Map<String, Object>> list(String world, AreaBounds bounds) {
        prune();
        return leases.values().stream()
            .filter(l -> world == null || world.equals(l.world()))
            .filter(l -> bounds == null || bounds.intersects(l.bounds()))
            .map(l -> describe(l, false)).toList();
    }

    /** Validate without renewing, for accepting queued execution. */
    public synchronized void validateToken(String id) {
        prune();
        if (id != null) require(id);
    }

    /** Authorization and the entire operation share one critical section, including renewal. */
    public synchronized <T> T execute(String world, AreaBounds bounds, String id,
                                      Supplier<T> operation, Predicate<T> success) {
        prune();
        Lease owner = id == null ? null : require(id);
        if (owner != null && (!owner.world().equals(world)
            || (bounds != null && !owner.bounds().contains(bounds)))) {
            throw new AreaLockException("outside_area_lock", "Placement must stay inside its reserved cuboid and world",
                Map.of("reservation", describe(owner, false), "requested_world", world,
                    "requested_bounds", bounds == null ? Map.of() : bounds));
        }
        if (bounds != null) checkOverlap(world, bounds, id);
        T result = operation.get();
        if (owner != null && success.test(result)) {
            leases.put(id, new Lease(id, world, owner.bounds(), owner.label(), clock.instant().plus(idleTimeout)));
        }
        return result;
    }

    private Lease require(String id) {
        Lease lease = leases.get(id);
        if (lease == null) {
            throw new AreaLockException("invalid_area_lock", "Area lock is unknown or expired; explicitly acquire a reservation before retrying",
                Map.of());
        }
        return lease;
    }

    private void checkOverlap(String world, AreaBounds bounds, String owner) {
        for (Lease lease : leases.values()) {
            if (!lease.id().equals(owner) && lease.world().equals(world) && lease.bounds().intersects(bounds)) {
                throw new AreaLockException("area_locked", "Placement area overlaps an active reservation",
                    Map.of("reservation", describe(lease, false), "requested_bounds", bounds));
            }
        }
    }

    private void prune() {
        Instant now = clock.instant();
        leases.values().removeIf(l -> !l.expiresAt().isAfter(now));
    }

    private Map<String, Object> describe(Lease lease, boolean includeToken) {
        var result = new LinkedHashMap<String, Object>();
        if (includeToken) result.put("lock_id", lease.id());
        result.put("world", lease.world());
        result.put("bounds", lease.bounds());
        result.put("label", lease.label());
        result.put("expires_at", lease.expiresAt().toString());
        result.put("idle_timeout_seconds", idleTimeout.getSeconds());
        return result;
    }
}
