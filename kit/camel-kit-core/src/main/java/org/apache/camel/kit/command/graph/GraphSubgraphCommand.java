package org.apache.camel.kit.command.graph;

import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.query.GraphQuery;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

@CommandDefinition(name = "subgraph", description = "All nodes within N hops of a node")
public class GraphSubgraphCommand extends GraphQueryCommand {
    @Argument(required = true, index = "0")
    String nodeId;
    @Option(parser = KitOptionParser.class, name = "hops", defaultValue = "2")
    int hops;

    @Override
    protected String execute(ProjectGraph graph) {
        GraphQuery.SubgraphResult result = new GraphQuery(graph).subgraph(nodeId, hops);
        ObjectNode root = GraphJsonWriter.createObject();
        root.set("nodes", GraphJsonWriter.nodesToArray(result.nodes()));
        root.set("edges", GraphJsonWriter.edgesToArray(result.edges()));
        return GraphJsonWriter.toJson(root);
    }
}
