package changes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FeatureScenarioChangeMap {

  private static final String FEATURES_ROOT = "src/test/java/features";

  // Exposed result for hooks/steps to read
  private static Map<String, Map<String, String>> lastComputed = Collections.emptyMap();

  public static Map<String, Map<String, String>> latest() {
    return lastComputed;
  }

  public static Map<String, Map<String, String>> build(String fromRef, String toRef) {
    Objects.requireNonNull(fromRef, "fromRef is required");
    Objects.requireNonNull(toRef, "toRef is required");
    try {
      Map<String, List<String>> fileToLines = readAllFeatureFiles();
      Map<String, Map<String, LineRange>> fileScenarioRanges = new HashMap<>();
      Map<String, Set<String>> fileScenarioNames = new HashMap<>();

      for (Map.Entry<String, List<String>> e : fileToLines.entrySet()) {
        String featurePath = e.getKey();
        List<String> lines = e.getValue();
        Map<String, LineRange> ranges = computeScenarioRanges(lines);
        fileScenarioRanges.put(featurePath, ranges);
        fileScenarioNames.put(featurePath, ranges.keySet());
      }

      Set<String> changedFeaturePaths = listChangedFeatureFiles(fromRef, toRef);

      Map<String, Map<String, String>> result = new LinkedHashMap<>();
      for (String featurePath : fileScenarioNames.keySet()) {
        String featureName = Paths.get(featurePath).getFileName().toString();
        Map<String, String> scenarioMap = new LinkedHashMap<>();
        for (String scenario : fileScenarioNames.get(featurePath)) {
          scenarioMap.put(scenario, "UNCHANGED");
        }
        result.put(featureName, scenarioMap);
      }

      for (String featurePath : changedFeaturePaths) {
        List<DiffHunk> hunks = readDiffHunks(fromRef, toRef, featurePath);
        Map<String, LineRange> ranges = fileScenarioRanges.getOrDefault(featurePath, Collections.emptyMap());
        Set<String> currentScenarios = fileScenarioNames.getOrDefault(featurePath, Collections.emptySet());

        for (DiffHunk h : hunks) {
          for (Map.Entry<String, LineRange> ent : ranges.entrySet()) {
            if (ent.getValue().overlaps(h.addStart, h.addEnd)) {
              mark(result, featurePath, ent.getKey(), "CHANGED");
            }
          }
        }

        Set<String> previousScenarios = readScenarioNamesAtRef(fromRef, featurePath);
        for (String now : currentScenarios) {
          if (!previousScenarios.contains(now)) {
            mark(result, featurePath, now, "NEW");
          }
        }
      }

      lastComputed = result;
      return result;

    } catch (Exception ex) {
      throw new RuntimeException("Failed to build feature-scenario change map: " + ex.getMessage(), ex);
    }
  }

  private static void mark(Map<String, Map<String, String>> result, String featurePath, String scenarioName, String status) {
    String featureName = Paths.get(featurePath).getFileName().toString();
    Map<String, String> scenarioMap = result.computeIfAbsent(featureName, k -> new LinkedHashMap<>());
    String current = scenarioMap.get(scenarioName);
    if ("NEW".equals(current) || "CHANGED".equals(current)) return;
    scenarioMap.put(scenarioName, status);
  }

  private static Map<String, List<String>> readAllFeatureFiles() throws Exception {
    Map<String, List<String>> out = new LinkedHashMap<>();
    try (var stream = Files.walk(Paths.get(FEATURES_ROOT))) {
      for (Path p : (Iterable<Path>) stream::iterator) {
        if (Files.isRegularFile(p) && p.toString().endsWith(".feature")) {
          out.put(p.toString(), Files.readAllLines(p));
        }
      }
    }
    return out;
  }

  private static Map<String, LineRange> computeScenarioRanges(List<String> lines) {
    Map<String, LineRange> ranges = new LinkedHashMap<>();
    String current = null;
    int start = -1;
    for (int idx = 0; idx < lines.size(); idx++) {
      String t = lines.get(idx).trim();
      if (t.regionMatches(true, 0, "Scenario Outline:", 0, 17) || t.regionMatches(true, 0, "Scenario:", 0, 9)) {
        if (current != null && start >= 0) {
          ranges.put(current, new LineRange(start, idx));
        }
        String name = t.replaceFirst("(?i)Scenario\\s*(Outline)?:\\s*", "").trim();
        current = name;
        start = idx + 1;
      }
    }
    if (current != null && start >= 0) {
      ranges.put(current, new LineRange(start, lines.size() + 1));
    }
    return ranges;
  }

  private static Set<String> listChangedFeatureFiles(String fromRef, String toRef) throws Exception {
    List<String> names = execAndRead("git", "diff", "--name-only", fromRef, toRef, "--", FEATURES_ROOT);
    Set<String> out = new LinkedHashSet<>();
    for (String n : names) if (n.endsWith(".feature")) out.add(n);
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

  private static Set<String> readScenarioNamesAtRef(String ref, String filePath) throws Exception {
    List<String> content = execAndReadSafe("git", "show", ref + ":" + filePath);
    if (content.isEmpty()) return Collections.emptySet();
    return computeScenarioRanges(content).keySet();
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

  private static final class LineRange {
    final int start; // inclusive, 1-based
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
