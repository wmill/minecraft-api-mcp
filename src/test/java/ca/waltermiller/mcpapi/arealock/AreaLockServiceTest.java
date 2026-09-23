package ca.waltermiller.mcpapi.arealock;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class AreaLockServiceTest {
    private static final AreaBounds AREA = new AreaBounds(0, 60, 0, 9, 80, 9);
    private final MutableClock clock = new MutableClock();
    private final AreaLockService locks = new AreaLockService(clock, Duration.ofMinutes(15));

    private String acquire() { return (String) locks.acquire("minecraft:overworld", AREA, "house").get("lock_id"); }

    @Test
    void overlappingAcquisitionsHaveExactlyOneWinner() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var winners = new AtomicInteger();
            var first = workers.submit(() -> compete(start, winners));
            var second = workers.submit(() -> compete(start, winners));
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertThat(winners.get()).isEqualTo(1);
        }
    }

    private void compete(CountDownLatch start, AtomicInteger winners) {
        try {
            start.await();
            acquire();
            winners.incrementAndGet();
        } catch (AreaLockException conflict) {
            assertThat(conflict.payload()).containsEntry("code", "area_locked");
        } catch (InterruptedException e) { throw new AssertionError(e); }
    }

    @Test
    void inclusiveEdgesConflictButAdjacentBlocksAndOtherWorldsDoNot() {
        acquire();
        assertThatThrownBy(() -> locks.acquire("minecraft:overworld", new AreaBounds(9, 80, 9, 12, 90, 12), ""))
            .isInstanceOf(AreaLockException.class);
        locks.acquire("minecraft:overworld", new AreaBounds(10, 60, 0, 19, 80, 9), "next door");
        locks.acquire("minecraft:the_nether", AREA, "nether");
        assertThat(locks.list(null, null)).hasSize(3);
        assertThat(locks.list("minecraft:overworld", AREA)).hasSize(1);
    }

    @Test
    void optionalWritesAndOwnerContainmentAreEnforcedBeforeAnyMutation() {
        String id = acquire();
        var writes = new AtomicInteger();
        assertThatThrownBy(() -> locks.execute("minecraft:overworld", AREA, null, writes::incrementAndGet, n -> true))
            .isInstanceOf(AreaLockException.class);
        AreaBounds elsewhere = new AreaBounds(100, 60, 100, 109, 80, 109);
        assertThatThrownBy(() -> locks.execute("minecraft:overworld", elsewhere, id, writes::incrementAndGet, n -> true))
            .isInstanceOf(AreaLockException.class);
        assertThatThrownBy(() -> locks.execute("minecraft:the_nether", AREA, id, writes::incrementAndGet, n -> true))
            .isInstanceOf(AreaLockException.class);
        assertThat(writes.get()).isZero();
        locks.execute("minecraft:overworld", AREA, id, writes::incrementAndGet, n -> true);
        locks.execute("minecraft:overworld", elsewhere, null, writes::incrementAndGet, n -> true);
        assertThat(writes.get()).isEqualTo(2);
    }

    @Test
    void expiresAtDeadlineAndOldTokenNeverBecomesAnUnlockedWrite() {
        String id = acquire();
        clock.advance(900);
        assertThat(locks.list(null, null)).isEmpty();
        assertThatThrownBy(() -> locks.execute("minecraft:overworld", AREA, id, () -> true, b -> b))
            .isInstanceOf(AreaLockException.class);
        assertThatThrownBy(() -> locks.update(id, null)).isInstanceOf(AreaLockException.class);
        assertThat(acquire()).isNotEqualTo(id);
    }

    @Test
    void writesAndExplicitRenewalExtendButReadsFailuresAndValidationDoNot() {
        String id = acquire();
        clock.advance(800);
        locks.execute("minecraft:overworld", AREA, id, () -> true, b -> b);
        clock.advance(800);
        locks.update(id, null);
        clock.advance(800);
        locks.list(null, null);
        locks.validateToken(id);
        locks.execute("minecraft:overworld", AREA, id, () -> false, b -> b);
        clock.advance(100);
        assertThat(locks.list(null, null)).isEmpty();
    }

    @Test
    void resizeIsAtomicAndDoesNotRenewOnConflict() {
        String id = acquire();
        locks.acquire("minecraft:overworld", new AreaBounds(20, 60, 0, 29, 80, 9), "neighbor");
        AreaBounds grown = new AreaBounds(-5, 60, 0, 19, 80, 9);
        assertThat(locks.update(id, grown)).containsEntry("bounds", grown);
        clock.advance(899);
        assertThatThrownBy(() -> locks.update(id, new AreaBounds(-5, 60, 0, 20, 80, 9)))
            .isInstanceOf(AreaLockException.class);
        assertThat(locks.list(null, AREA).getFirst()).containsEntry("bounds", grown);
        clock.advance(1);
        assertThat(locks.list(null, null)).isEmpty();
    }

    @Test
    void shrinkFreesSpaceAndReleaseIsIdempotent() {
        String id = acquire();
        locks.update(id, new AreaBounds(0, 60, 0, 4, 80, 9));
        locks.acquire("minecraft:overworld", new AreaBounds(5, 60, 0, 9, 80, 9), "extension");
        locks.release(id);
        locks.release(id);
        assertThat(locks.list(null, null)).hasSize(1);
    }

    @Test
    void listingsAndConflictsNeverRevealToken() {
        String id = acquire();
        assertThat(locks.list(null, null).toString()).doesNotContain(id).doesNotContain("lock_id");
        try {
            locks.acquire("minecraft:overworld", AREA, "other");
            fail("expected conflict");
        } catch (AreaLockException e) {
            assertThat(e.payload().toString()).doesNotContain(id).doesNotContain("lock_id");
            assertThat(e.payload()).containsKeys("reservation", "requested_bounds");
        }
    }

    @Test
    void longPlacementKeepsAuthorizationAndRenewsAtCompletion() {
        String id = acquire();
        locks.execute("minecraft:overworld", AREA, id, () -> { clock.advance(1000); return true; }, b -> b);
        locks.validateToken(id);
        assertThat(locks.list(null, null)).hasSize(1);
    }

    @Test
    void restartInvalidatesTokensAndBoundsValidate() {
        String id = acquire();
        var restarted = new AreaLockService(clock, Duration.ofMinutes(15));
        assertThatThrownBy(() -> restarted.validateToken(id)).isInstanceOf(AreaLockException.class);
        assertThatThrownBy(() -> new AreaBounds(1, 0, 0, 0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> locks.validateToken("")).isInstanceOf(AreaLockException.class);
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
