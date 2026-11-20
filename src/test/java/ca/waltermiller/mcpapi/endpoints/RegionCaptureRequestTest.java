package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Box;
import ca.waltermiller.mcpapi.arealock.AreaBounds;
import ca.waltermiller.mcpapi.endpoints.RegionCaptureRequest.RegionException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class RegionCaptureRequestTest {
    private static Map<String, String> query(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    @Test void cuboidCornersAreNormalised() {
        var request = RegionCaptureRequest.parse(query("x1", "10", "y1", "70", "z1", "-5", "x2", "0", "y2", "60", "z2", "5",
            "world", "minecraft:the_nether"));
        assertThat(request.cuboid()).isEqualTo(new AreaBounds(0, 60, -5, 10, 70, 5));
        assertThat(request.world()).isEqualTo("minecraft:the_nether");
        assertThat(request.buildId()).isNull();
    }

    @Test void buildFormUsesDefaultsAndExpands() {
        var request = RegionCaptureRequest.parse(query("build_id", "00000000-0000-0000-0000-000000000001"));
        assertThat(request.margin()).isEqualTo(RegionCaptureRequest.DEFAULT_MARGIN);
        assertThat(request.aroundBuild(new Box(0, 64, 0, 9, 72, 9)))
            .isEqualTo(new AreaBounds(-6, 61, -6, 15, 76, 15));
        var tight = RegionCaptureRequest.parse(query("build_id", "00000000-0000-0000-0000-000000000001",
            "margin", "0", "below", "0", "above", "1"));
        assertThat(tight.aroundBuild(new Box(0, 64, 0, 9, 72, 9))).isEqualTo(new AreaBounds(0, 64, 0, 9, 73, 9));
    }

    @Test void rejectsMixedMissingAndMalformedInput() {
        assertInvalid(query());
        assertInvalid(query("x1", "0", "y1", "0", "z1", "0", "x2", "1", "y2", "1"));
        assertInvalid(query("x1", "0", "y1", "0", "z1", "0", "x2", "1", "y2", "1", "z2", "1", "build_id", "00000000-0000-0000-0000-000000000001"));
        assertInvalid(query("x1", "a", "y1", "0", "z1", "0", "x2", "1", "y2", "1", "z2", "1"));
        assertInvalid(query("x1", "0", "y1", "0", "z1", "0", "x2", "1", "y2", "1", "z2", "1", "margin", "2"));
        assertInvalid(query("build_id", "nope"));
        assertInvalid(query("build_id", "00000000-0000-0000-0000-000000000001", "margin", "33"));
        assertInvalid(query("build_id", "00000000-0000-0000-0000-000000000001", "below", "-1"));
    }

    @Test void clampsToWorldHeight() {
        assertThat(RegionCaptureRequest.clampY(new AreaBounds(0, -100, 0, 1, 400, 1), -64, 319))
            .isEqualTo(new AreaBounds(0, -64, 0, 1, 319, 1));
        assertThatThrownBy(() -> RegionCaptureRequest.clampY(new AreaBounds(0, 400, 0, 1, 500, 1), -64, 319))
            .isInstanceOf(RegionException.class);
    }

    @Test void volumeCapWithoutOverflow() {
        assertThat(RegionCaptureRequest.checkVolume(new AreaBounds(0, 0, 0, 9, 9, 9), 1000)).isEqualTo(1000);
        for (var bounds : new AreaBounds[] {new AreaBounds(0, 0, 0, 10, 9, 9),
            new AreaBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)}) {
            assertThatThrownBy(() -> RegionCaptureRequest.checkVolume(bounds, 1000))
                .isInstanceOfSatisfying(RegionException.class, e -> assertThat(e.code()).isEqualTo("region_too_large"));
        }
    }

    private static void assertInvalid(Map<String, String> query) {
        assertThatThrownBy(() -> RegionCaptureRequest.parse(query)).as(query.toString())
            .isInstanceOfSatisfying(RegionException.class, e -> {
                assertThat(e.status()).isEqualTo(400);
                assertThat(e.code()).isEqualTo("invalid_request");
            });
    }
}
