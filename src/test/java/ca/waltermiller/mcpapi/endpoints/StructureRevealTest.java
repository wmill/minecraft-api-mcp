package ca.waltermiller.mcpapi.endpoints;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StructureRevealTest {
    @Test
    void revealIsMonotonicAndFinishesEvenWhenBlockCountDoesNotDivideDuration() {
        for (int total : new int[]{1, 39, 41, 8192}) {
            int previous = 0;
            for (int tick = 0; tick <= 40; tick++) {
                int count = StructureReveal.revealedCount(tick, total);
                assertThat(count).isBetween(previous, total);
                previous = count;
            }
            assertThat(previous).isEqualTo(total);
        }
    }

    @Test
    void timesOutsideAnimationAreClamped() {
        assertThat(StructureReveal.revealedCount(-2, 100)).isZero();
        assertThat(StructureReveal.revealedCount(20, 100)).isEqualTo(50);
        assertThat(StructureReveal.revealedCount(100, 100)).isEqualTo(100);
    }
}
