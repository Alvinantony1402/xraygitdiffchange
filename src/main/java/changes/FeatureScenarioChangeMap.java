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
        .findGitDir()
        .build()) {

      ObjectId from = repo.resolve(fromRef);
      ObjectId to = repo.resolve(toRef);
      if (from == null || to == null) {
        throw new IllegalArgumentException("Cannot resolve refs: FROM=" + fromRef + " TO=" + toRef);
      }

      final String repoRoot = repo.getWorkTree().getAbsolutePath().replace('\\', '/') + "/";

      // 1) Parse current .feature files → scenario ranges/names
      Map<String, List<String>> fileToLines = readAllFeatureFiles();
      Map<String, Map<String, LineRange>> fileScenarioRanges = new HashMap<>();
      Map<String, Set<String>> fileScenarioNames = new HashMap<>();
      for (Map.Entry<String, List<String>> e : fileToLines.entrySet()) {
        var ranges = computeScenarioRangesHeaderInclusive(e.getValue());
        fileScenarioRanges.put(e.getKey(), ranges);
        fileScenarioNames.put(e.getKey(), ranges.keySet());
      }

      // 2) Diff entries for CHANGED and brand-new files
      List<DiffEntry> diffEntries = diffTree(repo, from, to, repoRoot, FEATURES_ROOT);

      Set<String> addedFeaturePaths = new HashSet<>();
      for (DiffEntry de : diffEntries) {
        if (de.getChangeType() == DiffEntry.ChangeType.ADD) {
          String path = pathFromDiff(de);
          if (path.endsWith(".feature")) {
            String filename = Paths.get(path).getFileName().toString();
            for (String k : fileScenarioNames.keySet()) {
              if (Paths.get(k).getFileName().toString().equalsIgnoreCase(filename)) {
                addedFeaturePaths.add(k);
                break;
              }
            }
          }
        }
      }

      // 3) Initialize all current scenarios to UNCHANGED
      Map<String, Map<String, String>> result = new LinkedHashMap<>();
      for (String featurePath : fileScenarioNames.keySet()) {
        String featureName = Paths.get(featurePath).getFileName().toString();
        Map<String, String> scenarioMap = new LinkedHashMap<>();
        for (String scenario : fileScenarioNames.get(featurePath)) {
          scenarioMap.put(scenario, "UNCHANGED");
        }
        result.put(featureName, scenarioMap);
      }

      // 4) Previous scenario-name sets for ALL current files
      Map<String, Set<String>> previousScenarioNamesByFile = previousScenarioNamesForAll(
          repo, from, repoRoot, fileScenarioNames.keySet()
      );

      // 5) Brand-new feature files → CHANGED for all scenarios (policy)
      for (String addedPath : addedFeaturePaths) {
        Set<String> currentScenarios = fileScenarioNames.getOrDefault(addedPath, Collections.emptySet());
        for (String now : currentScenarios) {
          mark(result, addedPath, now, "NEW");
        }
      }

      // 6) CHANGED via hunk overlap (edits within ranges)
      try (DiffFormatter df = new DiffFormatter(new ByteArrayOutputStream())) {
        df.setRepository(repo);
        df.setDetectRenames(true);

        for (DiffEntry de : diffEntries) {
          String path = normalize(pathFromDiff(de));
          if (!path.endsWith(".feature")) continue;

          String currentKey = resolveCurrentFeatureKey(fileScenarioNames.keySet(), path);
          if (currentKey == null) continue;

          EditList edits = df.toFileHeader(de).toEditList();
          List<DiffHunk> hunks = toBufferedHunks(edits, HUNK_BUFFER_LINES);

          Map<String, LineRange> ranges = fileScenarioRanges.getOrDefault(currentKey, Collections.emptyMap());
          for (DiffHunk h : hunks) {
            for (Map.Entry<String, LineRange> ent : ranges.entrySet()) {
              if (ent.getValue().overlaps(h.addStart, h.addEnd)) {
                mark(result, currentKey, ent.getKey(), "CHANGED");
              }
            }
          }
        }
      }

      // 7) NEW in existing files: current − previous
      for (String currentPath : fileScenarioNames.keySet()) {
        if (addedFeaturePaths.contains(currentPath)) {
          continue; // brand-new file kept as CHANGED-only
        }
        Set<String> prevNames = previousScenarioNamesByFile.get(currentPath);
        Set<String> currentNames = fileScenarioNames.getOrDefault(currentPath, Collections.emptySet());

        // Debug (optional)
        // System.out.println("Previous names: " + prevNames);
        // System.out.println("Current names: " + currentNames);

        if (prevNames == null) {
          // Defensive: treat as CHANGED to avoid mislabeling as NEW
          for (String now : currentNames) {
            mark(result, currentPath, now, "CHANGED");
          }
        } else {
          for (String now : currentNames) {
            if (!prevNames.contains(now)) {
              // Scenario newly added to an existing file → NEW
              mark(result, currentPath, now, "NEW");
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
  private static List<DiffEntry> diffTree(Repository repo, ObjectId from, ObjectId to, String repoRoot, String pathFilter) throws IOException {
    try (RevWalk rw = new RevWalk(repo)) {
      RevCommit fromCommit = rw.parseCommit(from);
      RevCommit toCommit   = rw.parseCommit(to);
      AbstractTreeIterator oldTree = treeIter(repo, fromCommit.getTree().getId());
      AbstractTreeIterator newTree = treeIter(repo, toCommit.getTree().getId());

      try (DiffFormatter df = new DiffFormatter(new ByteArrayOutputStream())) {
        df.setRepository(repo);
        df.setDetectRenames(true);
        String repoRelFilter = repoRelative(pathFilter, repoRoot);
        df.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(repoRelFilter));
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

  private static String pathFromDiff(DiffEntry de) {
    String raw = de.getNewPath().equals(DiffEntry.DEV_NULL) ? de.getOldPath() : de.getNewPath();
    return normalize(raw);
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
    int start = -1; // 1-based inclusive
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

  private static Map<String, Set<String>> previousScenarioNamesForAll(
      Repository repo, ObjectId from, String repoRoot, Set<String> currentFeaturePaths) throws IOException {

    Map<String, Set<String>> out = new HashMap<>();
    for (String currentPath : currentFeaturePaths) {
      String repoPath = repoRelative(currentPath, repoRoot);
      if (repoPath == null) { out.put(currentPath, Collections.emptySet()); continue; }
      byte[] bytes = readBlobAt(repo, from, repoPath);
      if (bytes == null) {
        out.put(currentPath, null);
        continue;
      }
      List<String> lines = Arrays.asList(new String(bytes).split("\\R", -1));
      out.put(currentPath, computeScenarioRangesHeaderInclusive(lines).keySet());
    }
    return out;
  }

  private static byte[] readBlobAt(Repository repo, ObjectId commitId, String repoRelativePath) throws IOException {
    try (RevWalk rw = new RevWalk(repo)) {
      RevCommit commit = rw.parseCommit(commitId);
      var tree = commit.getTree();
      try (var treeWalk = new org.eclipse.jgit.treewalk.TreeWalk(repo)) {
        treeWalk.addTree(tree);
        treeWalk.setRecursive(true);
        treeWalk.setFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(repoRelativePath));
        if (!treeWalk.next()) return null;
        var objectId = treeWalk.getObjectId(0);
        org.eclipse.jgit.lib.ObjectLoader loader = repo.open(objectId, Constants.OBJ_BLOB);
        return loader.getBytes();
      }
    }
  }

  private static String resolveCurrentFeatureKey(Set<String> currentKeys, String diffRepoRelPath) {
    String diffFileName = Paths.get(diffRepoRelPath).getFileName().toString();
    for (String k : currentKeys) {
      if (Paths.get(k).getFileName().toString().equalsIgnoreCase(diffFileName)) {
        return k;
      }
    }
    for (String k : currentKeys) {
      if (normalize(k).endsWith(normalize(diffRepoRelPath))) return k;
    }
    return null;
  }

  private static void mark(Map<String, Map<String, String>> result, String featurePath, String scenarioName, String status) {
    String featureName = Paths.get(featurePath).getFileName().toString();
    Map<String, String> scenarioMap = result.computeIfAbsent(featureName, k -> new LinkedHashMap<>());
    String cur = scenarioMap.get(scenarioName);

    if (cur == null) {
      scenarioMap.put(scenarioName, status);
      return;
    }

    // Precedence rules:
    // - For existing files, NEW should not be downgraded by later CHANGED.
    // - If CHANGED exists and NEW is coming (e.g., set-diff after hunk), prefer NEW.
    if ("NEW".equals(cur)) {
      return; // keep NEW
    }
    if ("CHANGED".equals(cur) && "NEW".equals(status)) {
      scenarioMap.put(scenarioName, "NEW");
      return;
    }
    if (!"NEW".equals(cur)) {
      scenarioMap.put(scenarioName, status); // write CHANGED over UNCHANGED, etc.
    }
  }

  private static String normalize(String p) {
    return p == null ? null : p.replace('\\', '/');
  }

  private static String repoRelative(String absOrRelPath, String repoRoot) {
    if (absOrRelPath == null) return null;
    String norm = normalize(absOrRelPath);
    if (norm.startsWith(repoRoot)) return norm.substring(repoRoot.length());
    return norm;
  }

  private static String envOrDefault(String key, String def) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? def : v;
  }

  private static final class LineRange {
    final int start; // inclusive 1-based
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
