package org.apache.camel.kit.brainstorm;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Workflow regression tests for interface contract discovery (Issues #111 and #160).
 *
 * These tests verify the actual greenfield interview behavior by checking that: 1. Questions appear in the correct
 * order 2. Contract questions are split (source separate from destination) 3. DataMapper is deferred when contracts are
 * pending 4. Routing targets trigger per-destination contract discovery 5. Ship DISCOVERY includes contract rules
 * inline in the prompt
 *
 * Tests must FAIL if the interview structure regresses to the old combined Question 7b.
 */
@DisplayName("Interface Contract Discovery Workflow")
class InterfaceContractDiscoveryTest {

    private static final String GREENFIELD_INTERVIEW_PATH
            = "src/main/resources/skills/camel-brainstorm/guides/greenfield-interview.md";
    private static final String SHIP_COORDINATOR_PATH
            = "src/main/java/org/apache/camel/kit/ship/controller/ShipCoordinator.java";
    private static final String CONTRACT_BRIEFING_PATH
            = "src/main/resources/skills/shared/interface-contract-briefing.md";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Interview must have separate source and destination contract questions")
    void testSeparateSourceAndDestinationQuestions() throws Exception {
        Path interviewPath = Path.of(GREENFIELD_INTERVIEW_PATH);
        assertTrue(Files.exists(interviewPath),
                "Greenfield interview file must exist at: " + GREENFIELD_INTERVIEW_PATH);

        String content = Files.readString(interviewPath);

        // Must have Question 7b for source
        assertTrue(content.contains("### Question 7b: Source Interface Contract"),
                "Question 7b must ask about SOURCE interface contract specifically");

        // Must have Question 7c for destination
        assertTrue(content.contains("### Question 7c: Destination Interface Contract"),
                "Question 7c must ask about DESTINATION interface contract specifically");

        // Must NOT have the old combined question
        assertFalse(
                content.contains("For the **source** (where data comes from):\na) Existing definition available now")
                        && content.contains(
                                "For the **destination** (where data goes):\na) Existing definition available now")
                        && content.indexOf("For the **source**") > content.indexOf("### Question 7b"),
                "Must NOT contain old combined source+destination question after Question 7b");
    }

    @Test
    @DisplayName("Interview must NOT have duplicate Question 7b")
    void testNoDuplicateQuestion7b() throws Exception {
        Path interviewPath = Path.of(GREENFIELD_INTERVIEW_PATH);
        String content = Files.readString(interviewPath);

        int firstOccurrence = content.indexOf("### Question 7b:");
        int secondOccurrence = content.indexOf("### Question 7b:", firstOccurrence + 1);

        assertEquals(-1, secondOccurrence,
                "There must be exactly ONE Question 7b, not multiple instances");
    }

    @Test
    @DisplayName("Question order must be: transformations → contracts → sink → routing → DataMapper")
    void testQuestionOrder() throws Exception {
        Path interviewPath = Path.of(GREENFIELD_INTERVIEW_PATH);
        String content = Files.readString(interviewPath);

        int q7Index = content.indexOf("### Question 7: Transformations");
        int q7bIndex = content.indexOf("### Question 7b: Source Interface Contract");
        int q7cIndex = content.indexOf("### Question 7c: Destination Interface Contract");
        int q8Index = content.indexOf("### Question 8: Sink System");
        int q8aIndex = content.indexOf("### Question 8a: Multi-Path Routing");
        int q8bIndex = content.indexOf("### Question 8b: Routing Target Contracts");
        int q7aIndex = content.indexOf("### Question 7a: Data Transformation Detail");
        int q9Index = content.indexOf("### Question 9: Error Handling");

        assertTrue(q7Index > 0, "Question 7 (Transformations) must exist");
        assertTrue(q7bIndex > 0, "Question 7b (Source Contract) must exist");
        assertTrue(q7cIndex > 0, "Question 7c (Destination Contract) must exist");
        assertTrue(q8Index > 0, "Question 8 (Sink) must exist");
        assertTrue(q8aIndex > 0, "Question 8a (Routing) must exist");
        assertTrue(q8bIndex > 0, "Question 8b (Routing Target Contracts) must exist");
        assertTrue(q7aIndex > 0, "Question 7a (DataMapper) must exist");
        assertTrue(q9Index > 0, "Question 9 (Error Handling) must exist");

        assertTrue(q7Index < q7bIndex,
                "Question 7 (Transformations) must come before 7b (Source Contract)");
        assertTrue(q7bIndex < q7cIndex,
                "Question 7b (Source Contract) must come before 7c (Destination Contract)");
        assertTrue(q7cIndex < q8Index,
                "Question 7c (Destination Contract) must come before 8 (Sink)");
        assertTrue(q8Index < q8aIndex,
                "Question 8 (Sink) must come before 8a (Routing)");
        assertTrue(q8aIndex < q8bIndex,
                "Question 8a (Routing) must come before 8b (Routing Target Contracts)");
        assertTrue(q8bIndex < q7aIndex,
                "Question 8b (Routing Target Contracts) must come before 7a (DataMapper) - routing targets must be known before field mapping");
        assertTrue(q7aIndex < q9Index,
                "Question 7a (DataMapper) must come before 9 (Error Handling)");
    }

    @Test
    @DisplayName("DataMapper must defer only mappings that depend on pending contracts")
    void testDataMapperDefersOnlyAffectedMappings() throws Exception {
        Path interviewPath = Path.of(GREENFIELD_INTERVIEW_PATH);
        String content = Files.readString(interviewPath);

        int q7aIndex = content.indexOf("### Question 7a: Data Transformation Detail");
        int q9Index = content.indexOf("### Question 9: Error Handling");

        String q7aSection = content.substring(q7aIndex, q9Index);

        // Must check per-mapping dependencies, not blanket defer
        assertTrue(
                q7aSection.contains("BOTH source and destination")
                        || q7aSection.contains("BOTH source and that branch"),
                "Question 7a must check if BOTH source and destination are available for each mapping");

        // Must collect mappings where both schemas are available
        assertTrue(q7aSection.contains("Collect mapping for each branch where")
                || q7aSection.contains("proceed with mapping"),
                "Question 7a must collect mappings where both schemas are available");

        // Must defer only specific mappings with pending dependencies
        assertTrue(q7aSection.contains("Defer mapping for branches where") || q7aSection.contains("defer this mapping"),
                "Question 7a must defer only mappings that depend on pending contracts");

        // Must handle conditional routing per-branch
        assertTrue(q7aSection.contains("For EACH routing branch") || q7aSection.contains("each branch"),
                "Question 7a must handle conditional routing per-branch");

        // Must record pending dependencies separately per branch
        assertTrue(q7aSection.contains("Record each pending mapping dependency separately")
                || q7aSection.contains("separately"),
                "Question 7a must record each pending mapping dependency separately");

        // Must defer all if source is pending or designed without artifact
        assertTrue(q7aSection.contains("If source contract is \"pending\"") && q7aSection.contains("defer ALL"),
                "Question 7a must defer all mappings if source contract is pending or designed without artifact");
    }

    @Test
    @DisplayName("DataMapper must consume artifact references and return to Q9")
    void testDataMapperConsumesArtifactsAndReturnsToQ9() throws Exception {
        Path interviewPath = Path.of(GREENFIELD_INTERVIEW_PATH);
        String content = Files.readString(interviewPath);

        int q7aIndex = content.indexOf("### Question 7a: Data Transformation Detail");
        int q9Index = content.indexOf("### Question 9: Error Handling");
        String q7aSection = content.substring(q7aIndex, q9Index);

        // Must pass artifact references to DataMapper
        assertTrue(q7aSection.contains("Pass any existing contract artifacts")
                || q7aSection.contains("Pass") && q7aSection.contains("artifact"),
                "Question 7a must pass existing contract artifacts to DataMapper");

        assertTrue(q7aSection.contains("Questions 7b, 7c, and 8b") || (q7aSection.contains("7b")
                && q7aSection.contains("7c") && q7aSection.contains("8b")),
                "Question 7a must reference artifacts from Questions 7b, 7c, and 8b");

        // Must skip schema request steps when artifacts are provided
        assertTrue(q7aSection.contains("skip the schema request steps")
                || q7aSection.contains("use the provided schemas directly"),
                "Question 7a must tell DataMapper to skip schema requests when artifacts are provided");

        // Must ask only for missing information
        assertTrue(q7aSection.contains("Ask only for information not already")
                || q7aSection.contains("not already available"),
                "Question 7a must ask only for information not already in contract artifacts");

        // Must continue to Question 9 after DataMapper returns
        assertTrue(q7aSection.contains("continue to Question 9"),
                "Question 7a must continue to Question 9 after DataMapper returns control");
    }

    @Test
    @DisplayName("DataMapper interview must consume artifact references from Q7a")
    void testDataMapperInterviewConsumesReferences() throws Exception {
        Path datamapperPath = Path.of("src/main/resources/skills/camel-design/guides/datamapper-interview.md");
        assertTrue(Files.exists(datamapperPath), "DataMapper interview must exist");

        String content = Files.readString(datamapperPath);

        // Must mention contract artifact references from Q7a
        assertTrue(content.contains("Contract artifact references") || content.contains("Question 7a passes"),
                "DataMapper interview must acknowledge contract artifact references from Q7a");

        // Must check for existing artifacts in Step 2 (Source Schema)
        int step2Index = content.indexOf("## Step 2: Source Schema");
        int step3Index = content.indexOf("## Step 3: Target Schema");
        String step2Section = content.substring(step2Index, step3Index);

        assertTrue(step2Section.contains("Check for existing artifact") || step2Section.contains("already available"),
                "Step 2 must check for existing source artifact from Q7b");

        assertTrue(step2Section.contains("Question 7b") || step2Section.contains("Q7b"),
                "Step 2 must reference Question 7b for source artifact");

        assertTrue(step2Section.contains("skip the request") || step2Section.contains("skip"),
                "Step 2 must skip schema request if artifact is available");

        // Must check for existing artifacts in Step 3 (Target Schema)
        int step3bIndex = content.indexOf("## Step 3b:");
        String step3Section = content.substring(step3Index, step3bIndex);

        assertTrue(step3Section.contains("Check for existing artifact") || step3Section.contains("already available"),
                "Step 3 must check for existing target artifact from Q7c/Q8b");

        assertTrue((step3Section.contains("Question 7c") || step3Section.contains("Q7c"))
                && (step3Section.contains("Question 8b") || step3Section.contains("Q8b")),
                "Step 3 must reference Question 7c and Question 8b for target artifact");

        assertTrue(step3Section.contains("skip the request") || step3Section.contains("skip"),
                "Step 3 must skip schema request if artifact is available");

        // Must return to calling guide
        assertTrue(content.contains("return control to the calling guide"),
                "DataMapper interview must return control to the calling guide");
    }

    @Test
    @DisplayName("DataMapper canonicalize must return to Question 9")
    void testDataMapperCanonicalizeReturnsToQ9() throws Exception {
        Path canonicalizePath = Path.of("src/main/resources/skills/shared/datamapper-canonicalize.md");
        assertTrue(Files.exists(canonicalizePath), "DataMapper canonicalize must exist");

        String content = Files.readString(canonicalizePath);

        // Must return to calling guide, and when called from greenfield interview, continues to Q9
        assertTrue(content.contains("return control to the calling guide"),
                "DataMapper canonicalize must return control to the calling guide");

        assertTrue(content.contains("greenfield interview") && content.contains("Question 9"),
                "DataMapper canonicalize must specify that greenfield interview continues to Question 9");

        assertFalse(content.contains("return to `camel-brainstorm` at Question 8"),
                "DataMapper canonicalize must NOT return to Question 8");
    }

    @Test
    @DisplayName("Question 8b must reuse Q7c answers and analyze supplied material first")
    void testRoutingTargetContractsReuseAnswers() throws Exception {
        Path interviewPath = Path.of(GREENFIELD_INTERVIEW_PATH);
        String content = Files.readString(interviewPath);

        int q8bIndex = content.indexOf("### Question 8b: Routing Target Contracts");
        int q7aIndex = content.indexOf("### Question 7a: Data Transformation Detail");
        String q8bSection = content.substring(q8bIndex, q7aIndex);

        // Must analyze supplied material first
        assertTrue(q8bSection.contains("Analyze supplied material first"),
                "Question 8b must analyze supplied material first");

        // Must reuse Q7c answers
        assertTrue(q8bSection.contains("Reuse any answers already given in Question 7c")
                || q8bSection.contains("Reuse") && q8bSection.contains("7c"),
                "Question 8b must reuse answers already given in Question 7c");

        // Must skip asking if already recorded
        assertTrue(q8bSection.contains("skip") || q8bSection.contains("Skip"),
                "Question 8b must skip asking about destinations already recorded in Q7c");
    }

    @Test
    @DisplayName("Interview must have routing target contract question after Question 8a")
    void testRoutingTargetContractQuestion() throws Exception {
        Path interviewPath = Path.of(GREENFIELD_INTERVIEW_PATH);
        String content = Files.readString(interviewPath);

        int q8aIndex = content.indexOf("### Question 8a: Multi-Path Routing");
        assertTrue(q8aIndex > 0, "Question 8a (Routing) must exist");

        int q8bIndex = content.indexOf("### Question 8b: Routing Target Contracts");
        assertTrue(q8bIndex > 0,
                "Question 8b (Routing Target Contracts) must exist for conditional routing scenarios");

        assertTrue(q8aIndex < q8bIndex,
                "Question 8b (Routing Target Contracts) must come after 8a (Multi-Path Routing)");

        int q9Index = content.indexOf("### Question 9: Error Handling");
        assertTrue(q8bIndex < q9Index,
                "Question 8b must come before Question 9 (Error Handling)");

        // Verify it handles per-destination contracts
        String q8bSection = content.substring(q8bIndex, content.indexOf("### Question 7a"));
        assertTrue(q8bSection.contains("distinct routing destination") || q8bSection.contains("each distinct"),
                "Question 8b must ask about each distinct routing destination");
        assertTrue(q8bSection.contains("flow.routing.conditions[].contract"),
                "Question 8b must record contract per routing branch");
    }

    @Test
    @DisplayName("Ship DISCOVERY must include contract rules directly in controller-issued prompt")
    void testShipDiscoveryPromptIncludesInlineRules() throws Exception {
        Path shipCoordinatorPath = Path.of(SHIP_COORDINATOR_PATH);
        assertTrue(Files.exists(shipCoordinatorPath),
                "ShipCoordinator.java must exist at: " + SHIP_COORDINATOR_PATH);

        String content = Files.readString(shipCoordinatorPath);

        assertTrue(content.contains("Stage.DISCOVERY"),
                "ShipCoordinator must have DISCOVERY stage handling");

        // Rules must be in prompt.append() calls in the DISCOVERY case (not text.append in briefing)
        assertTrue(content.contains("Ask one boundary at a time") && content.contains("prompt.append"),
                "DISCOVERY case must append 'Ask one boundary at a time' rule to prompt");

        assertTrue(content.contains("Analyze supplied material first") && content.contains("prompt.append"),
                "DISCOVERY case must append 'Analyze supplied material first' rule to prompt");

        assertTrue(content.contains("Four possible statuses") && content.contains("prompt.append"),
                "DISCOVERY case must append 'Four possible statuses' rule to prompt");

        assertTrue(content.contains("(1) existing") && content.contains("(2) pending")
                && content.contains("(3) designed") && content.contains("(4) not-applicable"),
                "DISCOVERY case must define all four status values in prompt");
    }

    @Test
    @DisplayName("Ship DISCOVERY prompt must tell worker to record in report and unansweredQuestions")
    void testShipDiscoveryRecordingInstructions() throws Exception {
        Path shipCoordinatorPath = Path.of(SHIP_COORDINATOR_PATH);
        String content = Files.readString(shipCoordinatorPath);

        assertTrue(content.contains("Stage.DISCOVERY"), "DISCOVERY stage handling must exist");

        // Must tell worker to record in prompt (not briefing)
        assertTrue(content.contains("prompt.append") && content.contains("report")
                && content.contains("unansweredQuestions"),
                "DISCOVERY case must tell worker to use report and unansweredQuestions fields in prompt");

        assertTrue(content.contains("Record all findings in the report field"),
                "DISCOVERY case must instruct to record findings in report field in prompt");

        assertTrue(content.contains("grouped report structure"),
                "DISCOVERY case must specify grouped report structure in prompt");
    }

    @Test
    @DisplayName("Contract briefing must define four statuses")
    void testContractBriefingDefinesFourStatuses() throws Exception {
        Path briefingPath = Path.of(CONTRACT_BRIEFING_PATH);
        assertTrue(Files.exists(briefingPath),
                "Contract briefing must exist at: " + CONTRACT_BRIEFING_PATH);

        String content = Files.readString(briefingPath);

        assertTrue(content.contains("Status: existing") || content.contains("**Status: existing**"),
                "Briefing must define 'existing' status");
        assertTrue(content.contains("Status: pending") || content.contains("**Status: pending**"),
                "Briefing must define 'pending' status");
        assertTrue(content.contains("Status: designed") || content.contains("**Status: designed**"),
                "Briefing must define 'designed' status");
        assertTrue(content.contains("Status: not-applicable") || content.contains("**Status: not-applicable**"),
                "Briefing must define 'not-applicable' status");
    }

    @Test
    @DisplayName("Contract briefing must prohibit inventing placeholder contracts")
    void testContractBriefingProhibitsPlaceholders() throws Exception {
        Path briefingPath = Path.of(CONTRACT_BRIEFING_PATH);
        String content = Files.readString(briefingPath);

        assertTrue(content.contains("Do NOT invent") || content.contains("do not invent")
                || content.contains("NOT invent a placeholder"),
                "Briefing must explicitly prohibit inventing placeholder contracts");
    }

    @Test
    @DisplayName("Contract briefing must require asking source and destination separately")
    void testContractBriefingRequiresSeparateQuestions() throws Exception {
        Path briefingPath = Path.of(CONTRACT_BRIEFING_PATH);
        String content = Files.readString(briefingPath);

        assertTrue(content.contains("one boundary at a time") || content.contains("separately"),
                "Briefing must require asking about source and destination separately");
        assertTrue(content.contains("source") && content.contains("destination"),
                "Briefing must mention both source and destination");
    }

    @Test
    @DisplayName("Design template must support inline contract excerpts")
    void testDesignTemplateSupportsInlineExcerpts() throws Exception {
        Path designTemplatePath = Path.of(
                "src/main/resources/skills/camel-brainstorm/guides/design-assembly.md");
        assertTrue(Files.exists(designTemplatePath),
                "Design assembly template must exist");

        String content = Files.readString(designTemplatePath);

        // Check both Source and Sink Interface Contract sections
        assertTrue(content.contains("inline excerpt") || content.contains("inline content"),
                "Design template must allow inline contract excerpts, not just file paths or URLs");
    }

    @Test
    @DisplayName("Design template must preserve per-branch contract statuses for conditional routing")
    void testDesignTemplatePreservesPerBranchContracts() throws Exception {
        Path designTemplatePath = Path.of(
                "src/main/resources/skills/camel-brainstorm/guides/design-assembly.md");
        String content = Files.readString(designTemplatePath);

        // Must have Conditional Routing section
        assertTrue(content.contains("**Conditional Routing:**"),
                "Design template must have Conditional Routing section for multi-destination flows");

        // Must show per-branch structure
        assertTrue(content.contains("Branch 1:") && content.contains("Branch 2:"),
                "Conditional Routing section must show per-branch structure");

        // Each branch must have its own Interface Contract field
        int conditionalRoutingIndex = content.indexOf("**Conditional Routing:**");
        int sinkIndex = content.indexOf("**Sink:**", conditionalRoutingIndex);
        String conditionalSection = content.substring(conditionalRoutingIndex, sinkIndex);

        // Count Interface Contract occurrences in conditional routing section
        int contractCount = 0;
        int index = 0;
        while ((index = conditionalSection.indexOf("**Interface Contract:**", index)) != -1) {
            contractCount++;
            index += "**Interface Contract:**".length();
        }

        assertTrue(contractCount >= 2,
                "Conditional Routing section must have Interface Contract for each branch (found " + contractCount
                                       + ", expected >= 2)");

        // Must reference flow.routing.conditions[].contract
        assertTrue(content.contains("flow.routing.conditions[0].contract")
                || content.contains("flow.routing.conditions[1].contract"),
                "Design template must reference flow.routing.conditions[].contract for per-branch contracts");

        // Must explain that each branch's contract is recorded separately
        assertTrue(content.contains("separately") || content.contains("Branch 1 may have")
                || content.contains("preserves both"),
                "Design template must explain that per-branch contracts are preserved separately");
    }

    @Test
    @DisplayName("Interview must record contract status for both source and destination")
    void testInterviewRecordsBothContracts() throws Exception {
        Path interviewPath = Path.of(GREENFIELD_INTERVIEW_PATH);
        String content = Files.readString(interviewPath);

        assertTrue(content.contains("flow.contracts.source"),
                "Interview must record flow.contracts.source");
        assertTrue(content.contains("flow.contracts.destination"),
                "Interview must record flow.contracts.destination");
    }

    @Test
    @DisplayName("Contract briefing must require analyzing supplied material first")
    void testContractBriefingRequiresAnalysisFirst() throws Exception {
        Path briefingPath = Path.of(CONTRACT_BRIEFING_PATH);
        String content = Files.readString(briefingPath);

        assertTrue(content.contains("Analyze") || content.contains("analyze"),
                "Briefing must require analyzing supplied material");
        assertTrue(content.contains("First") || content.contains("first") || content.contains("Before asking"),
                "Briefing must require analysis BEFORE asking questions");

        // Must list supported contract types
        assertTrue(content.contains("OpenAPI") && content.contains("AsyncAPI")
                && content.contains("WSDL") && content.contains("JSON Schema")
                && content.contains("XSD"),
                "Briefing must list all supported contract types");
    }

    @Test
    @DisplayName("DataMapper must handle ready branch alongside pending branch")
    void testDataMapperHandlesReadyAndPendingBranches() throws Exception {
        Path interviewPath = Path.of(GREENFIELD_INTERVIEW_PATH);
        String content = Files.readString(interviewPath);

        int q7aIndex = content.indexOf("### Question 7a: Data Transformation Detail");
        int q9Index = content.indexOf("### Question 9: Error Handling");
        String q7aSection = content.substring(q7aIndex, q9Index);

        // Must describe per-branch mapping collection
        assertTrue(q7aSection.contains("Collect mapping for each branch where"),
                "Q7a must describe collecting mapping for ready branches");

        // Must defer only specific branches with pending dependencies
        assertTrue(q7aSection.contains("Defer mapping for branches where"),
                "Q7a must defer only branches with pending dependencies");

        // Must allow not-applicable (schema-less mapping)
        assertTrue(q7aSection.contains("not-applicable") && q7aSection.contains("schema-less"),
                "Q7a must support not-applicable status for schema-less Groovy mapping");

        // Must handle designed without artifact (defer until approval)
        assertTrue(q7aSection.contains("designed without artifact") && q7aSection.contains("defer"),
                "Q7a must defer mapping for designed contracts without artifacts (awaiting design approval)");
    }

    @Test
    @DisplayName("DataMapper final exit must return to calling guide")
    void testDataMapperFinalExitReturnsToQ9() throws Exception {
        Path datamapperPath = Path.of("src/main/resources/skills/camel-design/guides/datamapper-interview.md");
        String content = Files.readString(datamapperPath);

        // Find the final "When done" instruction
        int whenDoneIndex = content.lastIndexOf("When done");
        assertTrue(whenDoneIndex > 0, "DataMapper interview must have 'When done' instruction");

        String whenDoneSection = content.substring(whenDoneIndex, Math.min(content.length(), whenDoneIndex + 200));

        // Must return to calling guide
        assertTrue(whenDoneSection.contains("calling guide"),
                "DataMapper 'When done' instruction must return to calling guide");
        assertFalse(whenDoneSection.contains("Question 8"),
                "DataMapper 'When done' instruction must NOT return to Question 8");
    }

    @Test
    @DisplayName("DataMapper Steps 2-3 must handle designed contracts without artifacts")
    void testDataMapperHandlesDesignedWithoutArtifact() throws Exception {
        Path datamapperPath = Path.of("src/main/resources/skills/camel-design/guides/datamapper-interview.md");
        String content = Files.readString(datamapperPath);

        // Check Step 2
        int step2Index = content.indexOf("## Step 2: Source Schema");
        int step3Index = content.indexOf("## Step 3: Target Schema");
        String step2Section = content.substring(step2Index, step3Index);

        assertTrue(
                step2Section.contains("\"designed\" without artifact")
                        || step2Section.contains("designed without artifact"),
                "Step 2 must check for designed status without artifact");
        assertTrue(
                step2Section.contains("should have been deferred")
                        || step2Section.contains("depends on design approval"),
                "Step 2 must note that designed-without-artifact should have been deferred by Q7a");

        // Check Step 3
        int step3bIndex = content.indexOf("## Step 3b:");
        String step3Section = content.substring(step3Index, step3bIndex);

        assertTrue(
                step3Section.contains("\"designed\" without artifact")
                        || step3Section.contains("designed without artifact"),
                "Step 3 must check for designed status without artifact");
        assertTrue(
                step3Section.contains("should have been deferred")
                        || step3Section.contains("depends on design approval"),
                "Step 3 must note that designed-without-artifact should have been deferred by Q7a");
    }

    @Test
    @DisplayName("DataMapper must save extracted schemas with paths and preserve references")
    void testDataMapperSavesExtractedSchemas() throws Exception {
        Path datamapperPath = Path.of("src/main/resources/skills/camel-design/guides/datamapper-interview.md");
        String content = Files.readString(datamapperPath);

        // Check Step 2 for extracted schema handling
        int step2Index = content.indexOf("## Step 2: Source Schema");
        int step3Index = content.indexOf("## Step 3: Target Schema");
        String step2Section = content.substring(step2Index, step3Index);

        assertTrue(step2Section.contains("Extract") && step2Section.contains("save to"),
                "Step 2 must extract and save embedded schemas");
        assertTrue(step2Section.contains("flow-name}-source") && step2Section.contains("record this path"),
                "Step 2 must save extracted source schema with project-relative path");
        assertTrue(
                step2Section.contains("Preserve schema references") || step2Section.contains("referenced components"),
                "Step 2 must preserve references in extracted schemas");

        // Check Step 3
        int step3bIndex = content.indexOf("## Step 3b:");
        String step3Section = content.substring(step3Index, step3bIndex);

        assertTrue(step3Section.contains("Extract") && step3Section.contains("save to"),
                "Step 3 must extract and save embedded schemas");
        assertTrue(step3Section.contains("flow-name}-target") && step3Section.contains("record this path"),
                "Step 3 must save extracted target schema with project-relative path");
        assertTrue(
                step3Section.contains("Preserve schema references") || step3Section.contains("referenced components"),
                "Step 3 must preserve references in extracted schemas");
    }

    @Test
    @DisplayName("Interview contract questions must support all four answer options")
    void testContractQuestionsSupportAllOptions() throws Exception {
        Path interviewPath = Path.of(GREENFIELD_INTERVIEW_PATH);
        String content = Files.readString(interviewPath);

        int q7bIndex = content.indexOf("### Question 7b:");
        int q7cIndex = content.indexOf("### Question 7c:");

        assertTrue(q7bIndex > 0 && q7cIndex > 0, "Both contract questions must exist");

        // Check Question 7b section
        String q7bSection = content.substring(q7bIndex, q7cIndex);
        assertTrue(q7bSection.contains("Existing definition available now"),
                "Question 7b must have 'existing' option");
        assertTrue(q7bSection.contains("Definition will be supplied later"),
                "Question 7b must have 'pending' option");
        assertTrue(q7bSection.contains("We should design it together"),
                "Question 7b must have 'designed' option");
        assertTrue(q7bSection.contains("Not applicable"),
                "Question 7b must have 'not-applicable' option");

        // Check Question 7c section
        String q7cSection = content.substring(q7cIndex, content.indexOf("### Question 8"));
        assertTrue(q7cSection.contains("Existing definition available now"),
                "Question 7c must have 'existing' option");
        assertTrue(q7cSection.contains("Definition will be supplied later"),
                "Question 7c must have 'pending' option");
        assertTrue(q7cSection.contains("We should design it together"),
                "Question 7c must have 'designed' option");
        assertTrue(q7cSection.contains("Not applicable"),
                "Question 7c must have 'not-applicable' option");
    }

    @Test
    @DisplayName("Q7a must proceed when BOTH endpoints ready, defer when EITHER pending")
    void testDataMapperBranchSelectionLogic() throws Exception {
        Path interviewPath = Path.of(GREENFIELD_INTERVIEW_PATH);
        String content = Files.readString(interviewPath);

        int q7aIndex = content.indexOf("### Question 7a: Data Transformation Detail");
        int q9Index = content.indexOf("### Question 9: Error Handling");
        String q7aSection = content.substring(q7aIndex, q9Index);

        // Must describe proceeding when BOTH are ready
        assertTrue(
                q7aSection.contains("BOTH source and destination")
                        || q7aSection.contains("BOTH source and that branch"),
                "Q7a must check that BOTH endpoints are ready before proceeding");

        assertTrue(q7aSection.contains("existing\" with artifact") || q7aSection.contains("designed\" with artifact"),
                "Q7a must specify ready states: existing with artifact or designed with artifact");

        // Must describe deferring when EITHER is not ready
        assertTrue(q7aSection.contains("EITHER") || q7aSection.contains("Defer if EITHER"),
                "Q7a must defer if EITHER endpoint is not ready");

        assertTrue(q7aSection.contains("\"pending\"") && q7aSection.contains("defer"),
                "Q7a must defer mappings when either endpoint has pending status");

        assertTrue(q7aSection.contains("\"designed\" without artifact") && q7aSection.contains("defer"),
                "Q7a must defer mappings when either endpoint has designed status without artifact");

        // Must NOT have inverted logic like "proceed when both pending" or "defer when both ready"
        String q7aLower = q7aSection.toLowerCase();
        assertFalse(q7aLower.contains("proceed when both pending")
                || q7aLower.contains("proceed if both pending")
                || q7aLower.contains("both pending") && q7aLower.contains(" proceed"),
                "Q7a must NOT proceed when both endpoints are pending (inverted logic check)");
    }

    @Test
    @DisplayName("Design assembly must collect deferred mappings before approval")
    void testDesignAssemblyCollectsDeferredMappings() throws Exception {
        Path designAssemblyPath = Path.of("src/main/resources/skills/camel-brainstorm/guides/design-assembly.md");
        assertTrue(Files.exists(designAssemblyPath), "Design assembly guide must exist");

        String content = Files.readString(designAssemblyPath);

        // Must have section about collecting deferred mappings
        assertTrue(content.contains("Collect Deferred Mappings") || content.contains("deferred mappings"),
                "Design assembly must have instructions for collecting deferred mappings");

        // Must check for deferred mappings before approval
        int approvalIndex = content.indexOf("Do you approve this design");
        assertTrue(approvalIndex > 0, "Design assembly must have approval question");

        String beforeApproval = content.substring(0, approvalIndex);
        assertTrue(beforeApproval.contains("deferred") && beforeApproval.contains("designed"),
                "Design assembly must check for deferred mappings before approval");

        assertTrue(beforeApproval.contains("Load") && beforeApproval.contains("datamapper-interview.md"),
                "Design assembly must load DataMapper to collect deferred mappings");

        // Must ensure mappings are in design spec before approval
        assertTrue(beforeApproval.contains("canonical mapping section") || beforeApproval.contains("complete"),
                "Design assembly must ensure implementation has complete mapping section");
    }

    @Test
    @DisplayName("DataMapper target schema paths must be unique per routing branch")
    void testDataMapperUniqueTargetPathsPerBranch() throws Exception {
        Path datamapperPath = Path.of("src/main/resources/skills/camel-design/guides/datamapper-interview.md");
        String content = Files.readString(datamapperPath);

        int step3Index = content.indexOf("## Step 3: Target Schema");
        int step3bIndex = content.indexOf("## Step 3b:");
        String step3Section = content.substring(step3Index, step3bIndex);

        // Must have both JSON and XSD templates with branch identifier placeholder
        assertTrue(step3Section.contains("{flow-name}-{branch-identifier}-target.schema.json"),
                "Step 3 must specify JSON filename template with branch identifier placeholder");
        assertTrue(step3Section.contains("{flow-name}-{branch-identifier}-target.xsd"),
                "Step 3 must specify XSD filename template with branch identifier placeholder");

        // Must handle conditional routing with branch identifiers
        assertTrue(step3Section.contains("Conditional routing") || step3Section.contains("multiple branches"),
                "Step 3 must handle conditional routing case");

        // Must prevent overwriting
        assertTrue(step3Section.contains("prevent overwriting") || step3Section.contains("gets its own file"),
                "Step 3 must prevent one branch's schema from overwriting another's");

        // Must show actual distinct complete filenames for JSON in examples
        assertTrue(step3Section.contains("orders-billing-target.schema.json"),
                "Step 3 must show billing example with complete JSON filename");
        assertTrue(step3Section.contains("orders-warehouse-target.schema.json"),
                "Step 3 must show warehouse example with complete JSON filename");

        // Verify JSON filenames are different (not accidentally the same)
        int billingJsonIndex = step3Section.indexOf("orders-billing-target.schema.json");
        int warehouseJsonIndex = step3Section.indexOf("orders-warehouse-target.schema.json");
        assertTrue(billingJsonIndex >= 0 && warehouseJsonIndex >= 0 && billingJsonIndex != warehouseJsonIndex,
                "billing and warehouse JSON filenames must be distinct at different positions");

        // Both JSON and XSD examples must maintain branch identifier
        assertTrue(step3Section.contains("branch-identifier") && step3Section.contains(".schema.json"),
                "JSON template must maintain branch-identifier placeholder");
        assertTrue(step3Section.contains("branch-identifier") && step3Section.contains(".xsd"),
                "XSD template must maintain branch-identifier placeholder");
    }

    @Test
    @DisplayName("Q7b and Q7c must check supplied material before asking (issue #111)")
    void testContractQuestionsCheckSuppliedMaterialFirst() throws Exception {
        Path interviewPath = Path.of(GREENFIELD_INTERVIEW_PATH);
        String content = Files.readString(interviewPath);

        // Find Q7b (source contract)
        int q7bIndex = content.indexOf("### Question 7b: Source Interface Contract");
        int q7cIndex = content.indexOf("### Question 7c: Destination Interface Contract");
        String q7bSection = content.substring(q7bIndex, q7cIndex);

        // Q7b must have positive condition paired with skip action
        assertTrue(q7bSection.contains("If the user already provided")
                || q7bSection.contains("if the user already provided"),
                "Q7b must use positive condition 'if the user already provided'");

        // Must NOT use inverted condition
        assertFalse(q7bSection.contains("If the user has not already provided")
                || q7bSection.contains("if the user has not already provided"),
                "Q7b must NOT use inverted condition 'if the user has not already provided'");

        // Find the section between "already provided" and "skip this question"
        int alreadyProvidedIndex = q7bSection.toLowerCase().indexOf("already provided");
        int skipQuestionIndex = q7bSection.toLowerCase().indexOf("skip this question");
        assertTrue(alreadyProvidedIndex > 0 && skipQuestionIndex > alreadyProvidedIndex,
                "Q7b must have 'already provided' before 'skip this question' (positive condition → skip action)");

        // The condition and action must be in the same sentence/instruction
        String conditionToAction = q7bSection.substring(alreadyProvidedIndex, skipQuestionIndex + 20);
        assertTrue(conditionToAction.length() < 300,
                "Q7b condition and skip action must be in same instruction (not separated by >300 chars)");

        // Must list what to look for
        assertTrue(q7bSection.contains("OpenAPI") && q7bSection.contains("AsyncAPI")
                && q7bSection.contains("WSDL") && q7bSection.contains("JSON Schema"),
                "Q7b must list contract types to look for in supplied material");

        // Find Q7c (destination contract)
        int q8Index = content.indexOf("### Question 8: Sink System");
        String q7cSection = content.substring(q7cIndex, q8Index);

        // Q7c must also have positive condition paired with skip action
        assertTrue(q7cSection.contains("If the user already provided")
                || q7cSection.contains("if the user already provided"),
                "Q7c must use positive condition 'if the user already provided'");

        assertFalse(q7cSection.contains("If the user has not already provided")
                || q7cSection.contains("if the user has not already provided"),
                "Q7c must NOT use inverted condition 'if the user has not already provided'");

        int q7cAlreadyProvidedIndex = q7cSection.toLowerCase().indexOf("already provided");
        int q7cSkipQuestionIndex = q7cSection.toLowerCase().indexOf("skip this question");
        assertTrue(q7cAlreadyProvidedIndex > 0 && q7cSkipQuestionIndex > q7cAlreadyProvidedIndex,
                "Q7c must have 'already provided' before 'skip this question' (positive condition → skip action)");

        String q7cConditionToAction = q7cSection.substring(q7cAlreadyProvidedIndex, q7cSkipQuestionIndex + 20);
        assertTrue(q7cConditionToAction.length() < 300,
                "Q7c condition and skip action must be in same instruction (not separated by >300 chars)");
    }

}
