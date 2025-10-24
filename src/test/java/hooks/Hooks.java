package hooks;

import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import stepdefinitions.utils;

public class Hooks {

    private static boolean changeTrackingInitialized = false;
    private static boolean testsCreated = false;
    private final utils utilHelper = new utils();

    @Before(order = 0) // Initialize change tracking first (runs once for entire suite)
    public void initializeChangeDetection() {
        if (changeTrackingInitialized) return;
        changeTrackingInitialized = true;

        String fromCommit = System.getenv("FROM_COMMIT");
        String toCommit = System.getenv("TO_COMMIT");

        if (fromCommit == null || fromCommit.isEmpty()) {
            System.out.println("FROM_COMMIT not set, detecting from origin/main...");
            fromCommit = util.GitDiffParser.getRemoteMainSha();
        }
        if (toCommit == null || toCommit.isEmpty()) {
            toCommit = "HEAD";
        }

        System.out.println("\n=== INITIALIZING CHANGE DETECTION ===");
        System.out.println("FROM: " + fromCommit);
        System.out.println("TO:   " + toCommit);
        System.out.println("======================================\n");

        utils.initializeChangeTracking(fromCommit, toCommit);
    }

    @Before(order = 1) // Create/update tests after change detection (runs once)
    public void prepareXrayTestOnce(Scenario scenario) {
        if (testsCreated) return;
        testsCreated = true;

        System.out.println("\n=== PREPARING XRAY TESTS ===");
        
        utilHelper.authJira();
        utilHelper.authXray();
        
        // Use change-detection enabled method
        utilHelper.createOrReuseTestWithChangeDetection("XRAYL", scenario);
        
        System.out.println("=== XRAY TESTS READY ===\n");
    }
}
