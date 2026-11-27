package example;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.parser.GraphParser;
import org.apache.camel.kit.graph.parser.MigrationParser;

public class LegacyFlowParser implements MigrationParser {
    private final List<String> warnings = new ArrayList<>();

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
                var flow = new LegacyGraphAdapter.Flow(new String(GraphParser.readSource(file), StandardCharsets.UTF_8).strip(), source);
                if (flow.name().isEmpty()) {
                    warnings.add("Missing flow name in " + source);
                    continue;
                }
                flow.addTo(fragment);
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
