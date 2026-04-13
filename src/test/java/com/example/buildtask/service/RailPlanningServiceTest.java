package com.example.buildtask.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RailPlanningServiceTest {

    @Test
    void computeTrackProfileHeightsUsesRequestedAnchorsInsteadOfFallingIntoVoid() {
        List<Integer> surfaceHeights = List.of(63, 62, 61, 60, -64, -64, -64, -64, 63, 63);

        List<Integer> trackHeights = RailPlanningService.computeTrackProfileHeights(
            surfaceHeights, 63, 63, 1, 4);

        assertThat(trackHeights).hasSize(surfaceHeights.size());
        assertThat(trackHeights.get(0)).isEqualTo(63);
        assertThat(trackHeights.get(trackHeights.size() - 1)).isEqualTo(63);
        assertThat(trackHeights).allMatch(height -> height >= 59);
    }

    @Test
    void computeTrackProfileHeightsAllowsGentleAnchorToAnchorSlope() {
        List<Integer> surfaceHeights = List.of(70, 69, 68, 67, 66, 65);

        List<Integer> trackHeights = RailPlanningService.computeTrackProfileHeights(
            surfaceHeights, 70, 65, 1, 4);

        assertThat(trackHeights).containsExactly(70, 69, 68, 67, 66, 65);
    }
}
