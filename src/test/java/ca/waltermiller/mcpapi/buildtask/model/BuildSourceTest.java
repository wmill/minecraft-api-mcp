package ca.waltermiller.mcpapi.buildtask.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuildSourceTest {
    private final ObjectMapper json = new ObjectMapper();

    private BuildSource parse(String body) throws Exception {
        return BuildSource.fromJson(json.readTree(body));
    }

    @Test
    void parsesStarlarkProvenance() throws Exception {
        BuildSource source = parse("""
            {"kind": "starlark", "artifact_id": "slk_0123456789abcdef", "source": "def build(): pass",
             "entry": "build", "props": {"height": 12}, "root_size": [9, 12, 9], "y_offset": -1,
             "target": {"x": 10, "y": 70, "z": -4}, "details": {"library_loads": ["tower@2"]}}
            """);
        assertThat(source.kind()).isEqualTo("starlark");
        assertThat(source.props().get("height").asInt()).isEqualTo(12);
        assertThat(source.yOffset()).isEqualTo(-1);
        assertThat(source.buildId()).isNull();

        UUID id = UUID.randomUUID();
        Map<String, Object> full = source.withBuildId(id).toMap(true);
        assertThat(full).containsEntry("source", "def build(): pass").doesNotContainKey("has_source");
        Map<String, Object> summary = source.toMap(false);
        assertThat(summary).doesNotContainKey("source").containsEntry("has_source", true);
    }

    @Test
    void schematicProvenanceNeedsNoSource() throws Exception {
        BuildSource source = parse("{\"kind\": \"schematic\", \"schematic_id\": \"12345\"}");
        assertThat(source.toMap(false)).containsEntry("schematic_id", "12345").containsEntry("has_source", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[]",
        "{}",
        "{\"kind\": \"mod\"}",
        "{\"kind\": \"starlark\", \"surprise\": 1}",
        "{\"kind\": \"starlark\", \"source\": 5}",
        "{\"kind\": \"starlark\", \"props\": [1]}",
        "{\"kind\": \"starlark\", \"root_size\": [1, 2]}",
        "{\"kind\": \"starlark\", \"root_size\": [1, 2, \"3\"]}",
        "{\"kind\": \"starlark\", \"y_offset\": 1.5}",
        "{\"kind\": \"starlark\", \"target\": {\"x\": 1, \"y\": 2}}",
        "{\"kind\": \"starlark\", \"details\": \"text\"}",
    })
    void rejectsMalformedProvenance(String body) {
        assertThrows(IllegalArgumentException.class, () -> parse(body));
    }

    @Test
    void rejectsOversizedSource() {
        var node = json.createObjectNode().put("kind", "starlark").put("source", "x".repeat(BuildSource.MAX_SOURCE_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> BuildSource.fromJson(node));
    }
}
