package ca.waltermiller.mcpapi.marker;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;

class TemporaryMarkersTest {
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    @Test void expiresAtDeadlineAndCleansExactlyOnce() {
        var clock = new TestClock();
        var cleaned = new ArrayList<String>();
        var markers = new TemporaryMarkers<String>(clock, cleaned::add);
        var entry = markers.add(60, () -> "label");
        clock.now = clock.now.plusSeconds(59);
        markers.expire();
        assertThat(markers.entries()).hasSize(1);
        clock.now = clock.now.plusSeconds(1);
        markers.expire();
        assertThat(markers.entries()).isEmpty();
        assertThat(markers.remove(entry.id())).isFalse();
        markers.clear();
        assertThat(cleaned).containsExactly("label");
    }

    @Test void enforcesCapacityBeforeCreatingVisualsAndReclaimsExpiredSlots() {
        var clock = new TestClock();
        var cleaned = new ArrayList<Integer>();
        var markers = new TemporaryMarkers<Integer>(clock, cleaned::add);
        for (int i = 0; i < 16; i++) { final int index = i; markers.add(1, () -> index); }
        assertThatIllegalArgumentException().isThrownBy(() -> markers.add(1, () -> {
            throw new AssertionError("Must not construct visuals over capacity");
        }));
        clock.now = clock.now.plusSeconds(1);
        markers.add(60, () -> 99);
        assertThat(cleaned).hasSize(16);
        assertThat(markers.entries()).hasSize(1);
        markers.clear();
        markers.clear();
        assertThat(cleaned).hasSize(17).contains(99);
    }

    @Test void explicitRemovalIsIdempotentAndDoesNotAffectOtherMarkers() {
        var cleaned = new ArrayList<String>();
        var markers = new TemporaryMarkers<String>(new TestClock(), cleaned::add);
        var first = markers.add(60, () -> "first");
        var second = markers.add(60, () -> "second");
        assertThat(markers.remove(first.id())).isTrue();
        assertThat(markers.remove(first.id())).isFalse();
        assertThat(markers.entries()).containsExactly(second);
        assertThat(cleaned).containsExactly("first");
    }

    @Test void removalAtExpiryReportsAlreadyExpiredEvenBeforeNextTick() {
        var clock = new TestClock();
        var cleaned = new ArrayList<String>();
        var markers = new TemporaryMarkers<String>(clock, cleaned::add);
        var entry = markers.add(1, () -> "label");
        clock.now = clock.now.plusSeconds(1);
        assertThat(markers.remove(entry.id())).isFalse();
        assertThat(cleaned).containsExactly("label");
    }
}
