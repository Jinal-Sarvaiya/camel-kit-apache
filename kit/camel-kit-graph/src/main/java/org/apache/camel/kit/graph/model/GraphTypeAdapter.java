package org.apache.camel.kit.graph.model;

import java.util.Map;
import java.util.Optional;

import org.apache.camel.kit.graph.ProjectGraph;

/** A catalog-selected schema and its domain analysis. The provider ID owns its type namespace. */
public interface GraphTypeAdapter {
    /** Map wire names to their canonical types. A type without a legacy alias maps its own name. */
    Map<String, NodeType> nodeTypes();

    Map<String, EdgeType> edgeTypes();

    /** Describe the migration evidence in the graph. Empty means the adapter found no matching project. */
    Optional<String> summarize(ProjectGraph graph);
}
