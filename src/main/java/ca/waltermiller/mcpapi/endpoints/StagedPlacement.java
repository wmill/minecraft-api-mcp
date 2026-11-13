package ca.waltermiller.mcpapi.endpoints;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Places the later install stages of a staged structure on successive server ticks.
 *
 * <p>A single paste writes every block in one tick, and the block updates that follow can trip
 * redstone before its circuit is complete (observers firing, pistons extending). Staged builds
 * list their writes in install order; the caller places stage 0 itself, then hands the rest here,
 * and each one runs {@code delayTicks} ticks after the previous one. Everything runs on the
 * server thread: {@link #start} is called from a server-thread callback and {@link #tick} from
 * {@code END_SERVER_TICK}, so no locking is needed.
 */
public final class StagedPlacement {
    public static final int MAX_STAGES = 64;
    public static final int DEFAULT_DELAY_TICKS = 2;
    public static final int MAX_DELAY_TICKS = 100;

    /** One stage's write. Returns false when the world rejected it; may throw (e.g. an area-lock conflict). */
    @FunctionalInterface
    public interface Stage {
        boolean place() throws Exception;
    }

    /** A run stopped early. {@code placed} counts the stages already written, including stage 0. */
    public static final class StageFailure extends Exception {
        public final int placed;

        public StageFailure(int placed, String message, Throwable cause) {
            super(message, cause);
            this.placed = placed;
        }
    }

    private static final StagedPlacement INSTANCE = new StagedPlacement();

    private final List<Run> active = new ArrayList<>();

    /** Package-private so tests get their own scheduler; the mod uses {@link #instance()}. */
    StagedPlacement() {
    }

    /** Registers the tick and shutdown hooks; call once from the mod initializer. */
    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> INSTANCE.tick());
        // Pending stages never run after shutdown starts; their callers report the interruption.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> INSTANCE.abortAll("server is stopping"));
    }

    public static StagedPlacement instance() {
        return INSTANCE;
    }

    /**
     * Schedules {@code stages} (stage 1 onwards). The future completes with the total number of
     * stages placed ({@code alreadyPlaced} plus these), or exceptionally with a {@link StageFailure}.
     */
    public CompletableFuture<Integer> start(List<Stage> stages, int delayTicks, int alreadyPlaced) {
        Run run = new Run(new ArrayDeque<>(stages), Math.max(1, delayTicks), alreadyPlaced);
        if (run.remaining.isEmpty()) {
            run.done.complete(alreadyPlaced);
        } else {
            active.add(run);
        }
        return run.done;
    }

    /** Advances every run by one tick. */
    public void tick() {
        if (active.isEmpty()) return;
        // A completion callback may start another run; iterate over a copy.
        for (Run run : List.copyOf(active)) {
            if (run.step()) active.remove(run);
        }
    }

    /** Fails every pending run; the stages already placed stay in the world. */
    public void abortAll(String reason) {
        for (Run run : List.copyOf(active)) {
            run.done.completeExceptionally(new StageFailure(run.placed, "staged placement interrupted: " + reason, null));
        }
        active.clear();
    }

    int activeRuns() {
        return active.size();
    }

    /**
     * Checks that every stage template has the main template's size (stages share one coordinate
     * frame, so rotation and bounds are the same for all of them) and that the count is in range.
     * Returns an error message, or null when the stages are valid.
     */
    public static String validateStages(int[] mainSize, List<int[]> stageSizes) {
        if (stageSizes.size() > MAX_STAGES) {
            return "Invalid staged structure: at most " + MAX_STAGES + " stages, got " + stageSizes.size();
        }
        for (int i = 0; i < stageSizes.size(); i++) {
            int[] size = stageSizes.get(i);
            if (size.length != 3 || size[0] != mainSize[0] || size[1] != mainSize[1] || size[2] != mainSize[2]) {
                return "Invalid staged structure: stage " + i + " has size " + java.util.Arrays.toString(size)
                    + " but the structure is " + java.util.Arrays.toString(mainSize);
            }
        }
        return null;
    }

    /** Parses {@code stage_delay_ticks}: absent means the default; 1..{@value #MAX_DELAY_TICKS}. */
    public static int parseDelay(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_DELAY_TICKS;
        int ticks;
        try {
            ticks = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid stage_delay_ticks: " + raw);
        }
        if (ticks < 1 || ticks > MAX_DELAY_TICKS) {
            throw new IllegalArgumentException("Invalid stage_delay_ticks: must be 1.." + MAX_DELAY_TICKS + ", got " + ticks);
        }
        return ticks;
    }

    private static final class Run {
        final Deque<Stage> remaining;
        final int delay;
        final CompletableFuture<Integer> done = new CompletableFuture<>();
        int countdown;
        int placed;

        Run(Deque<Stage> remaining, int delay, int placed) {
            this.remaining = remaining;
            this.delay = delay;
            this.countdown = delay;
            this.placed = placed;
        }

        /** Returns true once the run is finished (successfully or not). */
        boolean step() {
            if (done.isDone()) return true;
            if (--countdown > 0) return false;
            Stage stage = remaining.poll();
            try {
                if (!stage.place()) {
                    done.completeExceptionally(new StageFailure(placed, "stage " + placed + " placement failed", null));
                    return true;
                }
            } catch (Exception e) {
                done.completeExceptionally(new StageFailure(placed, "stage " + placed + " failed: " + e.getMessage(), e));
                return true;
            }
            placed++;
            if (remaining.isEmpty()) {
                done.complete(placed);
                return true;
            }
            countdown = delay;
            return false;
        }
    }
}
