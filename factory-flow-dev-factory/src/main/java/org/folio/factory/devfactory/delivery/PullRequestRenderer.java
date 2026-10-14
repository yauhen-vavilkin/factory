package org.folio.factory.devfactory.delivery;

import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import org.folio.factory.devfactory.verification.VerificationReceipt;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Renders untrusted descriptions separately from Factory's independent verification evidence. */
public final class PullRequestRenderer {
    private static final Pattern DIFF_PATH = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern QUOTED_DIFF_PATH =
            Pattern.compile("^diff --git \"a/(.+)\" \"b/(.+)\"$");
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern RUNTIME_CHECKS = Pattern.compile("(?i)\\bChecks passed:");
    private static final Pattern TOKEN =
            Pattern.compile(
                    "\\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,})\\b");

    public String render(
            String summary,
            Candidate candidate,
            VerificationReceipt verification,
            DeliveryReceipt delivery,
            JsonNode request,
            JsonNode outcome) {
        var body = new StringBuilder("## Summary\n\n").append(prose(summary, 500)).append("\n\n");
        appendRuntimeSummary(body, outcome.path("summary").asString(""));
        int decisions = 0;
        for (var decision : request.path("confirmedDecisions")) {
            if (decisions++ >= 5) break;
            if (decisions == 1) body.append("## Confirmed decisions\n\n");
            body.append("- ")
                    .append(prose(decision.path("question").asString(""), 300))
                    .append(" — ")
                    .append(prose(decision.path("answer").asString(""), 500))
                    .append("\n");
        }
        if (decisions > 0) body.append("\n");
        List<String> files =
                candidate
                        .patch()
                        .lines()
                        .filter(line -> line.startsWith("diff --git "))
                        .map(PullRequestRenderer::changedPath)
                        .toList();
        body.append("## Changed files\n\n");
        files.stream()
                .limit(20)
                .forEach(path -> body.append("- `").append(code(path, 240)).append("`\n"));
        if (files.size() > 20) body.append("- Additional files are visible in the PR diff.\n");
        body.append("\n## Verification\n\n**")
                .append(prose(verification.result(), 40))
                .append("** · ")
                .append(count(verification.testCount()))
                .append(" tests · ")
                .append(count(verification.failureCount()))
                .append(" failures · ")
                .append(count(verification.errorCount()))
                .append(" errors\n\n")
                .append("- Plan: `")
                .append(code(verification.planId(), 200))
                .append("`\n")
                .append("- Command: `")
                .append(code(String.join(" ", verification.argv()), 1200))
                .append("`\n")
                .append("- Image: `")
                .append(code(verification.image(), 300))
                .append("`\n\n")
                .append("<details>\n<summary>Factory verification details</summary>\n\n")
                .append("- Surefire reports: ")
                .append(verification.surefireReportCount())
                .append("\n")
                .append("- Failsafe reports: ")
                .append(verification.failsafeReportCount())
                .append("\n")
                .append("- Required suites: ")
                .append(
                        verification.requiredReports().isEmpty()
                                ? "No named suites configured; aggregate executed-test evidence"
                                        + " only."
                                : prose(String.join(", ", verification.requiredReports()), 1600))
                .append("\n")
                .append(
                        "\n"
                            + "Factory independently verified the exact frozen candidate using the"
                            + " configured verification plan.\n")
                .append("</details>\n\n")
                .append("<details>\n<summary>Candidate and delivery details</summary>\n\n")
                .append("- Execution: `")
                .append(code(verification.executionId(), 200))
                .append("`\n")
                .append("- Destination: `")
                .append(code(delivery.repository(), 300))
                .append("` / `")
                .append(code(delivery.branch(), 300))
                .append("`\n");
        return body.append("- Base: `")
                .append(code(candidate.baseSha(), 100))
                .append("`\n")
                .append("- Tree: `")
                .append(code(candidate.treeSha(), 100))
                .append("`\n")
                .append("- Patch SHA-256: `")
                .append(code(candidate.patchSha256(), 100))
                .append("`\n")
                .append("- Delivered commit: `")
                .append(code(delivery.commitSha(), 100))
                .append("`\n")
                .append("</details>")
                .toString();
    }

    private static void appendRuntimeSummary(StringBuilder body, String summary) {
        if (summary == null || summary.isBlank()) return;
        String descriptive = summary.substring(0, Math.min(summary.length(), 4000));
        var checks = RUNTIME_CHECKS.matcher(descriptive);
        if (checks.find()) descriptive = descriptive.substring(0, checks.start());
        List<String> lines = descriptive.lines().toList();
        boolean changes = false;
        var bullets = new ArrayList<String>();
        var fallback = new StringBuilder();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.equalsIgnoreCase("Changes:")) {
                changes = true;
                continue;
            }
            if (changes && line.startsWith("- ")) {
                String bullet = prose(line.substring(2), 240);
                if (!bullet.isBlank() && bullets.size() < 20) bullets.add(bullet);
            } else if (!changes && !line.isBlank()) {
                if (!fallback.isEmpty()) fallback.append(' ');
                fallback.append(line);
            }
        }
        if (!bullets.isEmpty()) {
            body.append("## Changes\n\n");
            bullets.forEach(bullet -> body.append("- ").append(bullet).append("\n"));
            body.append("\n");
        } else {
            String description = prose(fallback.toString(), 2000);
            if (!description.isBlank()) body.append(description).append("\n\n");
        }
    }

    private static String changedPath(String header) {
        var matcher = DIFF_PATH.matcher(header);
        if (!matcher.matches()) matcher = QUOTED_DIFF_PATH.matcher(header);
        return matcher.matches() ? matcher.group(2) : "Path visible in PR diff";
    }

    private static String count(Integer value) {
        return value == null ? "unknown" : value.toString();
    }

    /** Dynamic inline code cannot close Factory's code span or expose credentials. */
    private static String code(String value, int limit) {
        String clean =
                redact(value)
                        .replaceAll("[\\p{Cntrl}\\s]+", " ")
                        .replace("`", "′")
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .strip();
        return clean.length() <= limit ? clean : clean.substring(0, limit) + "…";
    }

    private static String redact(String value) {
        if (value == null) return "";
        String clean =
                value.replaceAll("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9+/_.=-]+", "$1 [REDACTED]");
        clean = MavenBaselineOutput.sanitize(clean);
        return TOKEN.matcher(clean).replaceAll("[REDACTED]");
    }

    /** Descriptive input cannot introduce Markdown structure, HTML, links or mentions. */
    private static String prose(String value, int limit) {
        String clean =
                URL.matcher(redact(value))
                        .replaceAll("[link omitted]")
                        .replace("`", "")
                        .replaceAll("[\\p{Cntrl}\\s]+", " ")
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("#", "&#35;")
                        .replace("@", "＠")
                        .replace("[", "&#91;")
                        .replace("]", "&#93;")
                        .replace("*", "&#42;")
                        .replace("_", "&#95;")
                        .replace("-", "&#45;")
                        .replace("+", "&#43;")
                        .replace("~", "&#126;")
                        .replace("!", "&#33;")
                        .replace("|", "&#124;")
                        .replace("\\", "&#92;")
                        .strip();
        clean =
                clean.replaceFirst("^([0-9]{1,9})\\.(?=\\s)", "$1&#46;")
                        .replaceFirst("^([0-9]{1,9})\\)(?=\\s)", "$1&#41;");
        return clean.length() <= limit ? clean : clean.substring(0, limit) + "…";
    }
}
