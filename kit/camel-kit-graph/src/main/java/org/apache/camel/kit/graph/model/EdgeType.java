package org.apache.camel.kit.graph.model;

import java.util.Set;

/** A graph edge type identifier. */
public record EdgeType(String name) implements GraphType {
    public EdgeType {
        GraphType.validate(name);
    }

    public static final EdgeType EXTENDS = new EdgeType("EXTENDS");
    public static final EdgeType IMPLEMENTS = new EdgeType("IMPLEMENTS");
    public static final EdgeType DECLARES = new EdgeType("DECLARES");
    public static final EdgeType CALLS = new EdgeType("CALLS");
    public static final EdgeType USES_TYPE = new EdgeType("USES_TYPE");
    public static final EdgeType ROUTES_FROM = new EdgeType("ROUTES_FROM");
    public static final EdgeType ROUTES_TO = new EdgeType("ROUTES_TO");
    public static final EdgeType PROCESSES = new EdgeType("PROCESSES");
    public static final EdgeType LINKS_TO = new EdgeType("LINKS_TO");
    public static final EdgeType DEPENDS_ON = new EdgeType("DEPENDS_ON");
    public static final EdgeType USES_COMPONENT = new EdgeType("USES_COMPONENT");
    public static final EdgeType CONFIGURES = new EdgeType("CONFIGURES");
    public static final EdgeType INJECTS_INTO = new EdgeType("INJECTS_INTO");
    public static final EdgeType INSTANTIATES = new EdgeType("INSTANTIATES");
    public static final EdgeType REFERENCES_BEAN = new EdgeType("REFERENCES_BEAN");
    public static final EdgeType REFERENCES_PROPERTY = new EdgeType("REFERENCES_PROPERTY");
    public static final EdgeType DEPENDS_ON_VIA_INTERFACE = new EdgeType("DEPENDS_ON_VIA_INTERFACE");

    public static final Set<EdgeType> CORE = Set.of(EXTENDS, IMPLEMENTS, DECLARES, CALLS, USES_TYPE, ROUTES_FROM,
            ROUTES_TO, PROCESSES, LINKS_TO, DEPENDS_ON, USES_COMPONENT, CONFIGURES, INJECTS_INTO, INSTANTIATES,
            REFERENCES_BEAN, REFERENCES_PROPERTY, DEPENDS_ON_VIA_INTERFACE);

    @Override
    public String toString() {
        return name;
    }
}
