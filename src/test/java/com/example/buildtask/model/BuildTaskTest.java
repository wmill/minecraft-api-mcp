package com.example.buildtask.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BuildTaskTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void markCompletedSetsStatusAndClearsError() {
        BuildTask task = new BuildTask();
        task.setErrorMessage("old error");

        task.markCompleted();

        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getExecutedAt()).isNotNull();
        assertThat(task.getErrorMessage()).isNull();
    }

    @Test
    void resetForReplayRestoresQueuedState() {
        BuildTask task = new BuildTask();
        task.markFailed("boom");

        task.resetForReplay();

        assertThat(task.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(task.getExecutedAt()).isNull();
        assertThat(task.getErrorMessage()).isNull();
    }

    @Test
    void setTaskDataRecomputesCoordinates() {
        ObjectNode fill = mapper.createObjectNode();
        fill.put("x1", 5);
        fill.put("y1", 10);
        fill.put("z1", 15);
        fill.put("x2", 7);
        fill.put("y2", 11);
        fill.put("z2", 18);
        fill.put("block_type", "minecraft:stone");

        BuildTask task = new BuildTask(UUID.randomUUID(), 0, TaskType.BLOCK_FILL, fill, "fill");

        assertThat(task.getCoordinates()).isNotNull();
        assertThat(task.getCoordinates().getMinX()).isEqualTo(5);
        assertThat(task.getCoordinates().getMaxZ()).isEqualTo(18);
    }
}
