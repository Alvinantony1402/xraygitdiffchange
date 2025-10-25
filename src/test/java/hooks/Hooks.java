package hooks;

import changes.FeatureScenarioChangeMap;
import io.cucumber.java.Before;

public class Hooks {

  private static boolean initialized = false;

  @Before(order = 0)
  public void buildChangeMapOnce() {
    if (initialized) return;
    initialized = true;

    // Ensure your local repo has latest remote refs if you rely on origin/main
    // Run: git fetch origin (you can do this outside or here with ProcessBuilder)

    String from = System.getenv("FROM_COMMIT");
    String to = System.getenv("TO_COMMIT");

    if (from == null || from.isBlank()) {
      from = "origin/main";
    }
    if (to == null || to.isBlank()) {
      to = "HEAD";
    }

    System.out.println("Building feature-scenario change map:");
    System.out.println("  FROM: " + from);
    System.out.println("  TO:   " + to);

    var map = FeatureScenarioChangeMap.build(from, to);

    // Optional: quick summary printout
    int features = map.size();
    int scenarios = map.values().stream().mapToInt(m -> m.size()).sum();
    long changed = map.values().stream().flatMap(m -> m.values().stream()).filter("CHANGED"::equals).count();
    long added = map.values().stream().flatMap(m -> m.values().stream()).filter("NEW"::equals).count();

    System.out.println("Map ready. Features=" + features + ", Scenarios=" + scenarios +
        ", NEW=" + added + ", CHANGED=" + changed + ", UNCHANGED=" + (scenarios - added - changed));
  }
}
