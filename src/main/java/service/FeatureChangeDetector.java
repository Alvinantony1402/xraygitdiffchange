package service;

import model.ScenarioChangeTracker;
import util.GitDiffParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class FeatureChangeDetector {
    
    public static ScenarioChangeTracker detectChanges(String fromCommit, String toCommit) {
        System.out.println("Detecting scenario changes from " + fromCommit + " to " + toCommit);
        
        ScenarioChangeTracker tracker = new ScenarioChangeTracker();
        
        try {
            // Get all changed feature files
            List<String> changedFiles = GitDiffParser.getChangedFeatureFiles(fromCommit, toCommit);
            System.out.println("Changed feature files: " + changedFiles.size());
            
            // Analyze each changed file
            for (String featureFile : changedFiles) {
                System.out.println("\nAnalyzing: " + featureFile);
                analyzeChangedFeature(featureFile, fromCommit, toCommit, tracker);
            }
            
        } catch (Exception e) {
            System.err.println("Error detecting changes: " + e.getMessage());
            e.printStackTrace();
        }
        
        tracker.printSummary();
        return tracker;
    }
    
    private static void analyzeChangedFeature(String featureFile, String fromCommit, 
                                              String toCommit, ScenarioChangeTracker tracker) throws IOException {
        
        // Get diff changes for this file
        List<GitDiffParser.DiffChange> diffChanges = GitDiffParser.getDiffChanges(featureFile, fromCommit, toCommit);
        
        // Get scenario line ranges
        Map<String, LineRange> scenarioRanges = getScenarioLineRanges(featureFile);
        
        // Match diff changes to scenarios
        Set<String> affectedScenarios = new HashSet<>();
        for (GitDiffParser.DiffChange change : diffChanges) {
            for (Map.Entry<String, LineRange> entry : scenarioRanges.entrySet()) {
                if (entry.getValue().contains(change.getLineNumber())) {
                    affectedScenarios.add(entry.getKey());
                }
            }
        }
        
        // Mark scenarios as CHANGED
        String featureName = Paths.get(featureFile).getFileName().toString();
        for (String scenarioName : affectedScenarios) {
            String scenarioKey = buildScenarioKey(featureName, scenarioName);
            tracker.markScenario(scenarioKey, ScenarioChangeTracker.ChangeStatus.CHANGED);
            System.out.println("  → CHANGED: " + scenarioName);
        }
        
        // Mark other scenarios in this file as UNCHANGED
        for (String scenarioName : scenarioRanges.keySet()) {
            if (!affectedScenarios.contains(scenarioName)) {
                String scenarioKey = buildScenarioKey(featureName, scenarioName);
                if (tracker.getStatus(scenarioKey) == ScenarioChangeTracker.ChangeStatus.NEW) {
                    tracker.markScenario(scenarioKey, ScenarioChangeTracker.ChangeStatus.UNCHANGED);
                }
            }
        }
    }
    
    private static Map<String, LineRange> getScenarioLineRanges(String featurePath) throws IOException {
        Map<String, LineRange> ranges = new HashMap<>();
        List<String> lines = Files.readAllLines(Paths.get(featurePath));
        
        String currentScenario = null;
        int scenarioStart = 0;
        
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            
            if (line.startsWith("Scenario:") || line.startsWith("Scenario Outline:")) {
                // Save previous scenario
                if (currentScenario != null) {
                    ranges.put(currentScenario, new LineRange(scenarioStart, i - 1));
                }
                
                // Start new scenario
                currentScenario = line.replaceFirst("Scenario\\s*(Outline)?:\\s*", "").trim();
                scenarioStart = i + 1;
            }
        }
        
        // Save last scenario
        if (currentScenario != null) {
            ranges.put(currentScenario, new LineRange(scenarioStart, lines.size()));
        }
        
        return ranges;
    }
    
    private static String buildScenarioKey(String featureName, String scenarioName) {
        return "feature:" + featureName + "::scenario:" + scenarioName.trim().replaceAll("\\s+", "-");
    }
    
    private static class LineRange {
        int start;
        int end;
        
        LineRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
        
        boolean contains(int lineNumber) {
            return lineNumber >= start && lineNumber <= end;
        }
    }
}
