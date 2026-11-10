package org.folio.factory.devfactory.feedback;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads the agent's report of a feedback round. The report is Markdown with sections such as
 * "## Changes" and "## Assumptions"; the run page shows a short plain brief of the changes, and
 * Factory's comment on the pull request carries the changes section only.
 */
public final class RoundReport {

    private static final int BRIEF = 280;
    private static final int SENTENCES = 2;
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s*(.*?)\\s*#*\\s*$");
    /** "(PR feedback inline:4530617972)": Factory's own comment ids mean nothing on GitHub. */
    private static final Pattern INTERNAL_ID = Pattern.compile(
            "\\s*\\((?:PR feedback )?(?:inline|issue|review):\\d+\\)"
                    + "|(?:PR feedback )?(?:inline|issue|review):\\d+");
    private static final Pattern LIST_MARKER = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    private RoundReport() {
    }

    /**
     * The "Changes" section without its heading and without Factory's internal comment ids. A report
     * without that heading gives its text before the first heading.
     */
    public static String changes(String report) {
        var lines = report == null ? List.<String>of() : report.strip().lines().toList();
        var section = section(lines, "changes");
        var text = String.join("\n", section.isEmpty() ? beforeFirstHeading(lines) : section).strip();
        return INTERNAL_ID.matcher(text).replaceAll("").strip();
    }

    /** One or two plain sentences of the changes, at most 280 characters. */
    public static String brief(String report) {
        var sentences = new ArrayList<String>();
        for (var line : changes(report).lines().toList()) {
            var plain = plain(line);
            if (!plain.isEmpty()) {
                sentences.addAll(List.of(SENTENCE_END.split(plain.endsWith(".") ? plain : plain + ".")));
            }
        }
        return fit(sentences);
    }

    private static List<String> section(List<String> lines, String name) {
        var section = new ArrayList<String>();
        var inside = false;
        for (var line : lines) {
            var heading = HEADING.matcher(line);
            if (heading.matches()) {
                if (inside) {
                    break;
                }
                inside = heading.group(1).toLowerCase(Locale.ROOT).equals(name);
            } else if (inside) {
                section.add(line);
            }
        }
        return section;
    }

    private static List<String> beforeFirstHeading(List<String> lines) {
        return lines.stream().takeWhile(line -> !HEADING.matcher(line).matches()).toList();
    }

    private static String plain(String line) {
        var text = LIST_MARKER.matcher(line).replaceFirst("");
        text = LINK.matcher(text).replaceAll("$1");
        return text.replace("`", "").replace("**", "").replace("__", "").strip();
    }

    /** The first sentences while they fit; a first sentence that is too long is cut at a word. */
    private static String fit(List<String> sentences) {
        var brief = new StringBuilder();
        for (var sentence : sentences.subList(0, Math.min(SENTENCES, sentences.size()))) {
            if (brief.length() + sentence.length() + 1 > BRIEF) {
                break;
            }
            brief.append(brief.isEmpty() ? "" : " ").append(sentence);
        }
        if (!brief.isEmpty() || sentences.isEmpty()) {
            return brief.toString();
        }
        var first = sentences.getFirst().substring(0, BRIEF - 1);
        var space = first.lastIndexOf(' ');
        return (space > BRIEF / 2 ? first.substring(0, space) : first) + "…";
    }
}
