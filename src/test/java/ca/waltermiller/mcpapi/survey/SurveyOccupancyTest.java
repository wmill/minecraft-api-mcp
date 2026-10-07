package ca.waltermiller.mcpapi.survey;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import ca.waltermiller.mcpapi.arealock.AreaLockService;
import ca.waltermiller.mcpapi.buildtask.model.Build;
import ca.waltermiller.mcpapi.buildtask.repository.BuildRepository;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SurveyOccupancyTest {
    @Test void reservationsFilterCapAndDoNotRenewOrExposeTokens() {
        var locks = new AreaLockService();
        var first = locks.acquire("world", new AreaBounds(0, 10, 0, 0, 20, 0), "a".repeat(200));
        for (int x = 1; x < 7; x++) locks.acquire("world", new AreaBounds(x, 10, 0, x, 20, 0), "parcel");
        locks.acquire("other", new AreaBounds(0, 10, 0, 0, 20, 0), "other world");
        var result = SurveyOccupancy.reservations(locks, "world", new AreaBounds(0, -64, 0, 6, 319, 0));
        assertThat(result.total()).isEqualTo(7);
        assertThat(result.truncated()).isTrue();
        assertThat(result.entries()).hasSize(5).allSatisfy(e -> assertThat(e).doesNotContainKey("lock_id"));
        assertThat(result.entries().getFirst().get("label").toString()).hasSize(80);
        assertThat(locks.list("world", null).getFirst().get("expires_at")).isEqualTo(first.get("expires_at"));
        assertThat(SurveyOccupancy.reservations(locks, "world", new AreaBounds(100, -64, 0, 101, 319, 0)).total()).isZero();
    }

    @Test void buildHistoryIncludesNonCompletedRecordsAndReportsFailures() throws Exception {
        var service = new SurveyOccupancy();
        var bounds = new AreaBounds(0, -64, 0, 39, 319, 39);
        assertThat(service.builds("world", bounds).status()).isEqualTo("unavailable");
        var repository = mock(BuildRepository.class);
        when(repository.findByLocationIntersection(eq("world"), any())).thenReturn(
            IntStream.range(0, 7).mapToObj(i -> new Build("build " + i, "", "world")).toList());
        service.setBuildRepository(repository);
        var result = service.builds("world", bounds);
        assertThat(result.total()).isEqualTo(7);
        assertThat(result.entries()).hasSize(5).allSatisfy(e -> assertThat(e.get("status")).isEqualTo("CREATED"));
        assertThat(result.truncated()).isTrue();
        when(repository.findByLocationIntersection(any(), any())).thenThrow(new SQLException("offline"));
        assertThat(service.builds("world", bounds).total()).isNull();
        assertThat(service.builds("world", bounds).status()).isEqualTo("unavailable");
    }
}
