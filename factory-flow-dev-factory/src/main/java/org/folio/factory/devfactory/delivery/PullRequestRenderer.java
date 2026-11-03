package org.folio.factory.devfactory.delivery;

import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import org.folio.factory.devfactory.verification.VerificationReceipt;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders untrusted descriptions separately from Factory's independent verification evidence. */
public final class PullRequestRenderer {
    private static final Pattern DIFF_PATH = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern QUOTED_DIFF_PATH =
            Pattern.compile("^diff --git \"a/(.+)\" \"b/(.+)\"$");
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern RUNTIME_CHECKS = Pattern.compile("(?i)\\bChecks passed:");
    private static final String CHANGES = "Changes";
    private static final String CRITERIA = "Acceptance criteria";
    private static final String ASSUMPTIONS = "Assumptions";
    private static final String COULD_NOT_READ = "Could not read";
    private static final List<String> REPORT_SECTIONS = List.of(CHANGES, CRITERIA, ASSUMPTIONS, COULD_NOT_READ);
    private static final Pattern REPORT_HEADING = Pattern.compile(
            "(?i)##\\s+(changes|acceptance\\s+criteria|assumptions|could\\s+not\\s+read)\\s*:?");
    private static final Pattern NOT_MET = Pattern.compile("(?i)\\b(?:not|partially|partly)\\s+met\\b|\\bunmet\\b");
    private static final Pattern NOT_CHECKED = Pattern.compile("(?i)\\bnot\\s+checked\\b");
    /** "met" as the status: at the start of the text or right after a colon or a dash. */
    private static final Pattern MET = Pattern.compile("(?i)(?:^|[:\\u2013\\u2014-])\\s*[*_]{0,2}met\\b");
    private static final Pattern NONE = Pattern.compile("(?i)(?:[-*+]\\s+)?[*_]{0,2}(?:none(?:\\s+found)?|n/a"
            + "|nothing(?:\\s+to\\s+report)?|no\\s+assumptions?(?:\\s+(?:were\\s+)?made)?"
            + "|all\\s+sources\\s+were\\s+readable)[*_]{0,2}[.!]?");
    private static final Pattern LIST_ITEM = Pattern.compile("(\\s*)([-*+]|[0-9]{1,3}[.)])\\s+(.*)");
    private static final Pattern INNER_HEADING = Pattern.compile("\\s{0,3}#{1,6}\\s+(.*)");
    private static final int REPORT_LIMIT = 8000;
    private static final int SECTION_LINES = 20;
    private static final int CONFIRMED_DECISIONS_SHOWN = 5;
    private static final int CHANGED_FILES_SHOWN = 20;
    // REPORT_LIMIT already bounds the whole report; this only stops one runaway line.
    private static final int LINE_LIMIT = 2000;
    private static final Pattern TOKEN =
            Pattern.compile(
                    "\\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,})\\b");

    public String render(
            String issueKey,
            String jiraBaseUrl,
            Candidate candidate,
            VerificationReceipt verification,
            DeliveryReceipt delivery,
            JsonNode request,
            JsonNode outcome) {
        var body = new StringBuilder("## Issue\n\n")
                .append(issueLink(issueKey, jiraBaseUrl))
                .append("\n\n");
        appendAgentReport(body, outcome.path("summary").asString(""));
        appendConfirmedDecisions(body, request.path("confirmedDecisions"));
        appendChangedFiles(body, candidate.patch());
        appendVerification(body, verification);
        appendVerificationDetails(body, verification);
        appendCandidateDetails(body, verification.executionId(), candidate, delivery);
        return body.toString();
    }

    /**
     * The issue link is composed from the validated issue identity and trusted
     * operator configuration only; issue text reaches the body exclusively
     * through {@code prose}, which strips Markdown structure, links and mentions.
     */
    private static String issueLink(String issueKey, String jiraBaseUrl) {
        if (issueKey == null || !issueKey.matches("[A-Z][A-Z0-9]*-[0-9]+")) {
            return "Issue key unavailable";
        }
        String base = jiraBaseUrl == null ? "" : jiraBaseUrl.strip();
        if (!base.matches("https?://[A-Za-z0-9.-]+(:[0-9]+)?")) {
            return issueKey;
        }
        return "[" + issueKey + "](" + base + "/browse/" + issueKey + ")";
    }

    private static void appendConfirmedDecisions(StringBuilder body, JsonNode decisions) {
        var shown = 0;
        for (var decision : decisions) {
            if (shown == CONFIRMED_DECISIONS_SHOWN) {
                break;
            }
            if (shown == 0) {
                body.append("## Confirmed decisions\n\n");
            }
            body.append("- ")
                    .append(prose(decision.path("question").asString(""), 300))
                    .append(" — ")
                    .append(prose(decision.path("answer").asString(""), 500))
                    .append("\n");
            shown++;
        }
        if (shown > 0) {
            body.append("\n");
        }
    }

    private static void appendChangedFiles(StringBuilder body, String patch) {
        var files = patch.lines()
                .filter(line -> line.startsWith("diff --git "))
                .map(PullRequestRenderer::changedPath)
                .toList();
        body.append("## Changed files\n\n");
        files.stream()
                .limit(CHANGED_FILES_SHOWN)
                .forEach(path -> body.append("- `").append(code(path, 240)).append("`\n"));
        if (files.size() > CHANGED_FILES_SHOWN) {
            body.append("- Additional files are visible in the PR diff.\n");
        }
    }

    private static void appendVerification(StringBuilder body, VerificationReceipt verification) {
        body.append("\n## Verification\n\n**")
                .append(prose(verification.result(), 40))
                .append("** · ")
                .append(count(verification.testCount()))
                .append(" tests · ")
                .append(count(verification.failureCount()))
                .append(" failures · ")
                .append(count(verification.errorCount()))
                .append(" errors\n\n");
        appendCode(body, "Plan", verification.planId(), 200);
        appendCode(body, "Command", String.join(" ", verification.argv()), 1200);
        appendCode(body, "Image", verification.image(), 300);
        body.append("\n");
    }

    private static void appendVerificationDetails(StringBuilder body, VerificationReceipt verification) {
        body.append("<details>\n<summary>Factory verification details</summary>\n\n")
                .append("- Surefire reports: ")
                .append(verification.surefireReportCount())
                .append("\n")
                .append("- Failsafe reports: ")
                .append(verification.failsafeReportCount())
                .append("\n")
                .append("- Required suites: ")
                .append(requiredSuites(verification.requiredReports()))
                .append("\n")
                .append("\nFactory independently verified the exact frozen candidate using the"
                        + " configured verification plan.\n")
                .append("</details>\n\n");
    }

    private static String requiredSuites(List<String> requiredReports) {
        return requiredReports.isEmpty()
                ? "No named suites configured; aggregate executed-test evidence only."
                : prose(String.join(", ", requiredReports), 1600);
    }

    private static void appendCandidateDetails(
            StringBuilder body, String executionId, Candidate candidate, DeliveryReceipt delivery) {
        body.append("<details>\n<summary>Candidate and delivery details</summary>\n\n");
        appendCode(body, "Execution", executionId, 200);
        body.append("- Destination: `")
                .append(code(delivery.repository(), 300))
                .append("` / `")
                .append(code(delivery.branch(), 300))
                .append("`\n");
        appendCode(body, "Base", candidate.baseSha(), 100);
        appendCode(body, "Tree", candidate.treeSha(), 100);
        appendCode(body, "Patch SHA-256", candidate.patchSha256(), 100);
        appendCode(body, "Delivered commit", delivery.commitSha(), 100);
        body.append("</details>");
    }

    private static void appendCode(StringBuilder body, String label, String value, int limit) {
        body.append("- ").append(label).append(": `").append(code(value, limit)).append("`\n");
    }

    /**
     * Why the pull request is opened as a draft, in Factory's own words (model text never
     * reaches them); empty when the agent's report confirms everything.
     */
    public static List<String> draftReasons(JsonNode outcome) {
        Map<String, String> sections = reportSections(outcome.path("summary").asString(""));
        if (sections == null) {
            return List.of("The agent's final report does not use the four expected sections,"
                    + " so nothing in it is confirmed.");
        }
        var reasons = new ArrayList<>(criteriaReasons(criterionStatuses(sections.get(CRITERIA))));
        if (!isNone(sections.get(ASSUMPTIONS))) {
            reasons.add("The agent made assumptions that a reviewer should confirm.");
        }
        if (!isNone(sections.get(COULD_NOT_READ))) {
            reasons.add("The agent could not read some of the task's sources.");
        }
        return List.copyOf(reasons);
    }

    private static List<String> criteriaReasons(List<CriterionStatus> statuses) {
        var notMet = statuses.stream().filter(status -> status == CriterionStatus.NOT_MET).count();
        var notChecked = statuses.stream().filter(status -> status == CriterionStatus.NOT_CHECKED).count();
        var reasons = new ArrayList<String>();
        if (notMet > 0) {
            reasons.add(notMet == 1 ? "1 acceptance criterion is not met."
                    : notMet + " acceptance criteria are not met.");
        }
        if (notChecked > 0) {
            reasons.add(notChecked == 1 ? "1 acceptance criterion was not checked."
                    : notChecked + " acceptance criteria were not checked.");
        }
        return reasons;
    }

    private static List<CriterionStatus> criterionStatuses(String criteria) {
        if (isNone(criteria)) {
            return List.of();
        }
        return criteria.lines()
                .filter(line -> !line.isBlank())
                .filter(line -> !RUNTIME_CHECKS.matcher(line).find())
                .map(PullRequestRenderer::criterionStatus)
                .toList();
    }

    /**
     * A criterion is confirmed only when its status plainly says "met"; a line that does not say
     * "met" or "not met" (no status, "not verified", "failed") counts as not checked.
     */
    private static CriterionStatus criterionStatus(String line) {
        var item = LIST_ITEM.matcher(line);
        var text = item.matches() ? item.group(3) : line.strip();
        if (NOT_MET.matcher(text).find()) {
            return CriterionStatus.NOT_MET;
        }
        if (NOT_CHECKED.matcher(text).find() || !MET.matcher(text).find()) {
            return CriterionStatus.NOT_CHECKED;
        }
        return CriterionStatus.MET;
    }

    /**
     * The four sections the coding prompt asks for, read from the whole final message (not
     * the shorter cut used for the fallback); null when a heading is missing or repeated.
     */
    static Map<String, String> reportSections(String message) {
        String text = message.substring(0, Math.min(message.length(), REPORT_LIMIT));
        Map<String, StringBuilder> found = new LinkedHashMap<>();
        StringBuilder current = null;
        for (String line : text.lines().toList()) {
            var heading = REPORT_HEADING.matcher(line.strip());
            if (heading.matches()) {
                String name = REPORT_SECTIONS.stream()
                        .filter(section -> section.equalsIgnoreCase(heading.group(1).replaceAll("\\s+", " ")))
                        .findFirst().orElseThrow();
                if (found.containsKey(name)) return null;
                current = new StringBuilder();
                found.put(name, current);
            } else if (current != null) {
                current.append(line).append('\n');
            }
        }
        if (found.size() != REPORT_SECTIONS.size()) return null;
        Map<String, String> sections = new LinkedHashMap<>();
        REPORT_SECTIONS.forEach(name -> sections.put(name, found.get(name).toString().strip()));
        return sections;
    }

    private static void appendAgentReport(StringBuilder body, String message) {
        Map<String, String> sections = reportSections(message);
        if (sections == null) {
            String text = message.substring(0, Math.min(message.length(), 4000));
            var checks = RUNTIME_CHECKS.matcher(text);
            if (checks.find()) text = text.substring(0, checks.start());
            String report = block(text);
            body.append("## Agent report\n\n")
                    .append(report.isBlank() ? "The agent sent no final report." : report)
                    .append("\n\n");
            return;
        }
        sections.forEach((name, text) -> {
            boolean always = name.equals(CHANGES) || name.equals(CRITERIA);
            if (!always && isNone(text)) return;
            // The agent's own checks are cut from Changes only: the other sections explain the draft flag.
            var checks = RUNTIME_CHECKS.matcher(text);
            String shown = block(name.equals(CHANGES) && checks.find() ? text.substring(0, checks.start()) : text);
            body.append("## ").append(name).append("\n\n")
                    .append(shown.isBlank() ? "None." : shown)
                    .append("\n\n");
        });
    }

    /** Only the first paragraph counts, so a closing line after "None" does not make the section non-empty. */
    private static boolean isNone(String section) {
        String first = section.strip().split("\\n\\s*\\n", 2)[0].strip();
        return first.isEmpty() || NONE.matcher(first).matches();
    }

    /**
     * Model text keeps its paragraphs, lists and headings, but Factory writes every list
     * marker and heading itself and each line's content still goes through {@code prose}.
     */
    private static String block(String text) {
        var block = new Block();
        for (var raw : text.lines().toList()) {
            if (!block.add(raw)) {
                break;
            }
        }
        return block.text();
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
        var text = URL.matcher(redact(value))
                .replaceAll("[link omitted]")
                .replace("`", "")
                .replaceAll("[\\p{Cntrl}\\s]+", " ")
                .strip();
        // Cut before escaping, so the limit counts what the reader sees and never splits an entity.
        if (text.length() > limit) {
            text = text.substring(0, limit) + "…";
        }
        return escapeMarkdown(text);
    }

    private static String escapeMarkdown(String text) {
        return text.replace("&", "&amp;")
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
                .replaceFirst("^([0-9]{1,9})\\.(?=\\s)", "$1&#46;")
                .replaceFirst("^([0-9]{1,9})\\)(?=\\s)", "$1&#41;");
    }

    private enum CriterionStatus { MET, NOT_MET, NOT_CHECKED }

    /** One section of model text as it is rewritten line by line; stops after {@code SECTION_LINES}. */
    private static final class Block {
        private final StringBuilder out = new StringBuilder();
        private int lines;
        private boolean gap;
        private boolean afterItem;

        /** False once the section is cut and later lines must be dropped. */
        boolean add(String raw) {
            if (raw.isBlank()) {
                gap = true;
                return true;
            }
            if (lines == SECTION_LINES) {
                out.append("\n\nThe rest of this section was cut.");
                return false;
            }
            var item = LIST_ITEM.matcher(raw);
            var heading = INNER_HEADING.matcher(raw);
            var isItem = item.matches();
            var isHeading = !isItem && heading.matches();
            var content = prose(isItem ? item.group(3) : isHeading ? heading.group(1) : raw, LINE_LIMIT);
            if (!content.isBlank()) {
                append(isItem ? itemLine(item, content) : isHeading ? "### " + content : content, isItem);
            }
            return true;
        }

        private String itemLine(Matcher item, String content) {
            // Indentation only nests under the item just above; anywhere else it could start a code block.
            var indent = afterItem && !gap ? Math.min(item.group(1).replace("\t", "  ").length(), 4) : 0;
            var marker = Character.isDigit(item.group(2).charAt(0)) ? item.group(2).replace(')', '.') : "-";
            return " ".repeat(indent) + marker + " " + content;
        }

        private void append(String line, boolean isItem) {
            // A text line right after a list item would join that item; it starts its own paragraph.
            if (!out.isEmpty()) {
                out.append(gap || afterItem && !isItem ? "\n\n" : "\n");
            }
            out.append(line);
            gap = false;
            afterItem = isItem;
            lines++;
        }

        String text() {
            return out.toString();
        }
    }
}
