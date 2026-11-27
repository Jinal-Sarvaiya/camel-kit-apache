package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.camel.kit.config.OpenCodeProjectConfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class OpenCodeConfigMerger {

    private static final ObjectMapper JSON = OpenCodeProjectConfig.newJsonMapper();
    private static final Set<String> PERMISSION_ACTIONS = Set.of("allow", "ask", "deny");

    void validateExisting(Path configFile) throws IOException {
        for (Path candidate : existingConfigurationFiles(configFile)) {
            readExisting(candidate);
        }
    }

    void merge(Path configFile, String renderedConfig, boolean withCitrusMcp) throws IOException {
        ObjectNode generated = readObject(renderedConfig, "generated OpenCode configuration", false);
        requireGeneratedObject(generated, "permission");
        requireGeneratedObject(generated, "mcp");

        List<Path> targets = existingConfigurationFiles(configFile);
        if (targets.isEmpty()) {
            writeAll(List.of(new PendingWrite(configFile, pretty(generated) + System.lineSeparator())));
            return;
        }

        // Validate every candidate before changing any of them.
        List<ExistingConfiguration> configurations = new ArrayList<>(targets.size());
        for (Path target : targets) {
            configurations.add(readExisting(target));
        }

        ObjectNode userRestrictions = collectUserRules((ObjectNode) generated.path("permission"), configurations);

        List<PendingWrite> writes = new ArrayList<>();
        for (int i = 0; i < configurations.size() - 1; i++) {
            ExistingConfiguration configuration = configurations.get(i);
            String updated = removeManagedEntries(configuration.content(), generated, withCitrusMcp);
            if (!updated.equals(configuration.content())) {
                writes.add(new PendingWrite(configuration.path(), updated));
            }
        }

        ExistingConfiguration highest = configurations.get(configurations.size() - 1);
        String merged = mergeGenerated(highest, generated, userRestrictions, withCitrusMcp);
        if (!merged.equals(highest.content())) {
            writes.add(new PendingWrite(highest.path(), merged));
        }
        writeAll(writes);
    }

    /**
     * Collects the managed permission rules that must survive regeneration, in their effective order.
     *
     * <p>
     * OpenCode applies the last matching rule, so the order of the rules decides each tool's action. Rules from all
     * layers are flattened (a later layer updates an earlier rule for the same key in place) and compared with the
     * generated order. Everything from the first deviation onward is kept in the user's order, so exact exceptions on
     * both sides of a wildcard keep their relative position. Rules that match the generated layout need no special
     * handling.
     */
    private ObjectNode collectUserRules(ObjectNode generatedPermission, List<ExistingConfiguration> configurations) {
        Map<String, JsonNode> managedRules = new java.util.LinkedHashMap<>();
        for (ExistingConfiguration configuration : configurations) {
            JsonNode permission = configuration.config().path("permission");
            if (!permission.isObject()) {
                continue;
            }

            // A key declared by an earlier layer is updated in place, as OpenCode's merge does; new keys append.
            permission.fields().forEachRemaining(entry -> {
                JsonNode generatedValue = generatedPermission.get(entry.getKey());
                if (generatedValue != null) {
                    JsonNode normalized = normalizeManagedRule(entry.getValue(), generatedValue);
                    JsonNode previousValue = managedRules.get(entry.getKey());

                    // If both values are objects (argument patterns), merge them
                    if (previousValue != null && previousValue.isObject() && normalized.isObject()) {
                        ObjectNode merged = ((ObjectNode) previousValue).deepCopy();
                        normalized.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
                        managedRules.put(entry.getKey(), merged);
                    } else {
                        managedRules.put(entry.getKey(), normalized);
                    }
                }
            });
        }

        List<String> generatedKeys = new ArrayList<>();
        generatedPermission.fieldNames().forEachRemaining(generatedKeys::add);
        List<Map.Entry<String, JsonNode>> rules = new ArrayList<>(managedRules.entrySet());
        int common = 0;
        while (common < rules.size() && common < generatedKeys.size()) {
            String key = generatedKeys.get(common);
            Map.Entry<String, JsonNode> rule = rules.get(common);
            if (!rule.getKey().equals(key) || !rule.getValue().equals(generatedPermission.get(key))) {
                break;
            }
            common++;
        }

        ObjectNode userRules = JSON.createObjectNode();
        for (int i = common; i < rules.size(); i++) {
            userRules.set(rules.get(i).getKey(), rules.get(i).getValue());
        }
        return userRules;
    }

    /** Keeps object-valued rules and rules stricter than generated; anything looser reverts to the generated action. */
    private JsonNode normalizeManagedRule(JsonNode existing, JsonNode generated) {
        if (existing.isObject()) {
            return existing;
        }
        if (existing.isTextual() && generated.isTextual() && isStricterThan(existing.asText(), generated.asText())) {
            return existing;
        }
        return generated;
    }

    private String removeManagedEntries(
            String content, ObjectNode generated, boolean withCitrusMcp)
            throws IOException {
        String body = contentWithoutBom(content);
        if (body.isBlank()) {
            return content;
        }
        JsoncObjectEditor editor = new JsoncObjectEditor(body);
        removeObjectEntries(editor, generated, "permission");
        removeObjectEntries(editor, generated, "mcp");
        if (!withCitrusMcp) {
            removeCitrusEntries(editor, generated);
        }
        return bom(content) + editor.content();
    }

    private void removeObjectEntries(JsoncObjectEditor editor, ObjectNode generated, String field) {
        ObjectNode generatedObject = (ObjectNode) generated.path(field);
        editor.removeObjectMembers(field, generatedObject.fieldNames());
    }

    private void removeCitrusEntries(JsoncObjectEditor editor, ObjectNode generated) throws IOException {
        editor.removeObjectMembers("mcp", java.util.Collections.singletonList("citrus").iterator());

        // Remove citrus wildcard and all exact citrus tool permissions from workflow manifest
        List<String> citrusPermissions = new ArrayList<>();
        citrusPermissions.add("citrus_*");

        // Get Citrus tools from workflow manifest
        try {
            org.apache.camel.kit.workflow.WorkflowManifest workflow
                    = org.apache.camel.kit.workflow.WorkflowManifestLoader.loadDefault();
            org.apache.camel.kit.workflow.WorkflowManifest.WorkflowMcpServer citrusServer
                    = workflow.mcpServer("citrus");
            for (String tool : citrusServer.allowedTools()) {
                citrusPermissions.add("citrus_" + tool);
            }
        } catch (Exception e) {
            // If we can't load the manifest, fall back to removing anything starting with citrus_
            // This preserves the old behavior as a safety net
        }

        editor.removeObjectMembers("permission", citrusPermissions.iterator());
    }

    private boolean hasCitrusEntries(ObjectNode config) {
        if (config.path("mcp").has("citrus")) {
            return true;
        }
        JsonNode permission = config.path("permission");
        if (permission.has("citrus_*")) {
            return true;
        }
        // Check for any exact Citrus tool permissions from the manifest
        try {
            org.apache.camel.kit.workflow.WorkflowManifest workflow
                    = org.apache.camel.kit.workflow.WorkflowManifestLoader.loadDefault();
            org.apache.camel.kit.workflow.WorkflowManifest.WorkflowMcpServer citrusServer
                    = workflow.mcpServer("citrus");
            for (String tool : citrusServer.allowedTools()) {
                if (permission.has("citrus_" + tool)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // If we can't load the manifest, be conservative and assume no Citrus entries
        }
        return false;
    }

    private String mergeGenerated(
            ExistingConfiguration existing, ObjectNode generated, ObjectNode userRestrictions, boolean withCitrusMcp)
            throws IOException {
        boolean needsCitrusRemoval = !withCitrusMcp && hasCitrusEntries(existing.config());
        if (isCurrentAndOrdered(existing, generated) && !needsCitrusRemoval && userRestrictions.isEmpty()) {
            return existing.content();
        }

        String prefix = bom(existing.content());
        String content = contentWithoutBom(existing.content());
        if (content.isBlank()) {
            // Treat a blank file as an empty object so saved rules still apply through the normal merge.
            content = "{}";
        }

        JsoncObjectEditor editor = new JsoncObjectEditor(content);
        if (existing.scalarPermission()) {
            editor.replaceRootMemberValue("permission", existing.config().path("permission"));
        }

        generated.fields().forEachRemaining(entry -> {
            if (!"permission".equals(entry.getKey()) && !"mcp".equals(entry.getKey())) {
                editor.upsertRootMember(entry.getKey(), entry.getValue());
            }
        });
        mergeObject(editor, existing.config(), generated, "permission");
        mergeObject(editor, existing.config(), generated, "mcp");
        if (!withCitrusMcp) {
            removeCitrusEntries(editor, generated);
        }

        // Apply user restrictions collected from all config files
        if (!userRestrictions.isEmpty()) {
            editor.removeObjectMembers("permission", userRestrictions.fieldNames());
            editor.appendObjectMembers("permission", userRestrictions);
        }

        return prefix + editor.content();
    }

    private boolean isCurrentAndOrdered(ExistingConfiguration existing, ObjectNode generated) {
        if (existing.scalarPermission()) {
            return false;
        }

        ObjectNode config = existing.config();
        var fields = generated.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if ("permission".equals(field.getKey()) || "mcp".equals(field.getKey())) {
                if (!containsGeneratedEntries(config.path(field.getKey()), (ObjectNode) field.getValue())) {
                    return false;
                }
            } else if (!field.getValue().equals(config.get(field.getKey()))) {
                return false;
            }
        }
        return generatedPermissionsTrail(config.path("permission"), (ObjectNode) generated.path("permission"));
    }

    private boolean containsGeneratedEntries(JsonNode existing, ObjectNode generated) {
        if (!existing.isObject()) {
            return false;
        }
        var fields = generated.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!field.getValue().equals(existing.get(field.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private boolean generatedPermissionsTrail(JsonNode existing, ObjectNode generated) {
        if (!existing.isObject()) {
            return false;
        }
        List<String> existingNames = new ArrayList<>();
        existing.fieldNames().forEachRemaining(existingNames::add);
        List<String> generatedNames = new ArrayList<>();
        generated.fieldNames().forEachRemaining(generatedNames::add);
        int generatedStart = existingNames.size() - generatedNames.size();
        return generatedStart >= 0
                && existingNames.subList(generatedStart, existingNames.size()).equals(generatedNames);
    }

    private void mergeObject(
            JsoncObjectEditor editor,
            ObjectNode existing,
            ObjectNode generated,
            String field) {
        ObjectNode generatedObject = (ObjectNode) generated.path(field);
        if (!existing.has(field)) {
            editor.appendRootMember(field, generatedObject);
            return;
        }

        // For permissions, preserve user restrictions that are stricter than generated
        ObjectNode toMerge = generatedObject;
        if ("permission".equals(field)) {
            toMerge = filterStricterUserPermissions(existing.path(field), generatedObject);
        }

        editor.removeObjectMembers(field, toMerge.fieldNames());
        editor.appendObjectMembers(field, toMerge);
    }

    private ObjectNode filterStricterUserPermissions(JsonNode existingPermission, ObjectNode generatedPermission) {
        if (!existingPermission.isObject()) {
            return generatedPermission;
        }

        ObjectNode filtered = generatedPermission.deepCopy();
        var fields = generatedPermission.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String key = field.getKey();
            String generatedAction = field.getValue().asText();
            JsonNode existingValue = existingPermission.get(key);

            if (existingValue != null && existingValue.isTextual()) {
                String existingAction = existingValue.asText();
                // If user set a stricter permission, preserve it by not including it in the merge
                if (isStricterThan(existingAction, generatedAction)) {
                    filtered.remove(key);
                }
            }
        }
        return filtered;
    }

    private boolean isStricterThan(String existing, String generated) {
        // deny is strictest, ask is medium, allow is most permissive
        // If existing is stricter than generated, return true
        if ("deny".equals(existing)) {
            return "ask".equals(generated) || "allow".equals(generated);
        }
        if ("ask".equals(existing)) {
            return "allow".equals(generated);
        }
        return false;
    }

    private ExistingConfiguration readExisting(Path configFile) throws IOException {
        String content;
        try {
            content = Files.readString(configFile);
        } catch (IOException e) {
            throw new InvalidAgentConfigurationException(
                    "existing " + displayName(configFile) + " could not be read", e);
        }

        String body = contentWithoutBom(content);
        ObjectNode existing = body.isBlank()
                ? JSON.createObjectNode()
                : readObject(body, "existing " + displayName(configFile), true);
        JsonNode permission = existing.get("permission");
        boolean scalarPermission = permission != null && permission.isTextual()
                && PERMISSION_ACTIONS.contains(permission.asText());
        if (scalarPermission) {
            OpenCodeProjectConfig.normalizeScalarPermission(existing);
        }
        requireObjectField(configFile, existing, "permission");
        requireObjectField(configFile, existing, "mcp");
        return new ExistingConfiguration(configFile, content, existing, scalarPermission);
    }

    private boolean hasExistingConfiguration(Path configFile) throws InvalidAgentConfigurationException {
        if (!Files.exists(configFile)) {
            if (Files.isSymbolicLink(configFile)) {
                throw new InvalidAgentConfigurationException(
                        "existing " + displayName(configFile) + " is a symbolic link to a missing file");
            }
            return false;
        }
        if (!Files.isRegularFile(configFile)) {
            throw new InvalidAgentConfigurationException(
                    "existing " + displayName(configFile) + " must be a regular file");
        }
        return true;
    }

    private List<Path> existingConfigurationFiles(Path configFile) throws IOException {
        Path projectDir = configFile.getParent();
        if (projectDir == null) {
            projectDir = Path.of("");
        }
        List<Path> existing = new ArrayList<>();
        for (Path candidate : OpenCodeProjectConfig.existingFiles(projectDir)) {
            if (hasExistingConfiguration(candidate)) {
                existing.add(candidate);
            }
        }
        return existing;
    }

    private void requireObjectField(Path configFile, ObjectNode existing, String field)
            throws InvalidAgentConfigurationException {
        JsonNode value = existing.get(field);
        if (value != null && !value.isObject()) {
            throw new InvalidAgentConfigurationException(
                    "existing " + displayName(configFile) + " field '" + field + "' must be an object");
        }
    }

    private void requireGeneratedObject(ObjectNode generated, String field) throws IOException {
        if (!generated.path(field).isObject()) {
            throw new IOException("generated OpenCode configuration must contain an object named '" + field + "'");
        }
    }

    private String displayName(Path configFile) {
        Path parent = configFile.getParent();
        if (parent != null && ".opencode".equals(String.valueOf(parent.getFileName()))) {
            return ".opencode/" + configFile.getFileName();
        }
        return configFile.getFileName().toString();
    }

    private ObjectNode readObject(String content, String source, boolean existing) throws IOException {
        JsonNode parsed;
        try {
            parsed = JSON.readTree(content);
        } catch (JsonProcessingException e) {
            String message = source + " is not valid JSON or JSONC";
            if (existing) {
                throw new InvalidAgentConfigurationException(
                        message + ": " + e.getOriginalMessage()
                                                             + ". Fix the syntax or remove duplicate keys before retrying initialization.",
                        e);
            }
            throw new IOException(message, e);
        }
        if (parsed == null || !parsed.isObject()) {
            String message = source + " must be a JSON object";
            if (existing) {
                throw new InvalidAgentConfigurationException(message);
            }
            throw new IOException(message);
        }
        return ((ObjectNode) parsed).deepCopy();
    }

    private String pretty(JsonNode value) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not render OpenCode configuration", e);
        }
    }

    private String bom(String content) {
        return content.startsWith("\uFEFF") ? "\uFEFF" : "";
    }

    private String contentWithoutBom(String content) {
        return content.startsWith("\uFEFF") ? content.substring(1) : content;
    }

    private void writeAll(List<PendingWrite> writes) throws IOException {
        List<StagedWrite> staged = new ArrayList<>(writes.size());
        try {
            for (PendingWrite write : writes) {
                staged.add(stage(write));
            }
            // Keep generated definitions available if a later, lower-precedence replacement fails.
            for (int i = staged.size() - 1; i >= 0; i--) {
                commit(staged.get(i));
            }
        } catch (IOException failure) {
            cleanup(staged, failure);
            throw failure;
        }
        cleanup(staged, null);
    }

    private StagedWrite stage(PendingWrite write) throws IOException {
        // Write through symbolic links so dotfile-managed configs keep their link.
        Path target = Files.exists(write.target()) ? write.target().toRealPath() : write.target();
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, write.content());
            return new StagedWrite(target, temp);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void commit(StagedWrite write) throws IOException {
        try {
            Files.move(write.temp(), write.target(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(write.temp(), write.target(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void cleanup(List<StagedWrite> staged, IOException failure) throws IOException {
        IOException cleanupFailure = null;
        for (StagedWrite write : staged) {
            try {
                Files.deleteIfExists(write.temp());
            } catch (IOException e) {
                if (failure != null) {
                    failure.addSuppressed(e);
                } else if (cleanupFailure == null) {
                    cleanupFailure = e;
                } else {
                    cleanupFailure.addSuppressed(e);
                }
            }
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    private record ExistingConfiguration(
            Path path,
            String content,
            ObjectNode config,
            boolean scalarPermission) {
    }

    private record PendingWrite(Path target, String content) {
    }

    private record StagedWrite(Path target, Path temp) {
    }
}
