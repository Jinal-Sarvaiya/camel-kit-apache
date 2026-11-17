package org.apache.camel.kit;

import java.io.InputStream;
import java.util.Properties;

public class VersionProvider {

    private static final String POM_PROPERTIES = "META-INF/maven/org.apache.camel/camel-kit-core/pom.properties";

    public String[] getVersion() throws Exception {
        Properties props = new Properties();
        try (InputStream is = VersionProvider.class.getResourceAsStream("/" + POM_PROPERTIES)) {
            if (is != null) {
                props.load(is);
            }
        }
        return new String[]{props.getProperty("version", "unknown")};
    }
}
