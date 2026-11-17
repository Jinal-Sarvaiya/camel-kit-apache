///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 21+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED

//REPOS central=https://repo1.maven.org/maven2/,apache_snap=https://repository.apache.org/snapshots
//REPOS central_snap=https://central.sonatype.com/repository/maven-snapshots/
//DEPS org.apache.camel:camel-kit-core:${camel.kit.version:0.4.2-SNAPSHOT}

package main;

import org.apache.camel.kit.CamelKitMain;

/**
 * JBang entry point for Camel-Kit CLI. Design Apache Camel integrations with AI coding assistants.
 */
public class CamelKit {

    public static void main(String... args) {
        CamelKitMain.run(args);
    }
}
