package hooks;

import changes.FeatureScenarioChangeMap; // your detector class (CLI or JGit-backed)
import io.cucumber.java.Before;

import java.util.Collections;
import java.util.Map;

public class Hooks {
  private static boolean once = false;

  // Exposed immutable snapshot for other classes to read
  private static Map<String, Map<String, String>> changeMap = Collections.emptyMap();

  // Accessor for other code: feature -> (scenario -> flag)
  public static Map<String, Map<String, String>> getChangeMap() {
    return changeMap;
  }

  // Optional convenience: get flag by feature file name and scenario name
  public static String getScenarioFlag(String featureFileName, String scenarioName) {
    Map<String, String> scenarios = changeMap.get(featureFileName);
    if (scenarios == null) return "UNCHANGED";
    return scenarios.getOrDefault(scenarioName, "UNCHANGED");
  }

  @Before(order = 0)
  public void initMap() {
    if (once) return;
    once = true;

    // Local-friendly defaults; override via env if needed
    String from = envOrDefault("FROM_COMMIT", "HEAD~1");
    String to   = envOrDefault("TO_COMMIT", "HEAD");

    // Build once and store
    changeMap = FeatureScenarioChangeMap.build(from, to);

    // Summary log
    int features = changeMap.size();
    int scenarios = changeMap.values().stream().mapToInt(m -> m.size()).sum();
    long n = changeMap.values().stream().flatMap(m -> m.values().stream()).filter("NEW"::equals).count();
    long c = changeMap.values().stream().flatMap(m -> m.values().stream()).filter("CHANGED"::equals).count();
    System.out.println("Change map ready. features=" + features + " scenarios=" + scenarios +
        " NEW=" + n + " CHANGED=" + c + " UNCHANGED=" + (scenarios - n - c));
  }

  private static String envOrDefault(String key, String def) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? def : v;
  }
}
