package org.apache.camel.kit.command;

import java.io.IOException;
import java.nio.file.Files;
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

    @Override
    public Integer call() {
        Path config = projectDir.resolve(".camel-kit/config.properties");
        try {
            ConfinedPaths.requirePath(projectDir, config);
            Properties properties = new Properties();
            try (var input = Files.newInputStream(config)) {
                properties.load(input);
            }
            TargetRuntimeProfile profile = TargetRuntimeProfile.resolve(properties, migration);
            if ((catalogEvidence != null || effectivePom != null) && pomTemplate) {
                throw new IllegalArgumentException("Choose template output or target checks");
            }
            if (effectivePom != null) {
                TargetPomCheck.check(profile, read(effectivePom, 16 * 1024 * 1024));
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
