package org.folio.factory.devfactory.inbox;

/**
 * Git reference-name rules (a subset of {@code git check-ref-format} that the
 * inbox contract cares about) and raw commit-id detection.
 */
public final class GitRefs {

    private GitRefs() {
    }

    public static boolean isValidBranchName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (name.equals("@")) {
            return false;
        }
        if (name.startsWith(".") || name.startsWith("-")) {
            return false;
        }
        if (name.endsWith("/") || name.endsWith(".")) {
            return false;
        }
        if (name.contains("..") || name.contains("//")) {
            return false;
        }
        if (name.contains("@{")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
            if (c == ' ' || c == '~' || c == '^' || c == ':' || c == '?'
                    || c == '[' || c == '\\') {
                return false;
            }
        }
        for (String component : name.split("/", -1)) {
            if (component.isEmpty()) {
                return false;
            }
            if (component.startsWith(".")) {
                return false;
            }
            if (component.endsWith(".lock")) {
                return false;
            }
        }
        return true;
    }

    public static boolean isRawCommitId(String s) {
        if (s == null) {
            return false;
        }
        return s.matches("^[0-9a-fA-F]{40}$") || s.matches("^[0-9a-fA-F]{64}$");
    }
}
