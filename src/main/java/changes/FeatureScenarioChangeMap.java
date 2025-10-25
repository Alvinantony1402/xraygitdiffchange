package changes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local-focused change detector.
 * Default FROM=HEAD~1, TO=HEAD for local runs (overridable via env).
 * Flags:
 *  - NEW: present now, absent in FROM revision
 *  - CHANGED: any added/removed lines overlap (header-inclusive) scenario range, with small hunk buffer
 *  - UNCHANGED: default
 */
public class FeatureScenarioChangeMap {

  private static final String FEATURES_ROOT = envOrDefault("FEATURES_ROOT", "src/test/java/features");
  private static final int HUNK_BUFFER_LINES = 1; // widen detection around hunk edges

  private static Map<String, Map<String, String>> lastComputed = Collections.emptyMap();
  public static Map<String, Map<String, String>> latest() { return lastComputed; }

  public static Map<String, Map<String, String>> buildLocal() {
    // Prefer local last commit diff by default
    String fromRef = envOrDefault("FROM_COMMIT", "HEAD~1");
    String toRef   = envOrDefault("TO_COMMIT", "HEAD");
    return build(fromRef, toRef);
  }

  public static Map<String, Map<String, String>> build(String fromRef, String toRef) {
    Objects.requireNonNull(fromRef, "fromRef required");
    Objects.requireNonNull(toRef, "toRef required");

    try {
      Map<String, List<String>> fileToLines = readAllFeatureFiles();
      Map<String, Map<String, LineRange>> fileScenarioRanges = new HashMap<>();
      Map<String, Set<String>> fileScenarioNames = new HashMap<>();

      // Parse current .feature files and compute header-inclusive scenario ranges
      for (Map.Entry<String, List<String>> e : fileToLines.entrySet()) {
        String featurePath = e.getKey();
        List<String> lines = e.getValue();
        Map<String, LineRange> ranges = computeScenarioRangesHeaderInclusive(lines);
        fileScenarioRanges.put(featurePath, ranges);
        fileScenarioNames.put(featurePath, ranges.keySet());
      }

      // Which feature files changed?
      Set<String> changedFeaturePaths = listChangedFeatureFiles(fromRef, toRef);

      // Initialize all to UNCHANGED
      Map<String, Map<String, String>> result = new LinkedHashMap<>();
      for (String featurePath : fileScenarioNames.keySet()) {
        String featureName = Paths.get(featurePath).getFileName().toString();
        Map<String, String> scenarioMap = new LinkedHashMap<>();
        for (String scenario : fileScenarioNames.get(featurePath)) {
          scenarioMap.put(scenario, "UNCHANGED");
        }
        result.put(featureName, scenarioMap);
      }

      // For each changed feature, mark CHANGED via overlap with buffered hunks and detect NEW
      for (String featurePath : changedFeaturePaths) {
        List<DiffHunk> hunks = readDiffHunks(fromRef, toRef, featurePath);
        // apply small buffer around hunks to catch near-title edits
        List<DiffHunk> buffered = bufferHunks(hunks, HUNK_BUFFER_LINES);

        Map<String, LineRange> ranges = fileScenarioRanges.getOrDefault(featurePath, Collections.emptyMap());
        Set<String> currentScenarios = fileScenarioNames.getOrDefault(featurePath, Collections.emptySet());

        // Flag changed scenarios
        for (DiffHunk h : buffered) {
          for (Map.Entry<String, LineRange> ent : ranges.entrySet()) {
            if (ent.getValue().overlaps(h.addStart, h.addEnd)) {
              mark(result, featurePath, ent.getKey(), "CHANGED");
            }
          }
        }

        // Detect new scenarios by comparing current names to names from FROM revision
        Set<String> previousNames = readScenarioNamesAtRef(fromRef, featurePath);
        for (String now : currentScenarios) {
          if (!previousNames.contains(now)) {
            mark(result, featurePath, now, "NEW");
          }
        }
      }

      lastComputed = result;
      return result;

    } catch (Exception ex) {
      throw new RuntimeException("Change map build failed: " + ex.getMessage(), ex);
    }
  }

  // ---------- Helpers ----------

  private static Map<String, List<String>> readAllFeatureFiles() throws Exception {
    Map<String, List<String>> out = new LinkedHashMap<>();
    Path root = Paths.get(FEATURES_ROOT);
    if (!Files.exists(root)) return out;
    try (var stream = Files.walk(root)) {
      for (Path p : (Iterable<Path>) stream::iterator) {
        if (Files.isRegularFile(p) && p.toString().endsWith(".feature")) {
          out.put(p.toString().replace('\\', '/'), Files.readAllLines(p));
        }
      }
    }
    return out;
  }

  // Start range at the Scenario header line (not header+1), end at the next header line (exclusive)
  private static Map<String, LineRange> computeScenarioRangesHeaderInclusive(List<String> lines) {
    Map<String, LineRange> ranges = new LinkedHashMap<>();
    String current = null;
    int start = -1; // 1-based
    for (int i = 0; i < lines.size(); i++) {
      String t = lines.get(i).trim();
      if (t.regionMatches(true, 0, "Scenario Outline:", 0, 17) || t.regionMatches(true, 0, "Scenario:", 0, 9)) {
        if (current != null && start >= 1) {
          ranges.put(current, new LineRange(start, i + 1)); // end is exclusive, current i line starts next
        }
        String name = t.replaceFirst("(?i)Scenario\\s*(Outline)?:\\s*", "").trim();
        current = name;
        start = i + 1; // store as 1-based index including header line
      }
    }
    if (current != null && start >= 1) {
      ranges.put(current, new LineRange(start, lines.size() + 1));
    }
    return ranges;
  }

  private static Set<String> listChangedFeatureFiles(String fromRef, String toRef) throws Exception {
    List<String> names = execAndRead("git", "diff", fromRef, toRef, "--", FEATURES_ROOT);
    Set<String> out = new LinkedHashSet<>();
    for (String n : names) {
      String unix = n.replace('\\', '/');
      if (unix.endsWith(".feature")) out.add(unix);
    }
    return out;
  }

  private static List<DiffHunk> readDiffHunks(String fromRef, String toRef, String filePath) throws Exception {
    List<String> lines = execAndRead("git", "diff", "-U0", fromRef, toRef, "--", filePath);
    List<DiffHunk> hunks = new ArrayList<>();
    Pattern header = Pattern.compile("@@\\s+-\\d+(?:,\\d+)?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@");
    for (String l : lines) {
      Matcher m = header.matcher(l);
      if (m.find()) {
        int addStart = Integer.parseInt(m.group(1));
        int addCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        int addEnd = addStart + Math.max(addCount, 0);
        hunks.add(new DiffHunk(addStart, addEnd));
      }
    }
    return hunks;
  }

  private static List<DiffHunk> bufferHunks(List<DiffHunk> hunks, int buffer) {
    if (buffer <= 0) return hunks;
    List<DiffHunk> out = new ArrayList<>(hunks.size());
    for (DiffHunk h : hunks) {
      int s = Math.max(1, h.addStart - buffer);
      int e = h.addEnd + buffer;
      out.add(new DiffHunk(s, e));
    }
    return out;
  }

  private static Set<String> readScenarioNamesAtRef(String ref, String filePath) throws Exception {
    List<String> content = execAndReadSafe("git", "show", ref + ":" + filePath);
    if (content.isEmpty()) return Collections.emptySet();
    return computeScenarioRangesHeaderInclusive(content).keySet();
  }

  private static void mark(Map<String, Map<String, String>> result, String featurePath, String scenarioName, String status) {
    String featureName = Paths.get(featurePath).getFileName().toString();
    Map<String, String> scenarioMap = result.computeIfAbsent(featureName, k -> new LinkedHashMap<>());
    String cur = scenarioMap.get(scenarioName);
    if ("NEW".equals(cur) || "CHANGED".equals(cur)) return; // keep strongest flag
    scenarioMap.put(scenarioName, status);
  }

  private static List<String> execAndRead(String... cmd) throws Exception {
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    List<String> out = new ArrayList<>();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
      String line;
      while ((line = r.readLine()) != null) out.add(line);
    }
    int code = p.waitFor();
    if (code != 0) throw new RuntimeException("Command failed: " + String.join(" ", cmd) + " (exit " + code + ")");
    return out;
  }

  private static List<String> execAndReadSafe(String... cmd) throws Exception {
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    List<String> out = new ArrayList<>();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
      String line;
      while ((line = r.readLine()) != null) out.add(line);
    }
    p.waitFor();
    return out;
  }

  private static String envOrDefault(String key, String def) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? def : v;
  }

  private static final class LineRange {
    final int start; // inclusive (1-based, header-inclusive)
    final int end;   // exclusive
    LineRange(int start, int end) { this.start = start; this.end = end; }
    boolean overlaps(int s, int e) { return this.start < e && s < this.end; }
  }

  private static final class DiffHunk {
    final int addStart; // 1-based
    final int addEnd;   // exclusive
    DiffHunk(int addStart, int addEnd) { this.addStart = addStart; this.addEnd = addEnd; }
  }
}
