package ca.waltermiller.mcpapi.endpoints;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StagedPlacementTest {
    /** A fresh scheduler per test (the static instance is shared with the running mod). */
    private final StagedPlacement scheduler = new StagedPlacement();

    private static StagedPlacement.Stage recording(List<String> log, String name) {
        return () -> {
            log.add(name);
            return true;
        };
    }

    @Test
    void placesStagesInOrderEveryDelayTicks() throws Exception {
        List<String> log = new ArrayList<>();
        CompletableFuture<Integer> done = scheduler.start(
            List.of(recording(log, "a"), recording(log, "b"), recording(log, "c")), 2, 1);

        scheduler.tick();
        assertThat(log).isEmpty();
        scheduler.tick();
        assertThat(log).containsExactly("a");
        scheduler.tick();
        scheduler.tick();
        assertThat(log).containsExactly("a", "b");
        assertThat(done).isNotDone();
        scheduler.tick();
        scheduler.tick();
        assertThat(log).containsExactly("a", "b", "c");
        assertThat(done.get()).isEqualTo(4);
        assertThat(scheduler.activeRuns()).isZero();
    }

    @Test
    void noLaterStagesCompletesImmediately() throws Exception {
        assertThat(scheduler.start(List.of(), 2, 1).get()).isEqualTo(1);
        assertThat(scheduler.activeRuns()).isZero();
    }

    @Test
    void stopsAtTheFirstFailureAndReportsWhatWasPlaced() {
        List<String> log = new ArrayList<>();
        CompletableFuture<Integer> done = scheduler.start(List.of(
            recording(log, "a"),
            () -> { throw new IllegalStateException("area_locked"); },
            recording(log, "never")), 1, 1);

        scheduler.tick();
        scheduler.tick();
        scheduler.tick();
        assertThat(log).containsExactly("a");
        assertThatThrownBy(done::get).isInstanceOf(ExecutionException.class)
            .cause().isInstanceOf(StagedPlacement.StageFailure.class)
            .hasMessageContaining("area_locked")
            .satisfies(e -> assertThat(((StagedPlacement.StageFailure) e).placed).isEqualTo(2));
        assertThat(scheduler.activeRuns()).isZero();
    }

    @Test
    void aRejectedPlacementStopsTheRun() {
        CompletableFuture<Integer> done = scheduler.start(List.of(() -> false), 1, 1);
        scheduler.tick();
        assertThatThrownBy(done::get).cause().isInstanceOf(StagedPlacement.StageFailure.class)
            .hasMessageContaining("placement failed");
    }

    @Test
    void abortCancelsPendingStages() {
        List<String> log = new ArrayList<>();
        CompletableFuture<Integer> done = scheduler.start(List.of(recording(log, "a")), 5, 1);
        scheduler.abortAll("server is stopping");
        scheduler.tick();
        assertThat(log).isEmpty();
        assertThatThrownBy(done::get).cause().hasMessageContaining("interrupted")
            .satisfies(e -> assertThat(((StagedPlacement.StageFailure) e).placed).isEqualTo(1));
    }

    @Test
    void validatesStageSizesAndCount() {
        int[] size = {8, 11, 7};
        assertThat(StagedPlacement.validateStages(size, List.of(new int[]{8, 11, 7}, new int[]{8, 11, 7}))).isNull();
        assertThat(StagedPlacement.validateStages(size, List.of(new int[]{8, 11, 7}, new int[]{8, 10, 7})))
            .startsWith("Invalid staged structure: stage 1");
        List<int[]> tooMany = new ArrayList<>();
        for (int i = 0; i <= StagedPlacement.MAX_STAGES; i++) tooMany.add(size);
        assertThat(StagedPlacement.validateStages(size, tooMany)).contains("at most");
    }

    @Test
    void parsesDelay() {
        assertThat(StagedPlacement.parseDelay(null)).isEqualTo(StagedPlacement.DEFAULT_DELAY_TICKS);
        assertThat(StagedPlacement.parseDelay(" 5 ")).isEqualTo(5);
        assertThatThrownBy(() -> StagedPlacement.parseDelay("0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StagedPlacement.parseDelay("101")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StagedPlacement.parseDelay("x")).hasMessageStartingWith("Invalid stage_delay_ticks");
    }
}
