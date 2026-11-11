package org.folio.factory.devfactory.feedback;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads the agent's report of a feedback round. The report is Markdown with sections such as
 * "## Changes" and "## Assumptions"; the run page shows the first sentence of the changes and the report as
 * plain text, and Factory's comment on the pull request carries the changes section only.
 */
public final class RoundReport {

    private static final int FIRST_SENTENCE = 200;
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s*(.*?)\\s*#*\\s*$");
    /** "(PR feedback inline:4530617972)": Factory's own comment ids mean nothing on GitHub. */
    private static final Pattern INTERNAL_ID = Pattern.compile(
            "\\s*\\((?:PR feedback )?(?:inline|issue|review):\\d+\\)"
                    + "|(?:PR feedback )?(?:inline|issue|review):\\d+");
    private static final Pattern LIST_MARKER = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");
    /** A heading inside a line: a round without changes puts a sentence of Factory's before the report. */
    private static final Pattern INLINE_HEADING = Pattern.compile("\\s+(?=#{2,6}\\s)");

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

    /**
     * The first sentence of the "Changes" section as plain text, cut at a word with "…" when it is long. Empty
     * when the report has no such section: a guess from other text could say what the agent did not do.
     */
    public static String firstSentence(String report) {
        var first = section(headingLines(report), "changes").stream()
                .map(line -> INTERNAL_ID.matcher(plain(line)).replaceAll("").strip())
                .filter(line -> !line.isEmpty())
                .findFirst();
        if (first.isEmpty()) {
            return "";
        }
        var sentence = SENTENCE_END.split(first.get(), 2)[0];
        return cutAtWord(sentence.matches(".*[.!?]$") ? sentence : sentence + ".");
    }

    /**
     * True when the report has a "Changes" section. Without one the stored text is Factory's own summary of the
     * round ("Verified changes published to the same PR"), not the agent's report.
     */
    public static boolean hasChanges(String report) {
        return !section(headingLines(report), "changes").isEmpty();
    }

    /** The report without Markdown marks or internal ids: headings and list items keep their own lines. */
    public static String text(String report) {
        var text = new ArrayList<String>();
        for (var line : headingLines(report)) {
            var heading = HEADING.matcher(line);
            var plain = heading.matches() ? plain(heading.group(1)) : plain(line);
            text.add(LIST_MARKER.matcher(line).find() ? "• " + plain : plain);
        }
        return INTERNAL_ID.matcher(String.join("\n", text)).replaceAll("").strip();
    }

    /** The lines of the report, with a heading that follows text on its line moved to a line of its own. */
    private static List<String> headingLines(String report) {
        var lines = report == null ? List.<String>of() : report.strip().lines().toList();
        return lines.stream().flatMap(INLINE_HEADING::splitAsStream).toList();
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

    private static String cutAtWord(String sentence) {
        if (sentence.length() <= FIRST_SENTENCE) {
            return sentence;
        }
        var cut = sentence.substring(0, FIRST_SENTENCE - 1);
        var space = cut.lastIndexOf(' ');
        return (space > 0 ? cut.substring(0, space) : cut).stripTrailing() + "…";
    }
}
