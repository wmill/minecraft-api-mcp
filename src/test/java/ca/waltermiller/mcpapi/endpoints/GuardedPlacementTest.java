package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import ca.waltermiller.mcpapi.arealock.AreaLockException;
import ca.waltermiller.mcpapi.arealock.AreaLockService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class GuardedPlacementTest {
    @Test
    void authorizationHappensWhenScheduledWriteActuallyRuns() {
        var now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        var clock = new Clock() {
            @Override public Instant instant() { return now.get(); }
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
        };
        var locks = new AreaLockService(clock, Duration.ofMinutes(15));
        var bounds = new AreaBounds(0, 0, 0, 5, 5, 5);
        String token = (String) locks.acquire("world", bounds, "house").get("lock_id");
        var scheduled = new ArrayList<Runnable>();
        var writes = new AtomicInteger();
        CompletableFuture<Integer> result = new CompletableFuture<>();
        GuardedPlacement.submit(scheduled::add, locks, "world", token, () -> bounds,
            writes::incrementAndGet, n -> true, result);
        assertThat(result).isNotDone();
        now.set(now.get().plusSeconds(900));
        scheduled.getFirst().run();
        assertThatThrownBy(result::join).hasCauseInstanceOf(AreaLockException.class);
        assertThat(writes.get()).isZero();
    }

    @Test
    void newReservationBeforeQueuedUnlockedWriteRejectsEntireOperation() {
        var locks = new AreaLockService(Clock.systemUTC(), Duration.ofMinutes(15));
        var bounds = new AreaBounds(0, 0, 0, 5, 5, 5);
        var scheduled = new ArrayList<Runnable>();
        var writes = new AtomicInteger();
        CompletableFuture<Integer> result = new CompletableFuture<>();
        GuardedPlacement.submit(scheduled::add, locks, "world", null, () -> bounds,
            writes::incrementAndGet, n -> true, result);
        locks.acquire("world", bounds, "new owner");
        scheduled.getFirst().run();
        assertThatThrownBy(result::join).hasCauseInstanceOf(AreaLockException.class);
        assertThat(writes.get()).isZero();
    }
}
