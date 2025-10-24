package stepdefinitions;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import java.util.List;
import java.util.Map;

import org.aeonbits.owner.ConfigCache;
import org.json.JSONArray;
import org.json.JSONObject;

import Config.TestConfig;
import io.cucumber.java.Scenario;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import model.ScenarioChangeTracker;
import service.FeatureChangeDetector;

public class utils {

    static {
        RestAssured.useRelaxedHTTPSValidation();
    }

    private String jiraAuthHeader;   // Basic <base64(email:apiToken)>
    private String xrayToken;        // Bearer <xray_jwt>
    private Response lastHttpResponse;

    public static final TestConfig cfg = ConfigCache.getOrCreate(TestConfig.class);

    // Jira
    public static final String email = cfg.mail();
    public static final String apiToken = cfg.ApiKey();
    public static final String jiraBase = cfg.postUrl(); // e.g., https://<site>.atlassian.net/rest/api/3/issue

    // Xray
    public static final String xrayClientId = cfg.xrayClientId();
    public static final String xrayClientSecret = cfg.xrayClientSecret();
    public static final String xrayGraphQL = "https://xray.cloud.getxray.app/api/v2/graphql";
    public static final String xrayAuth = "https://xray.cloud.getxray.app/api/v2/authenticate";

    // State
    private String lastTestKey;
    private String lastTestId;
    private String lastPreconditionKey;
    private String lastPreconditionId;
    private String preId;

    // Accessors
    public String getLastTestKey() { return lastTestKey; }
    public String getLastTestId()  { return lastTestId; }
    public String getLastPreconditionKey() { return lastPreconditionKey; }
    public String getLastPreconditionId()  { return lastPreconditionId; }
    public Response getLastHttpResponse()  { return lastHttpResponse; }

    // ---------- Auth ----------
    public void authJira() {
        String basic = java.util.Base64.getEncoder().encodeToString((email + ":" + apiToken).getBytes());
        jiraAuthHeader = "Basic " + basic;
        System.out.println("Jira auth prepared (Basic header set).");
    }

    public void authXray() {
        JSONObject authBody = new JSONObject()
            .put("client_id", xrayClientId)
            .put("client_secret", xrayClientSecret);

        Response authResp = given()
            .header("Content-Type", "application/json")
            .body(authBody.toString())
            .when()
            .post(xrayAuth);

        if (authResp.statusCode() != 200) {
            throw new RuntimeException("Xray authentication failed: " + authResp.asString());
        }
        xrayToken = authResp.asString().replace("\"", "");
        if (xrayToken == null || xrayToken.isEmpty()) {
            throw new RuntimeException("Xray token is empty; check client id/secret");
        }
        System.out.println("Xray auth OK (token acquired).");
    }

    // ---------- Helpers ----------
    public static String readFeatureText(Scenario scenario) {
        try {
            java.net.URI uri = scenario.getUri();
            if (uri == null) throw new IllegalStateException("Cannot resolve feature URI from Scenario.");
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                java.nio.file.Path p = java.nio.file.Paths.get(uri);
                return java.nio.file.Files.readString(p);
            } else {
                String resourcePath = uri.getPath();
                try (java.io.InputStream is = Thread.currentThread()
                        .getContextClassLoader()
                        .getResourceAsStream(resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath)) {
                    if (is == null) throw new IllegalStateException("Feature not on classpath: " + resourcePath);
                    return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read feature file", e);
        }
    }
    
    // Extract the Background steps as a single multi-line string (only step lines).
    public static String extractBackgroundSteps(String featureText) {
        String[] lines = featureText.split("\\R");
        boolean inBg = false;
        StringBuilder sb = new StringBuilder();
        for (String raw : lines) {
            String line = raw.stripTrailing();
            String trimmed = line.trim();
            if (!inBg) {
                if (trimmed.startsWith("Background:")) {
                    inBg = true;
                }
                continue;
            }
            if (trimmed.startsWith("Scenario:") || trimmed.startsWith("Scenario Outline:")) {
                break;
            }
            String lower = trimmed.toLowerCase();
            if (lower.startsWith("given ") || lower.startsWith("when ") || lower.startsWith("then ")
                    || lower.startsWith("and ") || lower.startsWith("but ")) {
                sb.append(trimmed).append("\n");
            }
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    // Full Background block (title + content)
    public static String extractBackgroundBlock(String featureText) {
        String[] lines = featureText.split("\\R");
        StringBuilder backgroundBlock = new StringBuilder();
        boolean insideBackground = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("Background:")) {
                insideBackground = true;
                backgroundBlock.append(line).append(System.lineSeparator());
                continue;
            }

            if (insideBackground && (trimmed.startsWith("Scenario:") || trimmed.startsWith("Feature:") || trimmed.startsWith("@"))) {
                break;
            }

            if (insideBackground) {
                backgroundBlock.append(line).append(System.lineSeparator());
            }
        }

        if (backgroundBlock.length() == 0) {
            return null;
        } else {
            return backgroundBlock.toString().trim();
        }
    }

    // ADF helpers
    public static JSONObject adfCodeBlock(String language, String text) {
        JSONObject codeMark = new JSONObject()
            .put("type", "text")
            .put("text", text);
        JSONObject codeContent = new JSONObject()
            .put("type", "codeBlock")
            .put("attrs", new JSONObject().put("language", language == null ? "text" : language))
            .put("content", new JSONArray().put(
                new JSONObject().put("type", "paragraph").put("content", new JSONArray().put(codeMark))
            ));
        return new JSONObject()
            .put("type", "doc")
            .put("version", 1)
            .put("content", new JSONArray().put(codeContent));
    }

    public static JSONObject adfParagraph(String text) {
        return new JSONObject()
            .put("type", "doc")
            .put("version", 1)
            .put("content", new JSONArray().put(
                new JSONObject().put("type", "paragraph").put("content", new JSONArray().put(
                    new JSONObject().put("type", "text").put("text", text)
                ))
            ));
    }

    public static String escapeJqlLiteral(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String slugify(String s) {
        if (s == null) return null;
        return s.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private static String featureFileName(Scenario scenario) {
        java.net.URI uri = scenario.getUri();
        if (uri == null) return "unknown.feature";
        return "file".equalsIgnoreCase(uri.getScheme())
            ? java.nio.file.Paths.get(uri).getFileName().toString()
            : new java.io.File(uri.getPath()).getName();
    }

    // ---------- Scenario parsing for per-scenario uploads ----------

    private static final class ScenarioInstance {
        final String name;         // Concrete scenario name (placeholders expanded; may have [ex n] suffix)
        final String labelSlug;    // Slug for label
        final String gherkinBlock; // Only this scenario’s block (title + steps) suitable for Xray upload

        ScenarioInstance(String name, String gherkinBlock) {
            this.name = name;
            this.labelSlug = name.trim().replaceAll("\\s+", "-");
            this.gherkinBlock = gherkinBlock;
        }
    }

    private static List<ScenarioInstance> parseFeatureIntoInstances(String featureText) {
        java.util.ArrayList<ScenarioInstance> out = new java.util.ArrayList<>();

        String[] linesArr = featureText.split("\\R");
        java.util.List<String> lines = java.util.Arrays.asList(linesArr);

        // State
        enum Block { NONE, SCENARIO, OUTLINE }
        Block block = Block.NONE;

        String currentScenarioTitle = null;
        java.util.List<String> currentScenarioSteps = new java.util.ArrayList<>();

        String outlineTitle = null;
        java.util.List<String> outlineSteps = new java.util.ArrayList<>();
        java.util.List<String> examplesHeader = null;
        java.util.List<java.util.List<String>> examplesRows = new java.util.ArrayList<>();

        // Iterate and parse
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("@")) continue; // ignore tags for this minimal parser
            if (line.regionMatches(true, 0, "Feature:", 0, 8)) continue;
            if (line.regionMatches(true, 0, "Background:", 0, 11)) continue;

            if (line.regionMatches(true, 0, "Scenario Outline:", 0, 17)
             || line.regionMatches(true, 0, "Scenario Template:", 0, 18)) {
                // flush previous block
                if (block == Block.SCENARIO) {
                    if (currentScenarioTitle != null) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Scenario: ").append(currentScenarioTitle).append("\n");
                        for (String s : currentScenarioSteps) sb.append(s).append("\n");
                        out.add(new ScenarioInstance(currentScenarioTitle, sb.toString().trim()));
                        System.out.println("Parsed scenario instance: " + currentScenarioTitle);
                    }
                    currentScenarioTitle = null;
                    currentScenarioSteps.clear();
                } else if (block == Block.OUTLINE) {
                    if (outlineTitle != null) {
                        if (examplesHeader == null || examplesRows.isEmpty()) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("Scenario: ").append(outlineTitle).append("\n");
                            for (String s : outlineSteps) sb.append(s).append("\n");
                            out.add(new ScenarioInstance(outlineTitle, sb.toString().trim()));
                            System.out.println("Parsed outline (no examples) as scenario: " + outlineTitle);
                        } else {
                            int idx = 1;
                            for (java.util.List<String> rowVals : examplesRows) {
                                java.util.Map<String,String> row = new java.util.LinkedHashMap<>();
                                for (int i = 0; i < Math.min(examplesHeader.size(), rowVals.size()); i++) {
                                    row.put(examplesHeader.get(i), rowVals.get(i));
                                }
                                String expandedTitle = expandPlaceholders(outlineTitle, row);
                                StringBuilder sb = new StringBuilder();
                                sb.append("Scenario: ").append(expandedTitle).append(" [ex ").append(idx).append("]").append("\n");
                                for (String s : outlineSteps) {
                                    String exp = expandPlaceholders(s, row);
                                    sb.append(exp).append("\n");
                                }
                                out.add(new ScenarioInstance(expandedTitle + " [ex " + idx + "]", sb.toString().trim()));
                               // System.out.println("Parsed outline example as scenario: " + expandedTitle + " [ex " + idx + "]");
                                idx++;
                            }
                        }
                    }
                    outlineTitle = null;
                    outlineSteps.clear();
                    examplesHeader = null;
                    examplesRows.clear();
                }

                // start new outline
                block = Block.OUTLINE;
                outlineTitle = line.substring(line.indexOf(':') + 1).trim();
                outlineSteps.clear();
                examplesHeader = null;
                examplesRows.clear();
                continue;
            }

            if (line.regionMatches(true, 0, "Scenario:", 0, 9)) {
                // flush previous block
                if (block == Block.SCENARIO) {
                    if (currentScenarioTitle != null) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Scenario: ").append(currentScenarioTitle).append("\n");
                        for (String s : currentScenarioSteps) sb.append(s).append("\n");
                        out.add(new ScenarioInstance(currentScenarioTitle, sb.toString().trim()));
                        System.out.println("Parsed scenario instance: " + currentScenarioTitle);
                    }
                    currentScenarioTitle = null;
                    currentScenarioSteps.clear();
                } else if (block == Block.OUTLINE) {
                    if (outlineTitle != null) {
                        if (examplesHeader == null || examplesRows.isEmpty()) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("Scenario: ").append(outlineTitle).append("\n");
                            for (String s : outlineSteps) sb.append(s).append("\n");
                            out.add(new ScenarioInstance(outlineTitle, sb.toString().trim()));
                            System.out.println("Parsed outline (no examples) as scenario: " + outlineTitle);
                        } else {
                            int idx = 1;
                            for (java.util.List<String> rowVals : examplesRows) {
                                java.util.Map<String,String> row = new java.util.LinkedHashMap<>();
                                for (int i = 0; i < Math.min(examplesHeader.size(), rowVals.size()); i++) {
                                    row.put(examplesHeader.get(i), rowVals.get(i));
                                }
                                String expandedTitle = expandPlaceholders(outlineTitle, row);
                                StringBuilder sb = new StringBuilder();
                                sb.append("Scenario: ").append(expandedTitle).append(" [ex ").append(idx).append("]").append("\n");
                                for (String s : outlineSteps) {
                                    String exp = expandPlaceholders(s, row);
                                    sb.append(exp).append("\n");
                                }
                                out.add(new ScenarioInstance(expandedTitle + " [ex " + idx + "]", sb.toString().trim()));
                                System.out.println("Parsed outline example as scenario: " + expandedTitle + " [ex " + idx + "]");
                                idx++;
                            }
                        }
                    }
                    outlineTitle = null;
                    outlineSteps.clear();
                    examplesHeader = null;
                    examplesRows.clear();
                }

                // start new scenario
                block = Block.SCENARIO;
                currentScenarioTitle = line.substring(line.indexOf(':') + 1).trim();
                currentScenarioSteps.clear();
                continue;
            }

            // collect steps/examples
            if (block == Block.SCENARIO) {
                if (isStep(line)) currentScenarioSteps.add(line);
                continue;
            }

            if (block == Block.OUTLINE) {
                if (line.regionMatches(true, 0, "Examples:", 0, 9)) {
                    examplesHeader = null;
                    examplesRows.clear();
                    continue;
                }
                if (line.startsWith("|")) {
                    java.util.List<String> cells = parseRow(line);
                    if (examplesHeader == null) {
                        examplesHeader = cells;
                    } else {
                        examplesRows.add(cells);
                    }
                    continue;
                }
                if (isStep(line)) outlineSteps.add(line);
                continue;
            }
        }

        // flush tail
        if (block == Block.SCENARIO) {
            if (currentScenarioTitle != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("Scenario: ").append(currentScenarioTitle).append("\n");
                for (String s : currentScenarioSteps) sb.append(s).append("\n");
                out.add(new ScenarioInstance(currentScenarioTitle, sb.toString().trim()));
                System.out.println("Parsed scenario instance: " + currentScenarioTitle);
            }
        } else if (block == Block.OUTLINE) {
            if (outlineTitle != null) {
                if (examplesHeader == null || examplesRows.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Scenario: ").append(outlineTitle).append("\n");
                    for (String s : outlineSteps) sb.append(s).append("\n");
                    out.add(new ScenarioInstance(outlineTitle, sb.toString().trim()));
                    System.out.println("Parsed outline (no examples) as scenario: " + outlineTitle);
                } else {
                    int idx = 1;
                    for (java.util.List<String> rowVals : examplesRows) {
                        java.util.Map<String,String> row = new java.util.LinkedHashMap<>();
                        for (int i = 0; i < Math.min(examplesHeader.size(), rowVals.size()); i++) {
                            row.put(examplesHeader.get(i), rowVals.get(i));
                        }
                        String expandedTitle = expandPlaceholders(outlineTitle, row);
                        StringBuilder sb = new StringBuilder();
                        sb.append("Scenario: ").append(expandedTitle).append(" [ex ").append(idx).append("]").append("\n");
                        for (String s : outlineSteps) {
                            String exp = expandPlaceholders(s, row);
                            sb.append(exp).append("\n");
                        }
                        out.add(new ScenarioInstance(expandedTitle + " [ex " + idx + "]", sb.toString().trim()));
                        System.out.println("Parsed outline example as scenario: " + expandedTitle + " [ex " + idx + "]");
                        idx++;
                    }
                }
            }
        }

        return out;
    }

    private static boolean isStep(String line) {
        return line.matches("(?i)^(Given|When|Then|And|But|\\*)\\b.*");
    }

    private static java.util.List<String> parseRow(String line) {
        String[] parts = line.split("\\|");
        java.util.List<String> cells = new java.util.ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) cells.add(t);
        }
        return cells;
    }

    private static String expandPlaceholders(String text, java.util.Map<String,String> row) {
        if (text == null || row == null || row.isEmpty()) return text;
        String out = text;
        for (java.util.Map.Entry<String,String> e : row.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();
            if (key != null && val != null) {
                out = out.replace("<" + key + ">", val);
            }
        }
        return out;
    }

    // ---------- Core flows ----------
 // Add this field to track scenario changes
    private static ScenarioChangeTracker changeTracker = null;

    // Initialize change tracker before processing (call once at startup)
    public static void initializeChangeTracking(String fromCommit, String toCommit) {
        System.out.println("Initializing change tracking...");
        changeTracker = FeatureChangeDetector.detectChanges(fromCommit, toCommit);
    }

    // Modified createOrReuseTest with change detection integration
    public void createOrReuseTestWithChangeDetection(String projectKey, Scenario scenario) {
        if (jiraAuthHeader == null) authJira();
        if (xrayToken == null) authXray();

        String featureText = readFeatureText(scenario);
        String featureName = featureFileName(scenario);
        List<ScenarioInstance> instances = parseFeatureIntoInstances(featureText);
        System.out.println("Total scenario instances parsed: " + instances.size());

        java.util.List<String> createdOrReusedTestIds = new java.util.ArrayList<>();

        for (ScenarioInstance inst : instances) {
            String scenarioSlug = inst.labelSlug;
            String featureLabel  = "feature:" + featureName;
            String scenarioLabel = "scenario:" + scenarioSlug;
            
            // Build scenario key for change tracking
            String scenarioKey = featureLabel + "::" + scenarioLabel;
            
            // Check if this scenario has changed
            boolean hasChanged = changeTracker != null && changeTracker.hasChanged(scenarioKey);
            
            System.out.println("\nProcessing scenario: " + inst.name);
            System.out.println("  Change status: " + (hasChanged ? "CHANGED/NEW" : "UNCHANGED"));

            String jql = String.format(
                    "project=%s AND issuetype=Test AND labels in (\"%s\") AND labels in (\"%s\")",
                    projectKey,
                    featureLabel.replace("\"","\\\""),
                    scenarioLabel.replace("\"","\\\"")
                );

            String searchUrl = jiraBase.replace("/issue", "/search/jql");

            JSONObject payload = new JSONObject()
              .put("jql", jql)
              .put("fields", new JSONArray().put("id").put("key"))
              .put("maxResults", 1);

            System.out.println("  Searching Test by labels: " + scenarioLabel + " & " + featureLabel);
            Response search = given()
              .header("Authorization", jiraAuthHeader)
              .header("Content-Type", "application/json")
              .body(payload.toString())
              .when()
              .post(searchUrl);

            if (search.statusCode() != 200) {
              throw new RuntimeException("Jira search failed: " + search.asString());
            }

            List<Map<String, Object>> issues = search.jsonPath().getList("issues");
            if (issues != null && !issues.isEmpty()) {
                // Test exists
                Map<String, Object> first = issues.get(0);
                lastTestKey = String.valueOf(first.get("key"));
                lastTestId  = String.valueOf(first.get("id"));
                lastHttpResponse = search;
                System.out.println("  Found existing Test: key=" + lastTestKey + " id=" + lastTestId);
                
                // Only update if changed
                if (hasChanged) {
                    System.out.println("  → Updating Test (scenario changed)");
                    ensureCucumberType();
                    uploadPerScenarioGherkin(lastTestId, inst.gherkinBlock);
                } else {
                    System.out.println("  → Skipping update (scenario unchanged)");
                }
            } else {
                // Create new test
                System.out.println("  → Creating new Test");
                String summary = inst.name;
                JSONObject adfDescription = new JSONObject()
                    .put("type", "doc")
                    .put("version", 1)
                    .put("content", new JSONArray().put(
                        new JSONObject()
                            .put("type", "paragraph")
                            .put("content", new JSONArray().put(
                                new JSONObject().put("type", "text")
                                    .put("text", "Created via REST with ADF for " + featureLabel + ".")
                            ))
                    ));

                JSONArray labels = new JSONArray()
                    .put("automation")
                    .put(scenarioLabel)
                    .put(featureLabel);

                JSONObject body = new JSONObject()
                    .put("fields", new JSONObject()
                        .put("project", new JSONObject().put("key", projectKey))
                        .put("summary", summary)
                        .put("description", adfDescription)
                        .put("issuetype", new JSONObject().put("name", "Test"))
                        .put("labels", labels)
                    );

                Response create = given()
                    .header("Authorization", jiraAuthHeader)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .when()
                    .post(jiraBase);

                create.then().statusCode(201).body("key", notNullValue());
                lastTestKey = create.jsonPath().getString("key");

                Response getIssue = given()
                    .header("Authorization", jiraAuthHeader)
                    .header("Content-Type", "application/json")
                    .when()
                    .get(jiraBase + "/" + lastTestKey);

                getIssue.then().statusCode(200);
                lastTestId = getIssue.jsonPath().getString("id");
                lastHttpResponse = create;
                System.out.println("  Created Test: key=" + lastTestKey + " id=" + lastTestId);
                
                ensureCucumberType();
                uploadPerScenarioGherkin(lastTestId, inst.gherkinBlock);
            }

            createdOrReusedTestIds.add(lastTestId);
        }

        // Background Precondition (same as before)
        String bgBlock = extractBackgroundBlock(featureText);
        String bgSteps = extractBackgroundSteps(featureText);
        if (bgBlock != null && bgSteps != null && !createdOrReusedTestIds.isEmpty()) {
            String createdPreId = createBackgroundPrecondition(projectKey, scenario, bgBlock, bgSteps);
            for (String tid : createdOrReusedTestIds) {
                linkPreconditionToTest(tid, createdPreId);
            }
            lastPreconditionId = createdPreId;
        }
    }


    // Upload only the scenario block to the Test
    private void uploadPerScenarioGherkin(String testIssueId, String scenarioGherkin) {
        if (xrayToken == null) authXray();
        String preview = scenarioGherkin == null ? "" : scenarioGherkin.split("\\R", 2)[0];
        System.out.println("Uploading scenario Gherkin to Test issueId=" + testIssueId + " preview=\"" + preview + "\"");

        String query =
            "mutation UpdateGherkin($issueId: String!, $gherkin: String!) { " +
            "  updateGherkinTestDefinition(issueId: $issueId, gherkin: $gherkin) { " +
            "    issueId testType { name } gherkin jira(fields:[\"key\"]) " +
            "  } " +
            "}";

        JSONObject variables = new JSONObject()
            .put("issueId", testIssueId)
            .put("gherkin", scenarioGherkin);

        JSONObject gql = new JSONObject()
            .put("query", query)
            .put("variables", variables);

        Response gqlResp = given()
            .header("Authorization", "Bearer " + xrayToken)
            .header("Content-Type", "application/json")
            .body(gql.toString())
            .when()
            .post(xrayGraphQL);

        gqlResp.then().statusCode(200);
        System.out.println("Uploaded scenario Gherkin to Test issueId=" + testIssueId);
    }

    public void ensureCucumberType() {
        if (xrayToken == null) authXray();
        if (lastTestId == null) throw new RuntimeException("No Test identified to change type.");

        System.out.println("Changing Test type to Cucumber for issueId=" + lastTestId);

        String mutation = String.format(
            "mutation { updateTestType(issueId: \"%s\", testType: { name: \"Cucumber\" }) { issueId testType { name } jira(fields:[\"key\"]) } }",
            lastTestId
        );

        JSONObject gql = new JSONObject().put("query", mutation);

        Response gqlResp = given()
            .header("Authorization", "Bearer " + xrayToken)
            .header("Content-Type", "application/json")
            .body(gql.toString())
            .when()
            .post(xrayGraphQL);

        gqlResp.then().statusCode(200);
        System.out.println("Changed Test type to Cucumber for issueId=" + lastTestId);
    }
    
    public void ensurePreconditionType() {
        if (xrayToken == null) authXray();

        String mutation = String.format(
            "mutation { updatePrecondition(issueId: \"%s\", data: { preconditionType: { name: \"Cucumber\" } }) { issueId preconditionType { kind name } definition } }" ,
            preId
        );

        JSONObject gql = new JSONObject().put("query", mutation);

        Response gqlResp = given()
            .header("Authorization", "Bearer " + xrayToken)
            .header("Content-Type", "application/json")
            .body(gql.toString())
            .when()
            .post(xrayGraphQL);

        gqlResp.then().statusCode(200);
        System.out.println("Set Precondition type=Cucumber for id=" + preId);
    }

    public void uploadGherkinFromScenario(Scenario scenario) {
        if (xrayToken == null) authXray();
        if (lastTestId == null) throw new RuntimeException("No Test identified to add script.");

        String gherkin = readFeatureText(scenario);
        System.out.println("Uploading FULL feature Gherkin to Test issueId=" + lastTestId + ".");

        String query =
            "mutation UpdateGherkin($issueId: String!, $gherkin: String!) { " +
            "  updateGherkinTestDefinition(issueId: $issueId, gherkin: $gherkin) { " +
            "    issueId testType { name } gherkin jira(fields:[\"key\"]) " +
            "  } " +
            "}";

        JSONObject variables = new JSONObject()
            .put("issueId", lastTestId)
            .put("gherkin", gherkin);

        JSONObject gql = new JSONObject()
            .put("query", query)
            .put("variables", variables);

        Response gqlResp = given()
            .header("Authorization", "Bearer " + xrayToken)
            .header("Content-Type", "application/json")
            .body(gql.toString())
            .when()
            .post(xrayGraphQL);

        gqlResp.then().statusCode(200);
        System.out.println("Uploaded FULL feature Gherkin to Test issueId=" + lastTestId);
    }

    // Background Pre-Condition: create once and link many

    // Create the Background precondition (returns precond Id)
    private String createBackgroundPrecondition(String projectKey, Scenario scenario, String bgBlock, String bgSteps) {
        if (jiraAuthHeader == null) authJira();
        if (xrayToken == null) authXray();

        String featureName = featureFileName(scenario);
        String featureLabel  = "feature:" + featureName;
        String backgroundLabel = "background:" + slugify(bgBlock);

        final String preconditionIssueTypeName = "Precondition";
        JSONObject fields = new JSONObject()
            .put("project", new JSONObject().put("key", projectKey))
            .put("summary", "Precondition")
            .put("issuetype", new JSONObject().put("name", preconditionIssueTypeName))
            .put("labels", new JSONArray()
                .put("automation")
                .put(featureLabel)
                .put(backgroundLabel)
            );

        JSONObject body = new JSONObject().put("fields", fields);

        Response createResp = given()
            .header("Authorization", jiraAuthHeader)
            .header("Content-Type", "application/json")
            .body(body.toString())
            .when()
            .post(jiraBase);

        if (createResp.statusCode() != 201) {
            System.out.println("Precondition create failed; retrying with description...");
            JSONObject simpleAdf = adfParagraph("Background:\n" + bgSteps);
            fields.put("description", simpleAdf);
            Response retry = given()
                .header("Authorization", jiraAuthHeader)
                .header("Content-Type", "application/json")
                .body(new JSONObject().put("fields", fields).toString())
                .when()
                .post(jiraBase);
            if (retry.statusCode() != 201) {
                throw new RuntimeException("Create Precondition failed: " + retry.asString());
            }
            createResp = retry;
        }

        String preKey = createResp.jsonPath().getString("key");
        Response getIssue = given()
            .header("Authorization", jiraAuthHeader)
            .header("Accept", "application/json")
            .when()
            .get(jiraBase + "/" + preKey);

        getIssue.then().statusCode(200);
        String createdPreId = getIssue.jsonPath().getString("id");
        System.out.println("Created Precondition: key=" + preKey + " id=" + createdPreId);

        // Set Precondition type to Cucumber and upload definition (bg steps)
        System.out.println("Setting Precondition type=Cucumber for id=" + createdPreId);
        String mutation1 = String.format(
            "mutation { updatePrecondition(issueId: \"%s\", data: { preconditionType: { name: \"Cucumber\" } }) { issueId preconditionType { kind name } definition } }",
            createdPreId
        );
        JSONObject gql1 = new JSONObject().put("query", mutation1);
        Response gqlResp1 = given()
            .header("Authorization", "Bearer " + xrayToken)
            .header("Content-Type", "application/json")
            .body(gql1.toString())
            .when()
            .post(xrayGraphQL);
        gqlResp1.then().statusCode(200);
        System.out.println("Set Precondition type=Cucumber for id=" + createdPreId);

        System.out.println("Uploading Background steps to Precondition id=" + createdPreId);
        String query =
            "mutation UpdatePreDef($issueId: String!, $gherkin: String!) { " +
            " updatePrecondition(issueId: $issueId, data: { definition: $gherkin }) { " +
            " issueId preconditionType { name } definition jira(fields:[\"key\"]) " +
            " } " +
            "}";
        JSONObject variables = new JSONObject()
            .put("issueId", createdPreId)
            .put("gherkin", bgSteps);
        JSONObject gql2 = new JSONObject()
            .put("query", query)
            .put("variables", variables);
        Response gqlResp2 = given()
            .header("Authorization", "Bearer " + xrayToken)
            .header("Content-Type", "application/json")
            .body(gql2.toString())
            .when()
            .post(xrayGraphQL);
        gqlResp2.then().statusCode(200);
        System.out.println("Uploaded Background steps to Precondition id=" + createdPreId);

        lastPreconditionKey = preKey;
        preId = createdPreId; // store
        return createdPreId;
    }

    // Link a precondition to a specific Test id
    private void linkPreconditionToTest(String testIssueId, String preIssueId) {
        if (xrayToken == null) authXray();
        System.out.println("Linking Precondition id=" + preIssueId + " to Test id=" + testIssueId);
        String mutation = String.format(
            "mutation { addPreconditionsToTest(issueId: \"%s\", preconditionIssueIds: [\"%s\"]) { addedPreconditions warning } }",
            testIssueId, preIssueId
        );
        JSONObject gql = new JSONObject().put("query", mutation);
        Response gqlResp = given()
            .header("Authorization", "Bearer " + xrayToken)
            .header("Content-Type", "application/json")
            .body(gql.toString())
            .when()
            .post(xrayGraphQL);
        gqlResp.then().statusCode(200);
       // System.out.println("Linked Precondition id=" + preIssueId + " to Test id=" + testIssueId);
    }
}
