package changes;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.DiffEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

public class FeatureScenarioChangeMap {

  private static final String FEATURES_ROOT = envOrDefault("FEATURES_ROOT", "src/test/java/features");
  private static final int HUNK_BUFFER_LINES = 1;

  private static Map<String, Map<String, String>> lastComputed = Collections.emptyMap();
  public static Map<String, Map<String, String>> latest() { return lastComputed; }

  public static Map<String, Map<String, String>> buildLocal() {
    String fromRef = envOrDefault("FROM_COMMIT", "HEAD~1");
    String toRef   = envOrDefault("TO_COMMIT", "HEAD");
    return build(fromRef, toRef);
  }

  public static Map<String, Map<String, String>> build(String fromRef, String toRef) {
    Objects.requireNonNull(fromRef, "fromRef required");
    Objects.requireNonNull(toRef, "toRef required");

    try (Repository repo = new FileRepositoryBuilder()
        .setMustExist(true)
        .findGitDir() // locate .git upward from CWD
        .build()) {

      ObjectId from = repo.resolve(fromRef);
      ObjectId to = repo.resolve(toRef);
      if (from == null || to == null) {
        throw new IllegalArgumentException("Cannot resolve refs: FROM=" + fromRef + " TO=" + toRef);
      }

      // 1) Read current working tree feature files
      Map<String, List<String>> fileToLines = readAllFeatureFiles();
      Map<String, Map<String, LineRange>> fileScenarioRanges = new HashMap<>();
      Map<String, Set<String>> fileScenarioNames = new HashMap<>();
      for (Map.Entry<String, List<String>> e : fileToLines.entrySet()) {
        var ranges = computeScenarioRangesHeaderInclusive(e.getValue());
        fileScenarioRanges.put(e.getKey(), ranges);
        fileScenarioNames.put(e.getKey(), ranges.keySet());
      }

      // 2) List diffs between from and to for features root
      List<DiffEntry> diffEntries = diffTree(repo, from, to, FEATURES_ROOT);

      // 3) Initialize result map to UNCHANGED
      Map<String, Map<String, String>> result = new LinkedHashMap<>();
      for (String featurePath : fileScenarioNames.keySet()) {
        String featureName = Paths.get(featurePath).getFileName().toString();
        Map<String, String> scenarioMap = new LinkedHashMap<>();
        for (String scenario : fileScenarioNames.get(featurePath)) {
          scenarioMap.put(scenario, "UNCHANGED");
        }
        result.put(featureName, scenarioMap);
      }

      // 4) Process changed features
      Set<String> changedPaths = new LinkedHashSet<>();
      for (DiffEntry de : diffEntries) {
        String path = normalize(de.getNewPath().equals(DiffEntry.DEV_NULL) ? de.getOldPath() : de.getNewPath());
        if (path.endsWith(".feature")) changedPaths.add(path);
      }

      // Build a name set from "from" tree for NEW detection
      Map<String, Set<String>> previousScenarioNamesByFile = previousScenarioNames(repo, from, changedPaths);

      try (DiffFormatter df = new DiffFormatter(new ByteArrayOutputStream())) {
        df.setRepository(repo);
        df.setDetectRenames(true);

        for (DiffEntry de : diffEntries) {
          String path = normalize(de.getNewPath().equals(DiffEntry.DEV_NULL) ? de.getOldPath() : de.getNewPath());
          if (!path.endsWith(".feature")) continue;

          EditList edits = df.toFileHeader(de).toEditList();
          List<DiffHunk> hunks = toBufferedHunks(edits, HUNK_BUFFER_LINES);

          Map<String, LineRange> ranges = fileScenarioRanges.getOrDefault(path, Collections.emptyMap());
          Set<String> currentScenarios = fileScenarioNames.getOrDefault(path, Collections.emptySet());

          // CHANGED: any overlap of buffered hunks with header-inclusive scenario ranges
          for (DiffHunk h : hunks) {
            for (Map.Entry<String, LineRange> ent : ranges.entrySet()) {
              if (ent.getValue().overlaps(h.addStart, h.addEnd)) {
                mark(result, path, ent.getKey(), "CHANGED");
              }
            }
          }

          // NEW: present now, absent previously
          Set<String> prev = previousScenarioNamesByFile.getOrDefault(path, Collections.emptySet());
          for (String now : currentScenarios) {
            if (!prev.contains(now)) {
              mark(result, path, now, "NEW");
            }
          }
        }
      }

      lastComputed = result;
      return result;

    } catch (IOException ex) {
      throw new RuntimeException("Failed to build change map via JGit: " + ex.getMessage(), ex);
    }
  }

  // ---------- JGit helpers ----------

  private static List<DiffEntry> diffTree(Repository repo, ObjectId from, ObjectId to, String pathFilter) throws IOException {
    try (RevWalk rw = new RevWalk(repo)) {
      RevCommit fromCommit = rw.parseCommit(from);
      RevCommit toCommit   = rw.parseCommit(to);
      AbstractTreeIterator oldTree = treeIter(repo, fromCommit.getTree().getId());
      AbstractTreeIterator newTree = treeIter(repo, toCommit.getTree().getId());

      try (DiffFormatter df = new DiffFormatter(new ByteArrayOutputStream())) {
        df.setRepository(repo);
        df.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilterGroup.createFromStrings(Collections.singleton(pathFilter)));
        df.setDetectRenames(true);
        return df.scan(oldTree, newTree);
      }
    }
  }

  private static AbstractTreeIterator treeIter(Repository repo, ObjectId treeId) throws IOException {
    CanonicalTreeParser p = new CanonicalTreeParser();
    try (var reader = repo.newObjectReader()) {
      p.reset(reader, treeId);
    }
    return p;
  }

  // ---------- Parsing helpers ----------

  private static Map<String, List<String>> readAllFeatureFiles() throws IOException {
    Map<String, List<String>> out = new LinkedHashMap<>();
    Path root = Paths.get(FEATURES_ROOT);
    if (!Files.exists(root)) return out;
    try (var stream = Files.walk(root)) {
      for (Path p : (Iterable<Path>) stream::iterator) {
        if (Files.isRegularFile(p) && p.toString().endsWith(".feature")) {
          out.put(normalize(p.toString()), Files.readAllLines(p));
        }
      }
    }
    return out;
  }

  private static Map<String, LineRange> computeScenarioRangesHeaderInclusive(List<String> lines) {
    Map<String, LineRange> ranges = new LinkedHashMap<>();
    String current = null;
    int start = -1; // 1-based inclusive (header included)
    for (int i = 0; i < lines.size(); i++) {
      String t = lines.get(i).trim();
      if (startsWithScenario(t)) {
        if (current != null && start >= 1) {
          ranges.put(current, new LineRange(start, i + 1)); // end exclusive
        }
        String name = t.replaceFirst("(?i)Scenario\\s*(Outline)?:\\s*", "").trim();
        current = name;
        start = i + 1;
      }
    }
    if (current != null && start >= 1) {
      ranges.put(current, new LineRange(start, lines.size() + 1));
    }
    return ranges;
  }

  private static boolean startsWithScenario(String t) {
    return t.regionMatches(true, 0, "Scenario Outline:", 0, 17) || t.regionMatches(true, 0, "Scenario:", 0, 9);
  }

  private static List<DiffHunk> toBufferedHunks(EditList edits, int buffer) {
    List<DiffHunk> out = new ArrayList<>();
    for (Edit e : edits) {
      // new file line range is [beginB, endB), convert to 1-based
      int s = e.getBeginB() + 1;
      int eExclusive = e.getEndB() + 1;
      if (buffer > 0) {
        s = Math.max(1, s - buffer);
        eExclusive = eExclusive + buffer;
      }
      out.add(new DiffHunk(s, eExclusive));
    }
    return out;
  }

  private static Map<String, Set<String>> previousScenarioNames(Repository repo, ObjectId from, Set<String> featurePaths) throws IOException {
    Map<String, Set<String>> out = new HashMap<>();
    for (String path : featurePaths) {
      String repoPath = repoRelative(path);
      if (repoPath == null) { out.put(path, Collections.emptySet()); continue; }
      byte[] bytes = readBlobAt(repo, from, repoPath);
      if (bytes == null) { out.put(path, Collections.emptySet()); continue; }
      List<String> lines = Arrays.asList(new String(bytes).split("\\R", -1));
      out.put(path, computeScenarioRangesHeaderInclusive(lines).keySet());
    }
    return out;
  }

  private static String repoRelative(String path) {
    // JGit expects repo-relative forward-slash paths
    String norm = normalize(path);
    // If your project root equals the git repo root, returning norm is fine.
    // If FEATURES_ROOT is nested, norm should already be repo-relative from working dir.
    return norm;
  }

  private static byte[] readBlobAt(Repository repo, ObjectId commitId, String path) throws IOException {
	  try (RevWalk rw = new RevWalk(repo)) {
	    RevCommit commit = rw.parseCommit(commitId);
	    var tree = commit.getTree();
	    try (var treeWalk = new org.eclipse.jgit.treewalk.TreeWalk(repo)) {
	      treeWalk.addTree(tree);
	      treeWalk.setRecursive(true);
	      treeWalk.setFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(path));
	      if (!treeWalk.next()) return null;
	      var objectId = treeWalk.getObjectId(0);

	      // ObjectLoader is not AutoCloseable; do NOT use try-with-resources here
	      org.eclipse.jgit.lib.ObjectLoader loader = repo.open(objectId, Constants.OBJ_BLOB);
	      return loader.getBytes();
	    }
	  }
	}


  private static void mark(Map<String, Map<String, String>> result, String featurePath, String scenarioName, String status) {
    String featureName = Paths.get(featurePath).getFileName().toString();
    Map<String, String> scenarioMap = result.computeIfAbsent(featureName, k -> new LinkedHashMap<>());
    String cur = scenarioMap.get(scenarioName);
    if ("NEW".equals(cur) || "CHANGED".equals(cur)) return;
    scenarioMap.put(scenarioName, status);
  }

  private static String normalize(String p) {
    return p == null ? null : p.replace('\\', '/');
  }

  private static String envOrDefault(String key, String def) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? def : v;
  }

  // Small types
  private static final class LineRange {
    final int start; // inclusive 1-based (header-inclusive)
    final int end;   // exclusive
    LineRange(int s, int e) { this.start = s; this.end = e; }
    boolean overlaps(int s, int e) { return this.start < e && s < this.end; }
  }

  private static final class DiffHunk {
    final int addStart; // 1-based
    final int addEnd;   // exclusive
    DiffHunk(int s, int e) { this.addStart = s; this.addEnd = e; }
  }
}
