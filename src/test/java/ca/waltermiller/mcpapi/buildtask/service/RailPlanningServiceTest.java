package ca.waltermiller.mcpapi.buildtask.service;

import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    @Test
    void computeTurnPenaltyOnlyAppliesOnActualTurns() {
        Map<String, Double> weights = Map.of("turn_cost", 3.5);

        assertThat(RailPlanningService.computeTurnPenalty(Direction.EAST, Direction.EAST, weights)).isZero();
        assertThat(RailPlanningService.computeTurnPenalty(Direction.EAST, Direction.SOUTH, weights)).isEqualTo(3.5);
        assertThat(RailPlanningService.computeTurnPenalty(null, Direction.SOUTH, weights)).isZero();
    }

    @Test
    void computeTerrainPenaltyMakesTunnelingMoreExpensiveThanBridgingByDefault() {
        Map<String, Double> weights = Map.of(
            "bridge_cost", 2.5,
            "tunnel_cost", 6.0,
            "water_surface_threshold", 63.0,
            "water_tunnel_penalty", 8.0
        );

        double bridgePenalty = RailPlanningService.computeTerrainPenalty(61, 63.0, weights);
        double tunnelPenalty = RailPlanningService.computeTerrainPenalty(67, 63.0, weights);
        double underwaterTunnelPenalty = RailPlanningService.computeTerrainPenalty(63, 60.0, weights);

        assertThat(bridgePenalty).isEqualTo(2.5);
        assertThat(tunnelPenalty).isEqualTo(12.0);
        assertThat(underwaterTunnelPenalty).isGreaterThan(tunnelPenalty);
    }
}
