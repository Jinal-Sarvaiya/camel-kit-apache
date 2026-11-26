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

        // Must defer all if source is pending
        assertTrue(q7aSection.contains("If source contract is \"pending\", defer ALL"),
                "Question 7a must defer all mappings if source contract is pending");
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

        // Must return to Question 9
        assertTrue(q7aSection.contains("return to Question 9"),
                "Question 7a must explicitly return to Question 9 after completion");
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

        // Must return to Question 9
        assertTrue(content.contains("return control to `camel-brainstorm` at Question 9"),
                "DataMapper interview must return to Question 9");
    }

    @Test
    @DisplayName("DataMapper canonicalize must return to Question 9")
    void testDataMapperCanonicalizeReturnsToQ9() throws Exception {
        Path canonicalizePath = Path.of("src/main/resources/skills/shared/datamapper-canonicalize.md");
        assertTrue(Files.exists(canonicalizePath), "DataMapper canonicalize must exist");

        String content = Files.readString(canonicalizePath);

        // Must return to Question 9, not Question 8
        assertTrue(content.contains("return to `camel-brainstorm` at Question 9"),
                "DataMapper canonicalize must return to Question 9");

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

        // Rules must be inline in text.append() calls
        assertTrue(content.contains("Interface Contract Discovery"),
                "DISCOVERY prompt must contain Interface Contract Discovery heading");

        assertTrue(content.contains("Ask One Boundary") && content.contains("text.append"),
                "DISCOVERY prompt must contain inline 'Ask One Boundary at a Time' rule");

        assertTrue(content.contains("Analyze Supplied Material") && content.contains("text.append"),
                "DISCOVERY prompt must contain inline 'Analyze Supplied Material First' rule");

        assertTrue(content.contains("Four Possible Statuses") && content.contains("text.append"),
                "DISCOVERY prompt must contain inline 'Four Possible Statuses' rule");

        assertTrue(content.contains("Status: existing") && content.contains("Status: pending"),
                "DISCOVERY prompt must define status values inline");
    }

    @Test
    @DisplayName("Ship DISCOVERY prompt must tell worker to record in report and unansweredQuestions")
    void testShipDiscoveryRecordingInstructions() throws Exception {
        Path shipCoordinatorPath = Path.of(SHIP_COORDINATOR_PATH);
        String content = Files.readString(shipCoordinatorPath);

        assertTrue(content.contains("Stage.DISCOVERY"), "DISCOVERY stage handling must exist");

        // Must tell worker to record in report and unansweredQuestions
        assertTrue(content.contains("report") && content.contains("unansweredQuestions"),
                "DISCOVERY prompt must mention 'report' and 'unansweredQuestions' fields");

        assertTrue(content.contains("Record all interface contract findings"),
                "DISCOVERY prompt must instruct to record contract findings");

        assertTrue(content.contains("Recording in Ship Result") || content.contains("Recording"),
                "DISCOVERY prompt must have recording instructions section");
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
}
