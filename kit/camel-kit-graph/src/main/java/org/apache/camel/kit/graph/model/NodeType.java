package org.apache.camel.kit.graph.model;

import java.util.Set;

/** A graph node type identifier. */
public record NodeType(String name) implements GraphType {
    public NodeType {
        GraphType.validate(name);
    }

    public static final NodeType CLASS = new NodeType("CLASS");
    public static final NodeType METHOD = new NodeType("METHOD");
    public static final NodeType FIELD = new NodeType("FIELD");
    public static final NodeType CAMEL_ROUTE = new NodeType("CAMEL_ROUTE");
    public static final NodeType CAMEL_ENDPOINT = new NodeType("CAMEL_ENDPOINT");
    public static final NodeType CAMEL_PROCESSOR = new NodeType("CAMEL_PROCESSOR");
    public static final NodeType MAVEN_ARTIFACT = new NodeType("MAVEN_ARTIFACT");
    public static final NodeType CONFIG_PROPERTY = new NodeType("CONFIG_PROPERTY");
    public static final NodeType RESOURCE_FILE = new NodeType("RESOURCE_FILE");

    public static final Set<NodeType> CORE = Set.of(CLASS, METHOD, FIELD, CAMEL_ROUTE, CAMEL_ENDPOINT, CAMEL_PROCESSOR,
            MAVEN_ARTIFACT, CONFIG_PROPERTY, RESOURCE_FILE);

    @Override
    public String toString() {
        return name;
    }
}
