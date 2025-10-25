package hooks;

import changes.FeatureScenarioChangeMap;
import io.cucumber.java.Before;

import java.util.Map;

public class Hooks {

  private static boolean done = false;

  @Before(order = 0)
  public void initLocalChangeMap() {
    if (done) return;
    done = true;

    String from = System.getenv("FROM_COMMIT");
    String to   = System.getenv("TO_COMMIT");
    if (from == null || from.isBlank()) from = "HEAD~1";
    if (to == null || to.isBlank()) to = "HEAD";

    try {
      String fromSha = run("git", "rev-parse", from).trim();
      String toSha   = run("git", "rev-parse", to).trim();
      System.out.println("Local diff FROM=" + from + " (" + fromSha + ") TO=" + to + " (" + toSha + ")");
    } catch (Exception ignore) {
      System.out.println("Note: unable to resolve SHAs; continuing with refs.");
    }

    Map<String, Map<String, String>> map = FeatureScenarioChangeMap.build(from, to);

    // Optional: print a compact report
    int features = map.size();
    int scenarios = map.values().stream().mapToInt(m -> m.size()).sum();
    long n = map.values().stream().flatMap(m -> m.values().stream()).filter("NEW"::equals).count();
    long c = map.values().stream().flatMap(m -> m.values().stream()).filter("CHANGED"::equals).count();
    System.out.println("Local change map ready. features=" + features + " scenarios=" + scenarios +
        " NEW=" + n + " CHANGED=" + c + " UNCHANGED=" + (scenarios - n - c));
  }

  private static String run(String... cmd) throws Exception {
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    try (java.io.InputStream is = p.getInputStream()) {
      return new String(is.readAllBytes());
    } finally {
      p.waitFor();
    }
  }
}
