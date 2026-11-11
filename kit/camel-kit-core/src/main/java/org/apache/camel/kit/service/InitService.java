package org.apache.camel.kit.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.camel.kit.catalog.CitrusSchemaDownloader;
import org.apache.camel.kit.config.AgentConfig;
import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.config.DistributionConfig;
import org.apache.camel.kit.generator.AgentGenerator;
import org.apache.camel.kit.generator.AgentGeneratorFactory;
import org.apache.camel.kit.generator.AtomicFileWriter;
import org.apache.camel.kit.generator.GeneratedAssetPreflight;
import org.apache.camel.kit.generator.InitContext;
import org.apache.camel.kit.generator.ManagedFiles;
import org.apache.camel.kit.generator.QuteTemplateEngine;
import org.apache.camel.kit.graph.GraphBuildResult;
import org.apache.camel.kit.graph.GraphBuilder;
import org.apache.camel.kit.graph.GraphSerializer;
import org.apache.camel.kit.graph.ParserDiagnostic;
import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.model.NodeType;
import org.apache.camel.kit.util.ConfinedPaths;
import org.apache.camel.kit.util.TemplateUtils;

/**
 * Initializes Camel-Kit workspaces.
 */
public class InitService {

    private static final String MAVEN_WRAPPER_VERSION = "3.9.9";

    /** Upper bound for the opt-in baseline test run. */
    private static final long BASELINE_TEST_TIMEOUT_SECONDS = 600;

    /**
     * Reports what initialization would do to each file an existing project may already own, without writing anything.
     * Runs the same validation as {@link #initialize}, so a request that would be rejected is rejected here too.
     */
    public List<ManagedFiles.Change> preview(InitRequest request) throws Exception {
        AgentConfig agent = AgentRegistry.get(request.agentName());
        if (agent == null) {
            throw new IllegalArgumentException(AgentRegistry.unsupportedAgentMessage(request.agentName()));
        }
        Path targetDir = request.targetDir();
        Path skillsDir = targetDir.resolve(agent.skillsDirectory());
        Path commandsDir = agent.generatesCommandStubs() ? targetDir.resolve(agent.commandDirectory()) : skillsDir;
        ManagedFiles files = managedFiles(request);
        InitContext genCtx = new InitContext(
                agent, request.agentName(), commandsDir, skillsDir, targetDir,
                request.commandPrefix(), request.distribution(), request.withCitrusMcp(), request.printer(), files);
        requireConfinedOutputs(targetDir, genCtx, isExistingCamelProject(targetDir, request.sourcePlatform()));
        AgentGeneratorFactory.create(request.agentName()).validate(genCtx);

        Set<Path> shared = new LinkedHashSet<>();
        shared.add(targetDir.resolve("AGENTS.md"));
        for (var template : AgentRegistry.descriptor(request.agentName()).templates()) {
            shared.add(targetDir.resolve(template.target()));
        }
        shared.add(targetDir.resolve("docs/constitution.md"));
        List<ManagedFiles.Change> changes = new ArrayList<>();
        for (Path file : shared) {
            if (files.isShared(file)) {
                changes.add(change(targetDir, file, files.plan(file)));
            }
        }
        for (Map.Entry<Path, ManagedFiles.Action> wrapperFile : mavenWrapperPlan(targetDir, files).entrySet()) {
            changes.add(change(targetDir, wrapperFile.getKey(), wrapperFile.getValue()));
        }
        Path mcpConfig = targetDir.resolve(agent.mcpConfigPath());
        changes.add(change(targetDir, mcpConfig,
                Files.exists(mcpConfig) ? ManagedFiles.Action.MERGE : ManagedFiles.Action.CREATE));
        return changes;
    }

    private static ManagedFiles managedFiles(InitRequest request) throws IOException {
        List<String> replacements = new ArrayList<>(request.replace());
        if (request.replaceWrapper()) {
            for (String path : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")) {
                if (Files.exists(request.targetDir().resolve(path), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    replacements.add(path);
                }
            }
        }
        return ManagedFiles.open(request.targetDir(), replacements);
    }

    private static ManagedFiles.Change change(Path targetDir, Path file, ManagedFiles.Action action) {
        return new ManagedFiles.Change(targetDir.relativize(file).toString().replace('\\', '/'), action, null);
    }

    /**
     * What happens to each Maven wrapper file, shared by preview and execution.
     *
     * <p>
     * A project without a wrapper of its own gets the complete wrapper. Once any wrapper file belongs to the project,
     * Camel-Kit adds no further file to it: it writes only files that are already its own or were named for
     * replacement, and leaves the rest untouched.
     */
    private static Map<Path, ManagedFiles.Action> mavenWrapperPlan(Path projectDir, ManagedFiles files)
            throws IOException {
        Map<Path, ManagedFiles.Action> plan = new LinkedHashMap<>();
        for (String wrapperFile : List.of(".mvn/wrapper/maven-wrapper.properties", "mvnw", "mvnw.cmd")) {
            Path file = projectDir.resolve(wrapperFile);
            plan.put(file, files.plan(file));
        }
        if (plan.containsValue(ManagedFiles.Action.PRESERVE)) {
            plan.values().removeIf(action -> action == ManagedFiles.Action.CREATE);
        }
        return plan;
    }

    public InitResult initialize(InitRequest request) throws Exception {
        AgentConfig agent = AgentRegistry.get(request.agentName());
        if (agent == null) {
            throw new IllegalArgumentException(AgentRegistry.unsupportedAgentMessage(request.agentName()));
        }

        Path targetDir = request.targetDir();
        Path camelKitDir = targetDir.resolve(".camel-kit");
        Path skillsDir = targetDir.resolve(agent.skillsDirectory());
        Path commandsDir = agent.generatesCommandStubs()
                ? targetDir.resolve(agent.commandDirectory())
                : skillsDir;
        Path docsDir = targetDir.resolve("docs");
        String citrusVersion = request.resolvedCitrusVersion();

        boolean isExistingProject = isExistingCamelProject(targetDir, request.sourcePlatform());
        if (request.dryRun()) {
            return performDryRun(request, agent);
        }

        // Opened before anything is written: a workspace is recognized as existing only by what was there on entry.
        ManagedFiles files = managedFiles(request);
        InitContext genCtx = new InitContext(
                agent, request.agentName(), commandsDir,
                skillsDir, targetDir,
                request.commandPrefix(), request.distribution(), request.withCitrusMcp(), request.printer(), files);
        AgentGenerator generator = AgentGeneratorFactory.create(request.agentName());
        requireConfinedOutputs(targetDir, genCtx, isExistingProject);
        generator.preflight(genCtx);

        List<Path> createdPaths = new ArrayList<>();
        List<InitWarning> warnings = new ArrayList<>();
        InitProgress progress = request.progress();

        progress.startTask("\uD83D\uDCC1", "Creating project structure");
        createProjectStructure(
                targetDir, camelKitDir, docsDir, createdPaths);
        progress.finishTask();

        try {
            progress.startTask("\uD83D\uDCDD", "Writing configuration");
            createConfigFile(camelKitDir, request.projectName(), request.agentName(), agent, request, createdPaths,
                    isExistingProject);
            createConstitution(docsDir, request, files, createdPaths);
            copyAdditionalTemplates(camelKitDir.resolve("templates"), createdPaths);
            progress.finishTask();

            progress.startTask("\uD83E\uDD16", "Generating " + agent.name() + " workspace");
            generator.generate(genCtx);
            progress.finishTask();

            progress.startTask("\uD83D\uDD0C", "Configuring Maven wrapper");
            if (createMavenWrapper(targetDir, files, createdPaths)) {
                request.reporter().mavenWrapperCreated(MAVEN_WRAPPER_VERSION);
            } else {
                request.reporter().mavenWrapperPreserved();
            }
            progress.finishTask();
        } finally {
            files.save();
        }
        for (ManagedFiles.Change conflict : files.conflicts()) {
            request.reporter().conflict(conflict);
        }
        for (String unused : files.unusedReplacements()) {
            reportWarning(request, warnings, "--replace " + unused + " had no effect: the file does not exist.");
        }

        progress.startTask("\uD83D\uDD0D", "Building project graph");
        InitGraphSummary graph = buildProjectGraph(targetDir, camelKitDir, request, warnings, createdPaths);
        progress.finishTask();

        int citrusSchemaCount = 0;
        if (!request.noFetch()) {
            // After graph build: runtime detection may have rewritten project.camelVersion (and forage.version).
            cacheForageCatalog(camelKitDir, request, warnings);
            progress.startTask("\u2B07\uFE0F", "Downloading Citrus schemas");
            citrusSchemaCount = fetchCitrusSchemas(camelKitDir, citrusVersion, request, warnings);
            progress.finishTask();
        }

        return new InitResult(
                request.projectName(), request.agentName(), agent, targetDir, camelKitDir, commandsDir, skillsDir,
                citrusVersion, citrusSchemaCount, MAVEN_WRAPPER_VERSION, graph, createdPaths, warnings,
                files.conflicts());
    }

    /** Files init writes under {@code .camel-kit/} for an existing project (recorded baseline). */
    private static final List<String> BASELINE_OUTPUTS = List.of(
            ".camel-kit/baseline-routes.txt", ".camel-kit/baseline-tests.txt",
            ".camel-kit/baseline-contracts.txt", ".camel-kit/baseline-test-results.json");

    private void requireConfinedOutputs(Path targetDir, InitContext genCtx, boolean existingProject)
            throws IOException {
        Set<Path> outputs = new LinkedHashSet<>();
        for (String relative : List.of(".camel-kit/.cache", ".camel-kit/config.properties",
                ManagedFiles.LEDGER, ".camel-kit/project-graph.json", ".mvn/wrapper/maven-wrapper.properties", "mvnw",
                "mvnw.cmd",
                "AGENTS.md", "CLAUDE.md",
                "docs/constitution.md", "docs/flows", "test/data", "schemas")) {
            outputs.add(Path.of(relative));
        }
        if (existingProject) {
            BASELINE_OUTPUTS.forEach(relative -> outputs.add(Path.of(relative)));
        }
        try {
            for (Path output : outputs) {
                ConfinedPaths.requirePath(targetDir, targetDir.resolve(output));
            }
            ConfinedPaths.requireTree(targetDir, targetDir.resolve(".camel-kit/templates"));
            ConfinedPaths.requireTree(targetDir, targetDir.resolve(ManagedFiles.PROPOSALS));
            GeneratedAssetPreflight.check(genCtx);
        } catch (IOException e) {
            throw new IOException("Initialization aborted: " + e.getMessage(), e);
        }
    }

    private void createProjectStructure(
            Path targetDir, Path camelKitDir, Path docsDir, List<Path> createdPaths)
            throws Exception {
        createDirectory(targetDir, createdPaths);
        createDirectory(camelKitDir, createdPaths);
        createDirectory(docsDir.resolve("flows"), createdPaths);
        createDirectory(camelKitDir.resolve("templates"), createdPaths);
        createDirectory(camelKitDir.resolve(".cache"), createdPaths);
        createDirectory(targetDir.resolve("test/data"), createdPaths);
        createDirectory(targetDir.resolve("schemas"), createdPaths);
    }

    private void createDirectory(Path path, List<Path> createdPaths) throws Exception {
        Files.createDirectories(path);
        createdPaths.add(path);
    }

    private void createConfigFile(
            Path dir,
            String name,
            String agentName,
            AgentConfig agent,
            InitRequest request,
            List<Path> createdPaths,
            boolean isExistingProject)
            throws Exception {
        Path configFile = dir.resolve("config.properties");
        Properties config = new Properties();
        if (Files.isRegularFile(configFile)) {
            // Reinitialization keeps properties it does not manage, such as ones the user added.
            try (var in = Files.newInputStream(configFile)) {
                config.load(in);
            }
        }
        // Discovery must not retain constraints from an earlier scan when declarations disappear or conflict.
        for (String key : List.of("project.runtime", "project.camelVersion", "project.detectedCamelVersion",
                "project.platformBomVersion", "project.detectedPlatformBomVersion", "project.springBootVersion",
                "project.primaryDsl", "project.dslTypes", "project.hasTests", "project.baselineTestStatus",
                "project.routeCount", "project.contractFilesCount", "project.discoveryGaps",
                "project.unsupportedCombination", "project.modules", "project.moduleCount", "project.beansCount",
                "project.configFilesCount", "project.unsupportedVersion", "project.unsupportedReason",
                "forage.version")) {
            config.remove(key);
        }
        config.setProperty("project.name", name);
        config.setProperty("project.type", isExistingProject ? "existing" : "greenfield");
        config.setProperty("project.command-prefix", request.commandPrefix());
        config.setProperty("agent.name", agentName);
        config.setProperty("agent.folder", agent.folder());
        config.setProperty("citrus.version", request.resolvedCitrusVersion());
        if (request.withCitrusMcp()) {
            config.setProperty("citrus.mcp.version", request.distribution().citrusMcpVersion());
            config.setProperty("citrus.mcp.enabled", "true");
        } else {
            config.setProperty("citrus.mcp.enabled", "false");
            config.remove("citrus.mcp.version");
        }
        // For greenfield projects only: seed distribution defaults
        // Existing projects get versions from discovery in updateConfigWithRuntime
        if (!isExistingProject) {
            writeVersionConfig(config, request.distribution(), "main");
        }
        if (request.sourcePlatform() != null && !"auto".equals(request.sourcePlatform())) {
            config.setProperty("project.sourcePlatform", request.sourcePlatform());
        } else {
            config.remove("project.sourcePlatform");
        }

        var out = new ByteArrayOutputStream();
        config.store(out, "Camel-Kit Project Configuration");
        AtomicFileWriter.write(configFile, out.toByteArray());
        createdPaths.add(configFile);
    }

    private void cacheForageCatalog(Path camelKitDir, InitRequest request, List<InitWarning> warnings)
            throws Exception {
        Properties config = new Properties();
        try (var in = Files.newInputStream(camelKitDir.resolve("config.properties"))) {
            config.load(in);
        }
        String forageVersion = config.getProperty("forage.version");
        if (forageVersion == null) {
            return;
        }
        try {
            new ForageCatalogService().cache(camelKitDir, request.distribution().forageCatalogArtifact(),
                    forageVersion);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reportWarning(request, warnings,
                    "Forage catalog could not be cached (interrupted) — Forage support disabled for this "
                                             + "workspace; skills fall back to component properties/beans.");
        } catch (Exception e) {
            reportWarning(request, warnings,
                    "Forage catalog could not be cached (" + e.getMessage()
                                             + ") — Forage support disabled for this workspace; skills fall back to component properties/beans.");
        }
    }

    private void createConstitution(Path dir, InitRequest request, ManagedFiles files, List<Path> createdPaths)
            throws Exception {
        QuteTemplateEngine qute = new QuteTemplateEngine();
        String template = TemplateUtils.readTemplate("templates/constitution.md");

        String content = qute.renderString(template, Map.of(
                "DATE", LocalDate.now(ZoneId.systemDefault()).toString(),
                "CAMEL_VERSION", request.distribution().camelMainVersion()));
        Path constitution = dir.resolve("constitution.md");
        if (files.write(constitution, content)) {
            createdPaths.add(constitution);
        }
    }

    private void copyAdditionalTemplates(Path templatesDir, List<Path> createdPaths) throws Exception {
        String[] additionalTemplates = {
                "patterns-foundational.md",
                "patterns-error-handling.md",
                "patterns-deployment.md",
                "yaml-structure.md",
                "yaml-components.md",
                "yaml-examples.md",
                "validation-completeness.md",
                "validation-constitution.md",
                "validation-testing.md",
                "flow.md",
                "pom-quarkus.xml",
                "pom-spring-boot.xml"
        };
        for (String template : additionalTemplates) {
            String content = TemplateUtils.readTemplate("templates/" + template);
            Path target = templatesDir.resolve(template);
            AtomicFileWriter.write(target, content);
            createdPaths.add(target);
        }
    }

    /**
     * Installs the Maven wrapper files selected by {@link #mavenWrapperPlan}.
     *
     * @return {@code false} when a wrapper file of the project's own was preserved
     */
    private boolean createMavenWrapper(Path projectDir, ManagedFiles files, List<Path> createdPaths)
            throws Exception {
        Map<Path, ManagedFiles.Action> plan = mavenWrapperPlan(projectDir, files);
        String wrapperProps
                = String.format(Locale.ROOT,
                        """
                                distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%s/apache-maven-%s-bin.zip
                                wrapperUrl=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar
                                """,
                        MAVEN_WRAPPER_VERSION, MAVEN_WRAPPER_VERSION);
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put(".mvn/wrapper/maven-wrapper.properties", wrapperProps);
        contents.put("mvnw", TemplateUtils.readTemplate("maven/mvnw"));
        contents.put("mvnw.cmd", TemplateUtils.readTemplate("maven/mvnw.cmd"));

        boolean preserved = false;
        for (Map.Entry<String, String> content : contents.entrySet()) {
            Path file = projectDir.resolve(content.getKey());
            ManagedFiles.Action action = plan.get(file);
            if (action == ManagedFiles.Action.PRESERVE) {
                files.preserve(file);
                preserved = true;
            } else if (action != null && files.write(file, content.getValue())) {
                if ("mvnw".equals(content.getKey())) {
                    file.toFile().setExecutable(true);
                }
                createdPaths.add(file);
            }
        }
        return !preserved;
    }

    private InitGraphSummary buildProjectGraph(
            Path targetDir,
            Path camelKitDir,
            InitRequest request,
            List<InitWarning> warnings,
            List<Path> createdPaths)
            throws Exception {
        Path graphFile = camelKitDir.resolve("project-graph.json");
        try {
            GraphBuildResult result = new GraphBuilder().buildWithDiagnostics(targetDir);
            for (ParserDiagnostic diagnostic : result.warningDiagnostics()) {
                reportWarning(request, warnings, "Project graph warning: " + diagnostic.summary());
            }
            if (!result.successful()) {
                throw new IllegalStateException(result.failureSummary());
            }
            ProjectGraph projectGraph = result.graph();
            if (projectGraph.nodeCount() > 0) {
                Path temporary = Files.createTempFile(camelKitDir, "project-graph-", ".json");
                try {
                    GraphSerializer.write(projectGraph, temporary, targetDir.toAbsolutePath().toString());
                    AtomicFileWriter.replace(temporary, graphFile);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                createdPaths.add(graphFile);
                String detectedProjectType = detectProjectType(projectGraph);
                String detectedRuntime
                        = updateConfigWithRuntime(camelKitDir, projectGraph, request.distribution(), request, warnings,
                                targetDir);
                InitGraphSummary graph = new InitGraphSummary(
                        graphFile,
                        projectGraph.nodeCount(),
                        projectGraph.edgeCount(),
                        detectedProjectType,
                        detectedRuntime);
                request.reporter().graphBuilt(graph);
                return graph;
            }
            InitGraphSummary graph = new InitGraphSummary(graphFile, 0, 0, "", "");
            request.reporter().graphSkipped();
            return graph;
        } catch (IllegalStateException e) {
            // Rethrow fatal errors for existing projects (e.g., undetectable Camel version)
            // Graph parse failures are converted to warnings instead
            if (e.getMessage() != null && e.getMessage().contains("Cannot detect Camel version")) {
                throw e;
            }
            reportWarning(request, warnings, "Could not build project graph: " + e.getMessage());
            recordUnresolvedDiscovery(camelKitDir, e.getMessage());
            return null;
        } catch (Exception e) {
            reportWarning(request, warnings, "Could not build project graph: " + e.getMessage());
            recordUnresolvedDiscovery(camelKitDir, e.getMessage());
            return null;
        }
    }

    /**
     * An existing project whose graph could not be built has no discovered constraints at all. Record that in the
     * configuration so later stages stop and inspect the project instead of treating the absent versions as "nothing
     * declared". Greenfield projects are unaffected.
     */
    private void recordUnresolvedDiscovery(Path camelKitDir, String reason) {
        Path configFile = camelKitDir.resolve("config.properties");
        try {
            Properties config = new Properties();
            try (var in = Files.newInputStream(configFile)) {
                config.load(in);
            }
            if (!"existing".equals(config.getProperty("project.type"))) {
                return;
            }
            config.setProperty("project.discoveryGaps",
                    "Project discovery failed: " + reason + ". No runtime or version constraints were detected. "
                                                        + "Fix the project files and run init again, or inspect them before planning.");
            var out = new ByteArrayOutputStream();
            config.store(out, "Camel-Kit Project Configuration");
            AtomicFileWriter.write(configFile, out.toByteArray());
        } catch (Exception e) {
            // Best-effort: the warning has already been reported.
        }
    }

    private int fetchCitrusSchemas(
            Path camelKitDir, String citrusVersion, InitRequest request, List<InitWarning> warnings) {
        try {
            CitrusSchemaDownloader citrusDownloader = new CitrusSchemaDownloader(camelKitDir.resolve(".cache"));
            citrusDownloader.fetchCitrusSchemas(citrusVersion, false, request.printer()::println);
            Path citrusSchemasDir = citrusDownloader.getCitrusSchemasDir(citrusVersion);
            if (Files.exists(citrusSchemasDir)) {
                try (var paths = Files.walk(citrusSchemasDir)) {
                    return (int) paths
                            .filter(p -> p.toString().endsWith(".json"))
                            .count();
                }
            }
        } catch (Exception e) {
            reportWarning(request, warnings, "Could not fetch Citrus schemas: " + e.getMessage());
        }
        return 0;
    }

    private void reportWarning(InitRequest request, List<InitWarning> warnings, String message) {
        InitWarning warning = new InitWarning(message);
        warnings.add(warning);
        request.reporter().warning(warning);
    }

    private String updateConfigWithRuntime(
            Path dir,
            ProjectGraph graph,
            DistributionConfig distribution,
            InitRequest request,
            List<InitWarning> warnings,
            Path projectRoot)
            throws Exception {
        Path configFile = dir.resolve("config.properties");
        Properties config = new Properties();

        if (Files.exists(configFile)) {
            try (var in = Files.newInputStream(configFile)) {
                config.load(in);
            }
        }

        // Use ProjectDiscovery for comprehensive metadata
        org.apache.camel.kit.graph.ProjectDiscovery.DiscoveryResult discovery
                = org.apache.camel.kit.graph.ProjectDiscovery.discover(graph);

        boolean isExistingProject = "existing".equals(config.getProperty("project.type"));
        String runtime = discovery.runtime();

        if (isExistingProject) {
            // Existing projects: only values the project itself declares are recorded as constraints. Nothing is
            // seeded from the distribution, so an unresolved value is absent (and reported), never a default.
            if (runtime != null) {
                config.setProperty("project.runtime", normalizeRuntime(runtime));
            } else {
                config.remove("project.runtime");
            }

            if (discovery.camelVersion() != null) {
                config.setProperty("project.camelVersion", discovery.camelVersion());
                config.setProperty("project.detectedCamelVersion", discovery.camelVersion());
            } else if (!discovery.hasConflict()) {
                // ERROR: Cannot proceed with undetectable version on existing project
                throw new IllegalStateException(
                        "Cannot detect Camel version from existing project. "
                                                + "Add a camel.version property to pom.xml, or specify the version explicitly with "
                                                + "-p project.camelVersion=<version>. "
                                                + "Refusing to guess a version for an existing project.");
            }
            // With conflicting evidence neither value is locked; the gap below tells the user what to resolve.

            if (discovery.platformBomVersion() != null) {
                config.setProperty("project.platformBomVersion", discovery.platformBomVersion());
                config.setProperty("project.detectedPlatformBomVersion", discovery.platformBomVersion());
            }
            if (discovery.springBootVersion() != null) {
                config.setProperty("project.springBootVersion", discovery.springBootVersion());
            }

            if (!discovery.dslTypes().isEmpty()) {
                config.setProperty("project.primaryDsl", discovery.primaryDsl());
                // Record all DSL types for compatibility checks
                config.setProperty("project.dslTypes",
                        discovery.dslTypes().stream()
                                .map(Enum::name)
                                .sorted()
                                .collect(java.util.stream.Collectors.joining(",")));
            }
            config.setProperty("project.hasTests", String.valueOf(discovery.hasTests()));
            if (discovery.hasTests()) {
                // Record baseline test files for regression verification
                writeBaselineTests(dir, projectRoot);
                if (request.recordBaseline()) {
                    // Opt-in: executes the project's own build and tests
                    recordBaselineTestResults(dir, projectRoot, config);
                } else {
                    // Default: do not run project code during init; validation establishes the baseline later
                    writeBaselineStatus(dir.resolve("baseline-test-results.json"), "not_run",
                            "Baseline tests were not run during init (use --record-baseline to run them)",
                            projectRoot);
                    config.setProperty("project.baselineTestStatus", "not_run");
                }
            }
            if (!discovery.routes().isEmpty()) {
                config.setProperty("project.routeCount", String.valueOf(discovery.routes().size()));
                // Record baseline route files for regression verification
                writeBaselineRoutes(dir, discovery.routeFiles(), projectRoot);
            }

            // Interface contract files (OpenAPI, WSDL, XSD, ...) are inventoried so later stages can protect them.
            // Analysing their content is out of scope here (tracked separately in #111).
            List<String> contractFiles = findContractFiles(projectRoot);
            if (!contractFiles.isEmpty()) {
                config.setProperty("project.contractFilesCount", String.valueOf(contractFiles.size()));
                writeBaselineLines(dir.resolve("baseline-contracts.txt"), contractFiles, projectRoot);
            }

            // Gaps the graph could not fill (e.g. routes defined in unsupported layouts): workflow must inspect source
            List<String> gaps = discovery.coverageGaps();
            if (!gaps.isEmpty()) {
                config.setProperty("project.discoveryGaps", String.join(" | ", gaps));
                gaps.forEach(gap -> reportWarning(request, warnings, gap));
            }

            // Unsupported runtime/DSL combinations are reported, never silently planned around
            List<String> unsupported = discovery.unsupportedCombinationReasons();
            if (!unsupported.isEmpty()) {
                config.setProperty("project.unsupportedCombination", String.join(" ", unsupported));
                unsupported.forEach(reason -> reportWarning(request, warnings, reason));
            }

            // Record modules (for multi-module Maven projects)
            if (!discovery.modules().isEmpty()) {
                config.setProperty("project.modules", String.join(",", discovery.modules()));
                config.setProperty("project.moduleCount", String.valueOf(discovery.modules().size()));
            }

            // Record beans count (Spring, CDI, Camel beans)
            if (discovery.beansCount() > 0) {
                config.setProperty("project.beansCount", String.valueOf(discovery.beansCount()));
            }

            // Record configuration files count
            if (discovery.configFilesCount() > 0) {
                config.setProperty("project.configFilesCount", String.valueOf(discovery.configFilesCount()));
            }

            // Flag unsupported versions
            if (discovery.isUnsupportedVersion()) {
                config.setProperty("project.unsupportedVersion", "true");
                config.setProperty("project.unsupportedReason",
                        "Camel 3.x is not supported. Please upgrade to Camel 4.x.");
                reportWarning(request, warnings,
                        "Camel " + discovery.camelVersion() + " is not supported. Please upgrade to Camel 4.x "
                                                 + "before adding features.");
            }

            // forage.version must track project.camelVersion
            String camelVersion = config.getProperty("project.camelVersion");
            if (camelVersion != null) {
                String forageVersion = distribution.forageVersionForCamel(camelVersion);
                if (forageVersion != null) {
                    config.setProperty("forage.version", forageVersion);
                } else {
                    config.remove("forage.version");
                }
            }
        } else {
            // For greenfield projects: use distribution defaults
            writeVersionConfig(config, distribution, runtime);
        }

        var out = new ByteArrayOutputStream();
        config.store(out, "Camel-Kit Project Configuration");
        AtomicFileWriter.write(configFile, out.toByteArray());
        return runtime == null ? "" : runtime;
    }

    private void writeVersionConfig(Properties config, DistributionConfig distribution, String runtime) {
        String normalizedRuntime = normalizeRuntime(runtime);
        config.setProperty("project.runtime", normalizedRuntime);
        switch (normalizedRuntime) {
            case "quarkus" -> {
                String camelVersion = distribution.camelQuarkusVersion();
                config.setProperty("project.camelVersion", camelVersion);
                config.setProperty("project.platformBomVersion", distribution.quarkusPlatformForVersion(camelVersion));
                config.remove("project.springBootVersion");
            }
            case "spring-boot" -> {
                String camelVersion = distribution.camelSpringbootVersion();
                config.setProperty("project.camelVersion", camelVersion);
                config.setProperty("project.platformBomVersion", distribution.springbootBomVersion());
                // Only spring-boot projects need the framework version (spring-boot-maven-plugin lookup).
                config.setProperty("project.springBootVersion",
                        distribution.springBootMappings().getOrDefault(camelVersion, distribution.springBootVersion()));
            }
            default -> {
                config.remove("project.springBootVersion");
                config.setProperty("project.camelVersion", distribution.camelMainVersion());
                config.setProperty("project.platformBomVersion", distribution.camelMainVersion());
            }
        }
        // forage.version must always track project.camelVersion, including after graph-based runtime rewrites.
        String forageVersion = distribution.forageVersionForCamel(config.getProperty("project.camelVersion"));
        if (forageVersion != null) {
            config.setProperty("forage.version", forageVersion);
        } else {
            config.remove("forage.version");
        }
    }

    private String normalizeRuntime(String runtime) {
        if (runtime == null || runtime.isBlank() || "camel-main".equals(runtime) || "jbang".equals(runtime)) {
            return "main";
        }
        return runtime;
    }

    /**
     * Determines if the target directory contains an existing Camel 4 project that should be enhanced (as opposed to a
     * greenfield project or a migration source like MuleSoft/BizTalk).
     *
     * @param  targetDir      the project directory
     * @param  sourcePlatform the --source-platform flag value (or null)
     * @return                true if this is an existing Camel 4 project to enhance
     */
    private boolean isExistingCamelProject(Path targetDir, String sourcePlatform) {
        // If user explicitly requested migration from another platform, treat as greenfield target
        if (sourcePlatform != null && !"auto".equals(sourcePlatform)) {
            return false;
        }

        // Must have pom.xml to be an existing Maven project
        Path pomXml = targetDir.resolve("pom.xml");
        if (!Files.exists(pomXml)) {
            return false;
        }

        // Quick heuristic: if it has MuleSoft markers, it's a migration source, not an existing Camel project
        // (Full graph analysis happens later; this is just for early project.type classification)
        try {
            String pomContent = Files.readString(pomXml);
            // Check for MuleSoft group IDs
            if (pomContent.contains("org.mule.runtime") || pomContent.contains("com.mulesoft")) {
                return false;
            }
            // Check for BizTalk project markers (no pom.xml for BizTalk, but defensive check)
            Path bizTalkProject = targetDir.resolve("project.btproj");
            if (Files.exists(bizTalkProject)) {
                return false;
            }
            // If it has Camel artifacts, it's likely an existing Camel project
            if (pomContent.contains("org.apache.camel")) {
                return true;
            }
        } catch (Exception e) {
            // If we can't read pom.xml, treat as greenfield
            return false;
        }

        // Has pom.xml but no Camel artifacts - treat as greenfield
        return false;
    }

    private String detectProjectType(ProjectGraph graph) {
        var types = new ArrayList<String>();

        if (!graph.findByType(NodeType.MULE_FLOW).isEmpty()) {
            int flows = graph.findByType(NodeType.MULE_FLOW).size();
            int subFlows = graph.findByType(NodeType.MULE_SUB_FLOW).size();
            int dwl = graph.findByType(NodeType.DATAWEAVE_SCRIPT).size();
            String muleVersion = findMavenProperty(graph, "mule.version");
            if (muleVersion == null)
                muleVersion = findMavenProperty(graph, "app.runtime");
            if (muleVersion == null)
                muleVersion = findArtifactVersion(graph, "org.mule.runtime", "mule-core");
            if (muleVersion == null)
                muleVersion = findArtifactVersion(graph, "org.mule", "mule-core");
            types.add("MuleSoft Mule" + (muleVersion != null ? " " + muleVersion : "")
                      + " (" + flows + " flows, " + subFlows + " sub-flows"
                      + (dwl > 0 ? ", " + dwl + " DataWeave scripts" : "") + ")");
        }

        int orchs = graph.findByType(NodeType.BIZTALK_ORCHESTRATION).size();
        int maps = graph.findByType(NodeType.BIZTALK_MAP).size();
        int pipelines = graph.findByType(NodeType.BIZTALK_PIPELINE).size();
        if (orchs > 0 || maps > 0 || pipelines > 0) {
            types.add("Microsoft BizTalk (" + orchs + " orchestrations"
                      + (maps > 0 ? ", " + maps + " maps" : "")
                      + (pipelines > 0 ? ", " + pipelines + " pipelines" : "") + ")");
        }

        if (!graph.findByType(NodeType.CAMEL_ROUTE).isEmpty()) {
            int routes = graph.findByType(NodeType.CAMEL_ROUTE).size();
            String camelVersion = detectCamelVersion(graph);
            String platform = detectCamelPlatform(graph);
            types.add(platform + (camelVersion != null ? " " + camelVersion : "")
                      + " (" + routes + " routes)");
        }

        if (!graph.findByType(NodeType.MAVEN_ARTIFACT).isEmpty() && types.isEmpty()) {
            types.add("Maven project");
        }

        return String.join(", ", types);
    }

    private String detectCamelVersion(ProjectGraph graph) {
        String version = findMavenProperty(graph, "camel.version");
        if (version != null)
            return version;

        version = findArtifactVersion(graph, "org.apache.camel", "camel-core");
        if (version != null)
            return version;

        version = findArtifactVersion(graph, "org.apache.camel", "camel-bom");
        if (version != null)
            return version;

        for (var node : graph.findByType(NodeType.MAVEN_ARTIFACT)) {
            if ("org.apache.camel".equals(node.properties().get("groupId"))) {
                String v = node.properties().get("version");
                if (v != null && !"managed".equals(v) && !"unknown".equals(v) && !v.contains("${")) {
                    return v;
                }
            }
        }

        return null;
    }

    private String detectCamelPlatform(ProjectGraph graph) {
        for (var node : graph.findByType(NodeType.MAVEN_ARTIFACT)) {
            String file = node.properties().get("file");
            if (file != null && file.contains("pom.xml")) {
                String groupId = node.properties().getOrDefault("groupId", "");
                if (groupId.contains("jboss.fuse") || groupId.contains("fusesource")) {
                    return "JBoss Fuse";
                }
            }
        }

        for (var node : graph.findByType(NodeType.MAVEN_ARTIFACT)) {
            String v = node.properties().getOrDefault("version", "");
            if (v.contains(".fuse-") || v.contains(".redhat-")) {
                return "JBoss Fuse";
            }
        }

        return "Apache Camel";
    }

    private String findMavenProperty(ProjectGraph graph, String propertyName) {
        var node = graph.getNode("maven-property:" + propertyName);
        if (node != null) {
            String value = node.properties().get("value");
            if (value != null && !value.isEmpty() && !value.contains("${")) {
                return value;
            }
        }
        return null;
    }

    private String findArtifactVersion(ProjectGraph graph, String groupId, String artifactId) {
        String nodeId = "maven:" + groupId + ":" + artifactId;
        var node = graph.getNode(nodeId);
        if (node != null) {
            String version = node.properties().get("version");
            if (version != null && !"managed".equals(version) && !"unknown".equals(version)
                    && !version.contains("${")) {
                return version;
            }
        }
        return null;
    }

    private InitResult performDryRun(InitRequest request, AgentConfig agent) throws Exception {
        List<ManagedFiles.Change> changes = preview(request);
        request.printer().println("Dry run — nothing was written");
        for (ManagedFiles.Change change : changes) {
            request.printer().println("  " + change.action() + "  " + change.path());
        }
        Path targetDir = request.targetDir();
        Path skillsDir = targetDir.resolve(agent.skillsDirectory());
        Path commandsDir = agent.generatesCommandStubs() ? targetDir.resolve(agent.commandDirectory()) : skillsDir;
        return new InitResult(
                request.projectName(), request.agentName(), agent, targetDir,
                targetDir.resolve(".camel-kit"), commandsDir, skillsDir, request.resolvedCitrusVersion(), 0,
                MAVEN_WRAPPER_VERSION, null, List.of(), List.of(),
                changes.stream().filter(change -> change.action() == ManagedFiles.Action.PRESERVE).toList());
    }

    /**
     * Records the baseline test result by running the project's own tests once. Only called when the user opted in with
     * {@code --record-baseline}. The status is one of {@code passed}, {@code failed}, {@code timeout} or
     * {@code not_run}; a project that has no Maven wrapper, or whose run cannot start, is recorded as {@code not_run}
     * with the reason so validation can establish the baseline itself.
     *
     * @param camelKitDir .camel-kit directory
     * @param projectRoot project root directory
     * @param config      config properties to update
     */
    private void recordBaselineTestResults(Path camelKitDir, Path projectRoot, java.util.Properties config) {
        Path mvnw = projectRoot.resolve("mvnw");
        Path baselineResultsFile = camelKitDir.resolve("baseline-test-results.json");

        if (!Files.exists(mvnw)) {
            writeBaselineStatus(baselineResultsFile, "not_run", "Maven wrapper not found", projectRoot);
            config.setProperty("project.baselineTestStatus", "not_run");
            return;
        }

        Path output = null;
        try {
            mvnw.toFile().setExecutable(true);
            // Send output to a file so the child can never block on a full pipe buffer
            output = Files.createTempFile("camel-kit-baseline-", ".log");
            // Capture the timestamp before starting the test process so we can reject stale reports.
            Instant testStartTime = Instant.now();
            Process testProcess = new ProcessBuilder()
                    .command("./mvnw", "test", "-B", "-q")
                    .directory(projectRoot.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();

            boolean finished = testProcess.waitFor(BASELINE_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                testProcess.destroyForcibly();
                writeBaselineStatus(baselineResultsFile, "timeout",
                        "Tests timed out after " + BASELINE_TEST_TIMEOUT_SECONDS + " seconds", projectRoot);
                config.setProperty("project.baselineTestStatus", "timeout");
                return;
            }

            // Per-test outcomes and the revision they belong to: validation compares test identities, never totals.
            // Only collect reports from this invocation; stale reports are rejected.
            var collectionResult = BaselineTestResults.collect(projectRoot, testStartTime);
            var tests = collectionResult.outcomes();
            int exitCode = testProcess.exitValue();

            // If no fresh reports were produced, the build likely failed before tests or tests were skipped.
            // Exit 0 with no reports is not the same as "tests passed"; it means tests did not run.
            if (tests.isEmpty() && exitCode == 0) {
                writeBaselineStatus(baselineResultsFile, "not_run",
                        "Tests did not run (no reports were produced despite successful Maven exit)", projectRoot);
                config.setProperty("project.baselineTestStatus", "not_run");
                return;
            }

            String status = exitCode == 0 ? "passed" : "failed";
            String reason = exitCode == 0
                    ? "All tests passed"
                    : "Tests had failures (baseline state)";
            if (tests.isEmpty()) {
                reason += "; no Surefire/Failsafe reports were produced, so per-test outcomes are unavailable";
            } else if (!collectionResult.complete()) {
                reason += "; " + collectionResult.failedReportCount() + " report(s) could not be parsed"
                          + " (incomplete evidence, validation will report UNVERIFIED)";
            }
            writeBaselineFile(projectRoot, baselineResultsFile,
                    BaselineTestResults.toJson(status, reason, sourceRevision(projectRoot),
                            sourceTreeDirty(projectRoot), collectionResult.complete(), tests));
            config.setProperty("project.baselineTestStatus", status);
        } catch (Exception e) {
            writeBaselineStatus(baselineResultsFile, "not_run", "Could not execute tests: " + e.getMessage(),
                    projectRoot);
            config.setProperty("project.baselineTestStatus", "not_run");
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // temp file cleanup only
                }
            }
        }
    }

    /** Git HEAD of the project, or {@code unknown} when it is not a Git work tree. Read-only. */
    private String sourceRevision(Path projectRoot) {
        return runGit(projectRoot, "rev-parse", "HEAD").map(String::trim).filter(v -> !v.isEmpty()).orElse("unknown");
    }

    /**
     * True when a tracked file has uncommitted changes, or when that cannot be determined. Untracked files are ignored
     * on purpose: init's own output (.camel-kit, agent skills, AGENTS.md) is untracked at this point and must not make
     * every baseline look dirty.
     */
    private boolean sourceTreeDirty(Path projectRoot) {
        return runGit(projectRoot, "status", "--porcelain", "--untracked-files=no")
                .map(out -> !out.isBlank()).orElse(true);
    }

    private java.util.Optional<String> runGit(Path projectRoot, String... args) {
        Path output = null;
        try {
            output = Files.createTempFile("camel-kit-git-", ".out");
            List<String> command = new ArrayList<>(List.of("git", "-C", projectRoot.toString()));
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return java.util.Optional.empty();
            }
            return process.exitValue() == 0
                    ? java.util.Optional.of(Files.readString(output))
                    : java.util.Optional.empty();
        } catch (Exception e) {
            return java.util.Optional.empty();
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // temp file cleanup only
                }
            }
        }
    }

    /**
     * Writes a baseline status that has no per-test outcomes ({@code not_run} or {@code timeout}). It carries the same
     * fields as a full baseline, with an empty test list, so validation never has to special-case it.
     */
    private void writeBaselineStatus(Path file, String status, String reason, Path projectRoot) {
        try {
            // Error statuses (not_run, timeout) have no collection, so complete=true (no parse failures)
            writeBaselineFile(projectRoot, file,
                    BaselineTestResults.toJson(status, reason, sourceRevision(projectRoot),
                            sourceTreeDirty(projectRoot), true, java.util.Map.of()));
        } catch (Exception e) {
            // Best-effort - ignore write failures
        }
    }

    private void writeBaselineTests(Path camelKitDir, Path projectRoot) {
        try {
            Path testDir = projectRoot.resolve("src/test/java");
            if (!Files.isDirectory(testDir)) {
                return;
            }

            List<String> testFiles = new java.util.ArrayList<>();
            try (var paths = Files.walk(testDir)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith("Test.java")
                                || p.toString().endsWith("Tests.java")
                                || p.toString().endsWith("IT.java"))
                        .forEach(p -> {
                            try {
                                testFiles.add(testDir.relativize(p).toString());
                            } catch (IllegalArgumentException e) {
                                // Skip if cannot relativize
                            }
                        });
            }

            if (!testFiles.isEmpty()) {
                Path baselineFile = camelKitDir.resolve("baseline-tests.txt");
                writeBaselineFile(projectRoot, baselineFile, inventoryText(testFiles.stream().sorted().toList()));
            }
        } catch (Exception e) {
            // Non-fatal - baseline recording is best-effort
        }
    }

    /**
     * Write baseline route inventory for existing projects to enable regression verification. Records the route source
     * files discovered so that validation can distinguish pre-existing routes from newly added capabilities.
     *
     * @param camelKitDir .camel-kit directory
     * @param routeFiles  unique route source files from discovery
     * @param projectRoot project root directory for relativizing absolute paths
     */
    private void writeBaselineRoutes(Path camelKitDir, List<String> routeFiles, Path projectRoot) {
        try {
            Path baselineFile = camelKitDir.resolve("baseline-routes.txt");
            List<String> relativePaths = routeFiles.stream()
                    .map(r -> {
                        Path routePath = Path.of(r);
                        if (routePath.isAbsolute()) {
                            try {
                                return projectRoot.relativize(routePath).toString();
                            } catch (IllegalArgumentException e) {
                                return r; // Cannot relativize, use as-is
                            }
                        }
                        return r;
                    })
                    .sorted()
                    .toList();
            writeBaselineFile(projectRoot, baselineFile, inventoryText(relativePaths));
        } catch (Exception e) {
            // Non-fatal - baseline recording is best-effort
        }
    }

    private static final java.util.regex.Pattern CONTRACT_FILE = java.util.regex.Pattern.compile(
            "(?i)(.*\\.(wsdl|xsd|proto|avsc))|((openapi|swagger|asyncapi)[^/]*\\.(ya?ml|json))");
    private static final java.util.Set<String> SKIPPED_DIRECTORIES
            = java.util.Set.of("target", "node_modules", ".git", ".camel-kit", "build");

    /**
     * Lists interface contract files (OpenAPI/Swagger/AsyncAPI documents, WSDL, XSD, protobuf, Avro schemas) relative
     * to the project root, sorted. Build output and tooling directories are skipped.
     */
    private List<String> findContractFiles(Path projectRoot) {
        try (var paths = Files.walk(projectRoot)) {
            return paths.filter(Files::isRegularFile)
                    .map(projectRoot::relativize)
                    .filter(rel -> {
                        for (Path segment : rel.getParent() == null ? List.<Path>of() : rel.getParent()) {
                            if (SKIPPED_DIRECTORIES.contains(segment.toString())) {
                                return false;
                            }
                        }
                        return CONTRACT_FILE.matcher(rel.getFileName().toString()).matches();
                    })
                    .map(rel -> rel.toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            // Non-fatal - baseline recording is best-effort
            return List.of();
        }
    }

    /**
     * Writes one recorded-baseline file. Every baseline output goes through here so it is confined below the project
     * root (no symbolic-link redirection) and replaced atomically (a hard link to an outside file is never written
     * through).
     */
    private void writeBaselineFile(Path projectRoot, Path file, String content) throws IOException {
        org.apache.camel.kit.util.ConfinedPaths.requirePath(projectRoot, file);
        AtomicFileWriter.write(file, content);
    }

    /**
     * One record per line, every line (including the last) terminated by a line feed, so shell consumers such as
     * {@code while read} see the final entry. An empty inventory is an empty file.
     */
    static String inventoryText(List<String> lines) {
        return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
    }

    private void writeBaselineLines(Path file, List<String> lines, Path projectRoot) {
        try {
            writeBaselineFile(projectRoot, file, inventoryText(lines));
        } catch (Exception e) {
            // Non-fatal - baseline recording is best-effort
        }
    }
}
