package model;

import java.util.HashMap;
import java.util.Map;

public class ScenarioChangeTracker {
    
    private Map<String, ChangeStatus> scenarioStatusMap = new HashMap<>();
    
    public enum ChangeStatus {
        NEW,        // First time seeing this scenario
        CHANGED,    // Scenario content modified
        UNCHANGED   // Scenario exists but no changes detected
    }
    
    public void markScenario(String scenarioKey, ChangeStatus status) {
        scenarioStatusMap.put(scenarioKey, status);
    }
    
    public ChangeStatus getStatus(String scenarioKey) {
        return scenarioStatusMap.getOrDefault(scenarioKey, ChangeStatus.NEW);
    }
    
    public boolean hasChanged(String scenarioKey) {
        ChangeStatus status = getStatus(scenarioKey);
        return status == ChangeStatus.CHANGED || status == ChangeStatus.NEW;
    }
    
    public void printSummary() {
        int newCount = 0, changedCount = 0, unchangedCount = 0;
        
        for (ChangeStatus status : scenarioStatusMap.values()) {
            switch (status) {
                case NEW: newCount++; break;
                case CHANGED: changedCount++; break;
                case UNCHANGED: unchangedCount++; break;
            }
        }
        
        System.out.println("\n========== CHANGE DETECTION SUMMARY ==========");
        System.out.println("NEW scenarios:       " + newCount);
        System.out.println("CHANGED scenarios:   " + changedCount);
        System.out.println("UNCHANGED scenarios: " + unchangedCount);
        System.out.println("TOTAL scenarios:     " + scenarioStatusMap.size());
        System.out.println("==============================================\n");
    }
}
