package hooks;

import changes.FeatureScenarioChangeMap; // your existing builder class
import io.cucumber.java.Before;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

public class Hooks {

  private static boolean once = false;

  // Shared snapshot for other components to read
  private static Map<String, Map<String, String>> changeMap = Collections.emptyMap();

  public static Map<String, Map<String, String>> getChangeMap() {
    return changeMap;
  }

  @Before(order = 0)
  public void initAndPrintChangeMap() {
    if (once) return;
    once = true;

    String from = envOrDefault("FROM_COMMIT", "HEAD~1");
    String to   = envOrDefault("TO_COMMIT", "HEAD");

    // Build map once
    changeMap = FeatureScenarioChangeMap.build(from, to);

    // Sorted printing for stable output
    Map<String, Map<String, String>> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    sorted.putAll(changeMap);

    System.out.println("\n=== FEATURE → SCENARIO STATUS MAP ===");
    System.out.println("FROM: " + from);
    System.out.println("TO:   " + to);

    int featureCount = 0;
    int scenarioCount = 0;
    int newCount = 0;
    int changedCount = 0;
    int unchangedCount = 0;

    for (Map.Entry<String, Map<String, String>> featureEntry : sorted.entrySet()) {
      String featureName = featureEntry.getKey();

      // Sort scenarios for stable output
      Map<String, String> scenarios = new TreeMap<>(Comparator.comparing(String::toString, String.CASE_INSENSITIVE_ORDER));
      scenarios.putAll(featureEntry.getValue());

      featureCount++;
      for (Map.Entry<String, String> sc : scenarios.entrySet()) {
        scenarioCount++;
        String scenarioName = sc.getKey();
        String status = sc.getValue();

        switch (status) {
          case "NEW": newCount++; break;
          case "CHANGED": changedCount++; break;
          default: unchangedCount++; break;
        }

        System.out.println("Feature=" + featureName + " | Scenario=\"" + scenarioName + "\" | Status=" + status);
      }
    }

    System.out.println("\n=== SUMMARY ===");
    System.out.println("Features:   " + featureCount);
    System.out.println("Scenarios:  " + scenarioCount);
    System.out.println("NEW:        " + newCount);
    System.out.println("CHANGED:    " + changedCount);
    System.out.println("UNCHANGED:  " + unchangedCount);
    System.out.println("=============\n");
  }

  private static String envOrDefault(String key, String def) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? def : v;
  }
}
