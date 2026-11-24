package org.apache.camel.kit.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
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
        this(new TreeMap<>(adapters), Map.of());
    }

    public GraphTypeRegistry(Map<String, GraphTypeAdapter> adapters, Map<String, String> unavailable) {
        Map<String, GraphTypeAdapter> active = new LinkedHashMap<>();
        Map<String, String> failures = new TreeMap<>(unavailable);
        NodeType.CORE.forEach(t -> nodes.put(t.name(), t));
        EdgeType.CORE.forEach(t -> edges.put(t.name(), t));
        adapters.forEach((id, adapter) -> {
            try {
                var nodeTypes = mappings(id, nodes, adapter.nodeTypes());
                var edgeTypes = mappings(id, edges, adapter.edgeTypes());
                nodes.putAll(nodeTypes);
                edges.putAll(edgeTypes);
                active.put(id, adapter);
            } catch (RuntimeException | LinkageError e) {
                failures.put(id, e.toString());
            }
        });
        this.adapters = Collections.unmodifiableMap(active);
        this.unavailable = Collections.unmodifiableMap(failures);
    }

    private static <T extends GraphType> Map<String, T> mappings(
            String id, Map<String, T> registered,
            Map<String, T> declarations) {
        Map<String, T> result = new TreeMap<>();
        declarations.forEach((alias, type) -> {
            if (!type.name().startsWith(id + ":")
                    || (!alias.equals(type.name()) && !GraphType.LEGACY.matcher(alias).matches())) {
                throw new IllegalArgumentException("Invalid graph type mapping " + alias + " -> " + type);
            }
            T existing = registered.get(alias);
            if (existing != null) {
                String name = existing.name();
                int separator = name.indexOf(':');
                String owner = separator < 0 ? "core" : "provider/" + name.substring(0, separator);
                throw new IllegalArgumentException(
                        "Graph type alias " + alias + " already maps to " + name + " from " + owner);
            }
            result.put(alias, type);
            result.put(type.name(), type);
        });
        return result;
    }

    public Map<String, String> unavailable() {
        return unavailable;
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
