package org.apache.camel.kit.jbang;

import java.io.InputStream;
import java.util.Properties;

public class KitVersionProvider {

    private static final String POM_PROPERTIES
            = "META-INF/maven/org.apache.camel/camel-jbang-plugin-kit/pom.properties";

    public String[] getVersion() throws Exception {
        Properties props = new Properties();
        try (InputStream is = KitVersionProvider.class.getResourceAsStream("/" + POM_PROPERTIES)) {
            if (is != null) {
                props.load(is);
            }
        }
        return new String[]{props.getProperty("version", "unknown")};
    }
}
