package util;


import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitDiffParser {
    
    public static List<String> getChangedFeatureFiles(String fromCommit, String toCommit) {
        List<String> changedFiles = new ArrayList<>();
        
        try {
            String[] cmd = {
                "git", "diff", "--name-only",
                fromCommit, toCommit,
                "--", "src/test/java/features/"
            };
            
            Process process = Runtime.getRuntime().exec(cmd);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.endsWith(".feature")) {
                    changedFiles.add(line);
                }
            }
            
            process.waitFor();
            reader.close();
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to get changed files: " + e.getMessage(), e);
        }
        
        return changedFiles;
    }
    
    public static List<DiffChange> getDiffChanges(String filePath, String fromCommit, String toCommit) {
        List<DiffChange> changes = new ArrayList<>();
        
        try {
            String[] cmd = {
                "git", "diff", "-U0",
                fromCommit, toCommit,
                "--", filePath
            };
            
            Process process = Runtime.getRuntime().exec(cmd);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            
            int newLineNumber = 0;
            String line;
            
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("@@")) {
                    newLineNumber = parseNewLineStart(line);
                } else if (line.startsWith("+") && !line.startsWith("+++")) {
                    String content = line.substring(1);
                    changes.add(new DiffChange(newLineNumber, content, DiffType.ADDED));
                    newLineNumber++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    String content = line.substring(1);
                    changes.add(new DiffChange(newLineNumber, content, DiffType.DELETED));
                } else if (!line.startsWith("\\")) {
                    newLineNumber++;
                }
            }
            
            process.waitFor();
            reader.close();
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to get diff for " + filePath + ": " + e.getMessage(), e);
        }
        
        return changes;
    }
    
    private static int parseNewLineStart(String hunkHeader) {
        Pattern pattern = Pattern.compile("@@\\s+-\\d+(?:,\\d+)?\\s+\\+(\\d+)(?:,\\d+)?\\s+@@");
        Matcher matcher = pattern.matcher(hunkHeader);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }
    
    public static String getRemoteMainSha() {
        try {
            Process process = Runtime.getRuntime().exec(
                new String[]{"git", "rev-parse", "origin/main"}
            );
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            String sha = reader.readLine();
            process.waitFor();
            reader.close();
            return sha != null ? sha : "HEAD~1";
        } catch (Exception e) {
            return "HEAD~1";
        }
    }
    
    public static class DiffChange {
        private int lineNumber;
        private String content;
        private DiffType type;
        
        public DiffChange(int lineNumber, String content, DiffType type) {
            this.lineNumber = lineNumber;
            this.content = content;
            this.type = type;
        }
        
        public int getLineNumber() { return lineNumber; }
        public String getContent() { return content; }
        public DiffType getType() { return type; }
    }
    
    public enum DiffType {
        ADDED, DELETED
    }
}

