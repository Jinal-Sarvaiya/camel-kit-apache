package org.apache.camel.kit.config;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.camel.kit.util.TemplateUtils;

/** The destination used by the interactive workflows. Source discovery stays in project.* during migration. */
public record TargetRuntimeProfile(
        String runtime, String camelVersion, String applicationBom, String springBootVersion,
        List<String> repositories, String runtimeCatalogVersion, CatalogRequest catalog) {

    public record CatalogRequest(String runtime, String camelVersion, String platformBom) {
    }

    public static TargetRuntimeProfile resolve(Properties config, boolean migration) {
        String prefix = migration ? "target." : "project.";
        String runtime = config.getProperty(prefix + "runtime");
        String core = scalar(config.getProperty(prefix + "camelVersion"), prefix + "camelVersion");
        String bom = config.getProperty(prefix + "applicationBom");
        String binding = config.getProperty(prefix + "catalogBom");
        String boot = config.getProperty(prefix + "springBootVersion");
        String platform = config.getProperty(prefix + "platformBomVersion");
        if ("main".equals(runtime)) {
            if (bom != null || boot != null || (migration && platform != null)) {
                throw new IllegalArgumentException("Remove platform fields from the Main target");
            }
            binding = matchingBinding(binding, "org.apache.camel:camel-catalog:" + core);
        } else if ("spring-boot".equals(runtime) || "quarkus".equals(runtime)) {
            boolean spring = "spring-boot".equals(runtime);
            String artifact = spring ? "camel-spring-boot-bom" : "quarkus-camel-bom";
            if (bom == null && !migration && !"existing".equals(config.getProperty("project.type"))) {
                bom = (spring ? "org.apache.camel.springboot:" : "io.quarkus.platform:") + artifact + ":"
                      + scalar(platform, prefix + "platformBomVersion");
            }
            String[] gav = coordinate(bom, artifact, prefix + "applicationBom");
            if (platform != null && !platform.equals(gav[2])) {
                throw new IllegalArgumentException("Application BOM and platformBomVersion disagree");
            }
            if (spring) {
                if (migration || boot != null) {
                    boot = scalar(boot, prefix + "springBootVersion");
                }
                if (binding == null && !migration && "org.apache.camel.springboot".equals(gav[0])) {
                    binding = gav[0] + ":camel-catalog-provider-springboot:" + gav[2];
                }
                coordinate(binding, "camel-catalog-provider-springboot", prefix + "catalogBom");
            } else {
                if (boot != null) {
                    throw new IllegalArgumentException("Remove springBootVersion from the Quarkus target");
                }
                binding = matchingBinding(binding, bom);
            }
        } else {
            throw new IllegalArgumentException("Select " + prefix + "runtime (main, spring-boot or quarkus)");
        }
        String catalogVersion = binding.substring(binding.lastIndexOf(':') + 1);
        if ("quarkus".equals(runtime)) {
            catalogVersion = config.getProperty(prefix + "catalogVersion");
            if (migration || catalogVersion != null) {
                catalogVersion = scalar(catalogVersion, prefix + "catalogVersion");
            }
        }
        return new TargetRuntimeProfile(
                runtime, core, bom, boot, repositories(config), catalogVersion,
                new CatalogRequest(runtime, core, binding));
    }

    private static List<String> repositories(Properties config) {
        String repos = config.getProperty("camel.catalog.repos", DistributionConfig.loadBundled().camelCatalogRepos());
        List<String> repositories = Arrays.stream(repos.split(",", -1)).map(String::strip).toList();
        // Profiles are copied into design and verification reports. Credentials belong in Maven settings.
        for (String repository : repositories) {
            String url = repository.replaceFirst("^[A-Za-z0-9_.-]+=", "");
            try {
                URI uri = new URI(url);
                if (!uri.isOpaque() && uri.isAbsolute() && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                        && uri.getRawFragment() == null && ("file".equals(uri.getScheme()) || uri.getHost() != null)) {
                    continue;
                }
            } catch (URISyntaxException e) {
                // Do not include a malformed URL or credentials in the diagnostic.
            }
            throw new IllegalArgumentException("Use credential-free repository URLs in camel.catalog.repos");
        }
        return repositories;
    }

    public String requiredCatalogVersion() {
        if (runtimeCatalogVersion == null) {
            throw new IllegalArgumentException(
                    "Set project.catalogVersion to the camel-quarkus-catalog version managed by "
                                               + applicationBom);
        }
        return runtimeCatalogVersion;
    }

    private static String matchingBinding(String provided, String expected) {
        if (provided != null && !expected.equals(provided)) {
            throw new IllegalArgumentException("Catalog binding must be " + expected);
        }
        return expected;
    }

    private static String[] coordinate(String value, String artifact, String field) {
        String[] gav = value == null ? new String[0] : value.split(":", -1);
        if (gav.length != 3 || !gav[0].matches("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*") || !artifact.equals(gav[1])) {
            throw new IllegalArgumentException(
                    "Select " + field + " with coordinates groupId:" + artifact + ":version");
        }
        scalar(gav[2], field);
        return gav;
    }

    private static String scalar(String value, String field) {
        if (value == null || !value.matches("[0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Select an exact Maven version for " + field);
        }
        return value;
    }

    public String pomTemplate() throws IOException {
        if (applicationBom == null) {
            throw new IllegalArgumentException("Main uses no Maven application BOM");
        }
        String[] gav = applicationBom.split(":");
        String template = TemplateUtils.readTemplate("templates/pom-" + runtime + ".xml")
                .replace("[PLATFORM_BOM_GROUP_ID]", gav[0])
                .replace("[PLATFORM_BOM_VERSION]", gav[2]);
        return "spring-boot".equals(runtime)
                ? template.replace("[CAMEL_CORE_VERSION]", camelVersion)
                        .replace("[SPRING_BOOT_VERSION]", scalar(springBootVersion, "springBootVersion"))
                : template;
    }
}
