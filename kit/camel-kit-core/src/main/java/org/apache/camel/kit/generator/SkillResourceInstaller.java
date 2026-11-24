package org.apache.camel.kit.generator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.camel.kit.config.AgentGeneratorStrategy;
import org.apache.camel.kit.doc.FrontmatterHandler;
import org.apache.camel.kit.util.AnsiColors;
import org.apache.camel.kit.workflow.SkillCatalog;
import org.apache.camel.kit.workflow.WorkflowManifest;

class SkillResourceInstaller {

    private static final Pattern EMPHASIS_TOKENS = Pattern.compile("\\\\[\\\\*]|\\*++|\\R");

    /** Agents whose generated Ship entry point relays controller tasks to a read-only native subagent. */
    static final Set<String> NATIVE_SHIP_AGENTS = Set.of(
            AgentGeneratorStrategy.BOB2.descriptorValue(),
            AgentGeneratorStrategy.COPILOT.descriptorValue(),
            AgentGeneratorStrategy.CLAUDE.descriptorValue(),
            AgentGeneratorStrategy.CODEX.descriptorValue(),
            AgentGeneratorStrategy.ANTIGRAVITY.descriptorValue(),
            AgentGeneratorStrategy.QWEN.descriptorValue(),
            AgentGeneratorStrategy.OPENCODE.descriptorValue());

    private final DispatchBlockAppender dispatchBlockAppender;
    private final VersionPlaceholderResolver versionPlaceholderResolver;

    SkillResourceInstaller() {
        this(new DispatchBlockAppender(), new VersionPlaceholderResolver());
    }

    SkillResourceInstaller(
                           DispatchBlockAppender dispatchBlockAppender,
                           VersionPlaceholderResolver versionPlaceholderResolver) {
        this.dispatchBlockAppender = dispatchBlockAppender;
        this.versionPlaceholderResolver = versionPlaceholderResolver;
    }

    void install(InitContext ctx, SkillCatalog catalog) throws Exception {
        Files.createDirectories(ctx.skillsDir());
        List<String> failures = new ArrayList<>();
        Pattern codexInvocations = AgentGeneratorStrategy.CODEX.descriptorValue().equals(ctx.agentName())
                ? codexInvocationPattern(catalog.manifest().workflow()) : null;
        for (String path : catalog.paths()) {
            try (InputStream source = catalog.open(path)) {
                copySkillFile(ctx, source, ctx.skillFile(path), catalog.manifest(), codexInvocations);
            } catch (Exception e) {
                failures.add(path + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new IOException("Failed to copy skill resources: " + String.join("; ", failures));
        }
        long folders = catalog.paths().stream().map(path -> path.substring(0, path.indexOf('/'))).distinct().count();
        ctx.printer().println(AnsiColors.green("✓") + " Copied " + catalog.paths().size() + " files in "
                              + folders + " skill folders");
    }

    private void copySkillFile(
            InitContext ctx, InputStream source, Path destination, SkillCatalog.Manifest catalog,
            Pattern codexInvocations)
            throws Exception {
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        WorkflowManifest workflow = catalog.workflow();
        if (destination.getFileName().toString().equals("SKILL.md")) {
            String skillName = destination.getParent().getFileName().toString();
            boolean shipDelegate = "camel-ship".equals(skillName);
            if (shipDelegate && NATIVE_SHIP_AGENTS.contains(ctx.agentName())
                    && "core".equals(catalog.contributions().get("skill/camel-ship").pack())) {
                try (InputStream nativeSkill = getClass()
                        .getResourceAsStream("/templates/" + ctx.agentName() + "/camel-ship.md")) {
                    if (nativeSkill == null) {
                        throw new IOException("Native Ship skill is missing for " + ctx.agentName());
                    }
                    Files.copy(nativeSkill, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (AgentGeneratorStrategy.BOB2.descriptorValue().equals(ctx.agentName())
                    || AgentGeneratorStrategy.QWEN.descriptorValue().equals(ctx.agentName())
                    || AgentGeneratorStrategy.CLAUDE.descriptorValue().equals(ctx.agentName())) {
                boolean exposeCommand = (AgentGeneratorStrategy.BOB2.descriptorValue().equals(ctx.agentName())
                        || AgentGeneratorStrategy.CLAUDE.descriptorValue().equals(ctx.agentName()))
                        && workflow.commands().stream().anyMatch(command -> command.userFacing()
                                && command.skill().equals(skillName));
                addHyphenatedUserInvocableMetadata(destination, exposeCommand);
            }
            if (Set.of(AgentGeneratorStrategy.COPILOT.descriptorValue(),
                    AgentGeneratorStrategy.CLAUDE.descriptorValue(),
                    AgentGeneratorStrategy.PI.descriptorValue()).contains(ctx.agentName())
                    && workflow.skills().stream().anyMatch(skill -> skill.name().equals(skillName)
                            && "internal".equals(skill.status()))) {
                addInternalSkillInvocationMetadata(destination, ctx.agentName());
            }
            if (!shipDelegate) {
                dispatchBlockAppender.append(destination, ctx.agentName());
            }
        }
        if (destination.getFileName().toString().endsWith(".md")) {
            versionPlaceholderResolver.substitute(destination, ctx.distribution(), ctx.commandPrefix());
            var personaDirectory = PersonaResourceInstaller.targetDirectory(ctx);
            if (personaDirectory.isPresent()) {
                PersonaResourceInstaller.rewriteReferences(destination, personaDirectory.get());
            }
            if (codexInvocations != null) {
                useCodexSkillInvocations(destination, codexInvocations);
            }
        }
    }

    private static Pattern codexInvocationPattern(WorkflowManifest workflow) {
        String skills = workflow.skills().stream().map(s -> Pattern.quote(s.name())).collect(Collectors.joining("|"));
        // Possessive runs keep long labels and nested quote/list prefixes off the Java regex stack.
        String containers = "\\h*+(?:>\\h*+|(?:[-+*]|\\d{1,9}[.)])\\h++)*+";
        String label = "\\[(?!\\h*+\\])(?:[^\\[\\]\\\\\\r\\n]++|\\\\.)++\\]";
        String title = "(?:\"(?:[^\"\\\\\\r\\n]++|\\\\.)*+\"|'(?:[^'\\\\\\r\\n]++|\\\\.)*+'"
                       + "|\\((?:[^()\\\\\\r\\n]++|\\\\.)*+\\))";
        String definition = "^" + containers + label + ":\\h*+(?:\\R" + containers + ")?"
                            + "(?=\\S++(?:\\h++" + title + ")?\\h*+$)";
        String destination = "(\\]\\(\\s*<?|" + definition + "<?)";
        String boundary = "(?=\\**[.?]*(?:$|[\\s`'\"),;:!\\]}|~]|<br\\h*/?>))";
        return Pattern.compile("(?m)(?:" + destination + "|(?<![\\w./:=\\\\~-])|(?<=~~))/(" + skills + ")" + boundary);
    }

    private static void useCodexSkillInvocations(Path markdown, Pattern invocation) throws IOException {
        String content = Files.readString(markdown);
        BitSet closingEmphasis = closingEmphasis(content);
        String adapted = invocation.matcher(content).replaceAll(match -> {
            int end = match.end(2);
            boolean glob = end < content.length() && content.charAt(end) == '*' && !closingEmphasis.get(end);
            String replacement = match.group(1) == null && !glob
                    ? "$" + match.group(2) : match.group();
            return Matcher.quoteReplacement(replacement);
        });
        if (!adapted.equals(content)) {
            AtomicFileWriter.write(markdown, adapted);
        }
    }

    // Pair asterisk runs on the same line so a shell glob cannot reuse an earlier closing delimiter.
    private static BitSet closingEmphasis(String content) {
        BitSet closing = new BitSet();
        int openStars = 0;
        Matcher runs = EMPHASIS_TOKENS.matcher(content);
        while (runs.find()) {
            char first = content.charAt(runs.start());
            if (first == '\\') {
                continue;
            }
            if (first != '*') {
                openStars = 0;
                continue;
            }
            char before = runs.start() == 0 ? ' ' : content.charAt(runs.start() - 1);
            char after = runs.end() == content.length() ? ' ' : content.charAt(runs.end());
            int stars = runs.end() - runs.start();
            boolean followsWord = Character.isLetterOrDigit(before);
            if (!Character.isWhitespace(before) && (followsWord || !Character.isLetterOrDigit(after))) {
                int paired = Math.min(stars, openStars);
                openStars -= paired;
                stars -= paired;
                if (stars == 0) {
                    closing.set(runs.start());
                }
            }
            if (!followsWord && !Character.isWhitespace(after)) {
                openStars += stars;
            }
        }
        return closing;
    }

    private void addHyphenatedUserInvocableMetadata(Path skillFile, boolean exposeCommand) throws Exception {
        String content = Files.readString(skillFile);
        String normalized = content.replace("\r\n", "\n");
        String header = FrontmatterHandler.extractFrontmatterYaml(normalized);
        if (header == null || ("\n" + header).contains("\nuser-invocable:")) {
            return;
        }

        String[] lines = normalized.split("\n", -1);
        StringBuilder updated = new StringBuilder(normalized.length() + 24);
        boolean inserted = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!inserted && exposeCommand && line.startsWith("user_invocable:")) {
                line = "user_invocable: true";
            }
            updated.append(line);
            if (!inserted && line.startsWith("user_invocable:")) {
                updated.append('\n');
                updated.append("user-invocable:").append(line.substring("user_invocable:".length()));
                if (i < lines.length - 1) {
                    updated.append('\n');
                }
                inserted = true;
            } else if (i < lines.length - 1) {
                updated.append('\n');
            }
        }
        if (inserted) {
            AtomicFileWriter.write(skillFile, updated.toString());
        }
    }

    void addInternalSkillInvocationMetadata(Path skillFile, String agentName) throws Exception {
        String skillName = skillFile.getParent().getFileName().toString();
        String content = Files.readString(skillFile);
        String normalized = content.replace("\r\n", "\n");
        String header = FrontmatterHandler.extractFrontmatterYaml(normalized);
        if (header == null) {
            throw new IOException(
                    "Internal " + agentName + " skill '" + skillName
                                  + "' must declare frontmatter so invocation metadata can be generated");
        }

        header = "\n" + header;
        boolean hasUserInvocable = header.contains("\nuser-invocable:");
        boolean hasDisableModelInvocation = header.contains("\ndisable-model-invocation:");
        if (hasUserInvocable && hasDisableModelInvocation) {
            return;
        }
        boolean hasCamelKitUserInvocable = header.contains("\nuser_invocable:");
        if (!hasCamelKitUserInvocable && !hasUserInvocable) {
            throw new IOException(
                    "Internal " + agentName + " skill '" + skillName
                                  + "' must declare user_invocable or user-invocable so invocation metadata can be generated");
        }

        String[] lines = normalized.split("\n", -1);
        StringBuilder updated = new StringBuilder(normalized.length() + 64);
        boolean inserted = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            updated.append(line);
            if (!inserted && (line.startsWith("user_invocable:") || line.startsWith("user-invocable:"))) {
                if (!hasUserInvocable) {
                    updated.append('\n').append("user-invocable: false");
                }
                if (!hasDisableModelInvocation) {
                    updated.append('\n').append("disable-model-invocation: true");
                }
                inserted = true;
            }
            if (i < lines.length - 1) {
                updated.append('\n');
            }
        }
        if (inserted) {
            AtomicFileWriter.write(skillFile, updated.toString());
        }
    }
}
