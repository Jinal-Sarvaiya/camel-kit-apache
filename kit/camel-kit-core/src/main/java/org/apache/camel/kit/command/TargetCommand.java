package org.apache.camel.kit.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Properties;

import org.apache.camel.kit.config.TargetCatalogEvidence;
import org.apache.camel.kit.config.TargetPomCheck;
import org.apache.camel.kit.config.TargetRuntimeProfile;
import org.apache.camel.kit.util.ConfinedPaths;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Option;

@CommandDefinition(name = "target", description = "Show the selected runtime profile or its Maven template",
                   generateHelp = true)
public class TargetCommand extends HelpAwareCommand {

    @Option(parser = KitOptionParser.class, name = "project-dir", defaultValue = ".", converter = PathConverter.class,
            description = "Initialized workspace")
    Path projectDir;

    @Option(parser = KitOptionParser.class, name = "migration", hasValue = false,
            description = "Use the migration destination selected in target.*")
    boolean migration;

    @Option(parser = KitOptionParser.class, name = "pom-template", hasValue = false,
            description = "Print the application POM template with target versions filled in")
    boolean pomTemplate;

    @Option(parser = KitOptionParser.class, name = "check-catalog", converter = PathConverter.class,
            description = "Check captured catalog results for this target (workspace-relative JSON)")
    Path catalogEvidence;

    @Option(parser = KitOptionParser.class, name = "check-effective-pom", converter = PathConverter.class,
            description = "Check the destination module's Maven help:effective-pom output")
    Path effectivePom;

    @Option(parser = KitOptionParser.class, name = "dependency-tree", converter = PathConverter.class,
            description = "Maven dependency:tree JSON for the same module as --check-effective-pom")
    Path dependencyTree;

    @Override
    public Integer call() {
        if (dependencyTree != null && effectivePom == null) {
            throw new UsageException("--dependency-tree requires --check-effective-pom");
        }
        if ((catalogEvidence != null || effectivePom != null) && pomTemplate) {
            throw new UsageException("Choose template output or target checks");
        }
        Path config = projectDir.resolve(".camel-kit/config.properties");
        try {
            ConfinedPaths.requirePath(projectDir, config);
            Properties properties = new Properties();
            try (var input = Files.newInputStream(config)) {
                properties.load(input);
            } catch (NoSuchFileException e) {
                throw new IOException(
                        "Missing " + config + ". Run init here or use --project-dir for an initialized workspace.");
            }
            var application = !migration && "existing".equals(properties.getProperty("project.type"))
                    ? TargetPomCheck.Application.EXISTING : TargetPomCheck.Application.GENERATED;
            TargetPomCheck model = null;
            if (effectivePom != null) {
                model = new TargetPomCheck(
                        read(effectivePom, 16 * 1024 * 1024),
                        dependencyTree == null ? null : read(dependencyTree, 16 * 1024 * 1024));
                if (application == TargetPomCheck.Application.EXISTING) {
                    model.completeExistingProfile(properties);
                }
            } else if (application == TargetPomCheck.Application.EXISTING
                    && "true".equals(properties.getProperty("project.needsResolvedCore"))
                    && !properties.containsKey("project.camelVersion")) {
                throw new IOException(
                        "Resolve project.camelVersion with --check-effective-pom and --dependency-tree for each Camel module");
            }
            TargetRuntimeProfile profile = TargetRuntimeProfile.resolve(properties, migration);
            if (model != null) {
                model.check(profile, application);
            }
            if (catalogEvidence != null) {
                TargetCatalogEvidence.check(profile, read(catalogEvidence, 65536));
            }
            out.println(pomTemplate ? profile.pomTemplate() : new ObjectMapper().writeValueAsString(profile));
            return 0;
        } catch (IllegalArgumentException | IOException e) {
            err.println("INCOMPLETE target check: " + e.getMessage());
            return 1;
        }
    }

    private byte[] read(Path relative, int limit) throws IOException {
        Path path = projectDir.resolve(relative).normalize();
        ConfinedPaths.requirePath(projectDir, path);
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException("Target evidence exceeds " + limit + " bytes: " + relative);
            }
            return bytes;
        }
    }
}
