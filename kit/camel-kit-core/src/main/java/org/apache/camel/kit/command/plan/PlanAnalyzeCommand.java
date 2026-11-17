package org.apache.camel.kit.command.plan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.PathConverter;
import org.apache.camel.kit.plan.PlanAnalyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Argument;

@CommandDefinition(name = "analyze", description = "Analyze plan for parallel execution waves")
public class PlanAnalyzeCommand extends CliCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Argument(index = "0", description = "Path to plan markdown",
              defaultValue = "docs/implementation-plan.md",
              converter = PathConverter.class)
    Path planFile;

    @Override
    public Integer call() {
        try {
            String markdown = Files.readString(planFile);
            PlanAnalyzer analyzer = new PlanAnalyzer();
            var tasks = analyzer.parseTasks(markdown);
            System.out.println(analyzer.toJson(tasks));
            return 0;
        } catch (Exception e) {
            try {
                System.err.println(MAPPER.writeValueAsString(Map.of(
                        "error", "plan-analysis-failed",
                        "message", String.valueOf(e.getMessage()))));
            } catch (Exception ignored) {
                System.err.println("{\"error\":\"plan-analysis-failed\"}");
            }
            return 1;
        }
    }
}
