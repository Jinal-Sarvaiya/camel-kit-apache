package org.apache.camel.kit.graph;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.camel.kit.graph.model.*;

/** Resolves type aliases within one distribution. Unknown wire types remain ordinary values. */
public final class GraphTypeRegistry {
    private final Map<String, NodeType> nodes = new TreeMap<>();
    private final Map<String, EdgeType> edges = new TreeMap<>();
    private final Map<String, GraphTypeAdapter> adapters;
    private final Map<String, String> unavailable;

    public GraphTypeRegistry(Map<String, GraphTypeAdapter> adapters) {
        this(adapters, Map.of());
    }

    public GraphTypeRegistry(Map<String, GraphTypeAdapter> adapters, Map<String, String> unavailable) {
        this.adapters = Collections.unmodifiableMap(new TreeMap<>(adapters));
        this.unavailable = Map.copyOf(unavailable);
        NodeType.CORE.forEach(t -> nodes.put(t.name(), t));
        EdgeType.CORE.forEach(t -> edges.put(t.name(), t));
        this.adapters.forEach((id, adapter) -> {
            register(id, nodes, adapter.nodeTypes());
            register(id, edges, adapter.edgeTypes());
        });
    }

    private static <T extends GraphType> void register(String id, Map<String, T> types, Map<String, T> declarations) {
        declarations.forEach((alias, type) -> {
            if (!GraphType.QUALIFIED.matcher(type.name()).matches() || !type.name().startsWith(id + ":")
                    || !alias.equals(type.name()) && !GraphType.LEGACY.matcher(alias).matches()) {
                throw new IllegalArgumentException(
                        "provider/" + id + ": invalid graph type mapping " + alias + " -> " + type);
            }
            if (!alias.equals(type.name()) && types.putIfAbsent(alias, type) != null) {
                throw new IllegalArgumentException(
                        "provider/" + id + ": graph type alias already registered: " + alias);
            }
            types.put(type.name(), type);
        });
    }

    public NodeType nodeType(String name) {
        NodeType known = nodes.get(name);
        return known != null ? known : new NodeType(name);
    }

    public EdgeType edgeType(String name) {
        EdgeType known = edges.get(name);
        return known != null ? known : new EdgeType(name);
    }

    public Set<String> nodeNames() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    public Set<String> edgeNames() {
        return Collections.unmodifiableSet(edges.keySet());
    }

    public Map<String, GraphTypeAdapter> adapters() {
        return adapters;
    }

    public GraphTypeAdapter requireAdapter(String id) {
        GraphTypeAdapter adapter = adapters.get(id);
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "Specialized graph analysis unavailable for provider/" + id + ": "
                                               + unavailable.getOrDefault(id, "not selected in this distribution"));
        }
        return adapter;
    }
}
