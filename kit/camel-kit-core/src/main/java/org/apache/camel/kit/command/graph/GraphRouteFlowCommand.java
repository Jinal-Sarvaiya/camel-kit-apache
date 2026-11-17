package org.apache.camel.kit.command.graph;

import java.util.List;

import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.query.RouteFlowTracer;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

@CommandDefinition(name = "route-flow", description = "Trace message path through a route")
public class GraphRouteFlowCommand extends GraphQueryCommand {
    @Argument(required = true, index = "0")
    String routeId;
    @Option(parser = KitOptionParser.class, name = "from-endpoint", defaultValue = "false",
            hasValue = false)
    boolean fromEndpoint;

    @Override
    protected String execute(ProjectGraph graph) {
        RouteFlowTracer tracer = new RouteFlowTracer(graph);
        List<RouteFlowTracer.FlowStep> steps = fromEndpoint ? tracer.traceFromEndpoint(routeId) : tracer.trace(routeId);
        ObjectNode root = GraphJsonWriter.createObject();
        root.put("found", !steps.isEmpty());
        ArrayNode arr = root.putArray("steps");
        steps.forEach(s -> {
            ObjectNode o = arr.addObject();
            o.put("nodeId", s.nodeId());
            o.put("type", s.type());
            o.put("label", s.label());
            o.put("depth", s.depth());
        });
        return GraphJsonWriter.toJson(root);
    }
}
