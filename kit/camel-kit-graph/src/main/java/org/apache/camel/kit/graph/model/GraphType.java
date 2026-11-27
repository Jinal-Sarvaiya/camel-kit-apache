package org.apache.camel.kit.graph.model;

import java.util.regex.Pattern;

/** A wire identifier, independent of whether its adapter is installed. */
public sealed interface GraphType permits NodeType, EdgeType {
    Pattern LEGACY = Pattern.compile("[A-Z][A-Z0-9_]*");
    Pattern QUALIFIED = Pattern.compile("[a-z][a-z0-9-]*:[a-z][a-z0-9-]*");

    String name();

    static void validate(String name) {
        if (name == null || !(LEGACY.matcher(name).matches() || QUALIFIED.matcher(name).matches())) {
            throw new IllegalArgumentException(
                    "Invalid graph type '" + name + "'; expected a legacy identifier or namespace:type");
        }
    }
}
