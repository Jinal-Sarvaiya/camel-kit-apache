package example;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.model.GraphNode;
import org.apache.camel.kit.graph.model.NodeType;
import org.apache.camel.kit.graph.parser.GraphParser;
import org.apache.camel.kit.graph.parser.MigrationParser;

public class LegacyFlowParser implements MigrationParser {
    private final List<String> warnings = new ArrayList<>();

    private record Flow(String name, String source) {
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public Set<String> inputExtensions() {
        return Set.of("flow");
    }

    @Override
    public void parseFiles(Path root, List<Path> files, ProjectGraph fragment) {
        GraphParser.requireSafeFiles(root, files);
        for (Path file : files) {
            String source = GraphParser.relativeFileName(root, file);
            try {
                Flow flow = new Flow(new String(GraphParser.readSource(file), StandardCharsets.UTF_8).strip(), source);
                if (flow.name().isEmpty()) {
                    warnings.add("Missing flow name in " + source);
                    continue;
                }
                fragment.addNode(new GraphNode("legacy-flow:" + flow.source(), NodeType.RESOURCE_FILE,
                        Map.of("name", flow.name(), "file", flow.source())));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + source, e);
            }
        }
    }

    @Override
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    @Override
    public void resetWarnings() {
        warnings.clear();
    }
}
