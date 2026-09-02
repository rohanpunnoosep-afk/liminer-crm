package com.liminer.brief;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * DocumentSectionExtractor — ToC-guided section extraction for long regulatory
 * documents (ADV Part 2 brochures, 990 narratives, CAFR sections).
 *
 * Why: Item 8 in the Rock Creek brochure runs pp.14–58. Naive prompting of the full
 * doc overflows context. This extractor locates the target section via the Table of
 * Contents, pulls only that span, and lets the caller LLM-summarize a manageable chunk.
 *
 * All large raw text lives only in local variables — NEVER written to shared state or
 * the sheet.
 *
 * Usage:
 *   String item8Text = DocumentSectionExtractor.extractSection(fullText, "Item 8");
 *   // item8Text is at most MAX_SECTION_CHARS; pass to LLM for summarization.
 */
public class DocumentSectionExtractor
{
    // Maximum characters returned for a section — keeps LLM prompts manageable.
    private static final int MAX_SECTION_CHARS = 40_000;

    /**
     * Extract the text of a named section from a regulatory document.
     *
     * Strategy:
     *  1. Locate the Table of Contents and find the target section's page start + the
     *     NEXT section's page start.  Use those page-marker boundaries to slice the body.
     *  2. If no ToC (or page markers not found), fall back to a regex search for the
     *     section heading in the body text and extract until the next same-level heading.
     *  3. Cap the result at MAX_SECTION_CHARS and return.  Returns "" on failure.
     *
     * @param fullText    the full plain-text representation of the document
     * @param sectionLabel  e.g. "Item 8" (case-insensitive, partial match)
     */
    public static String extractSection(String fullText, String sectionLabel)
    {
        if (isBlank(fullText) || isBlank(sectionLabel))
        {
            return "";
        }

        // --- Strategy 1: ToC-guided page-boundary extraction ---
        String tocResult = extractViaToc(fullText, sectionLabel);
        if (!isBlank(tocResult))
        {
            return truncate(tocResult, MAX_SECTION_CHARS);
        }

        // --- Strategy 2: Regex heading search ---
        String regexResult = extractViaHeadingRegex(fullText, sectionLabel);
        if (!isBlank(regexResult))
        {
            return truncate(regexResult, MAX_SECTION_CHARS);
        }

        return "";
    }

    // -----------------------------------------------------------------------
    // Strategy 1 — ToC-guided
    // -----------------------------------------------------------------------

    private static String extractViaToc(String fullText, String sectionLabel)
    {
        // Locate the ToC block: look for a cluster of "Item N" lines with page numbers.
        int tocStart = findTocStart(fullText);
        if (tocStart < 0) return "";

        // Typical ToC line: "Item 8   Methods of Analysis.....................14"
        //  or "Item 8 – Methods of Analysis ........ 14"
        //  or "Item 8............14"
        Pattern tocLinePattern = Pattern.compile(
            "(?i)(" + Pattern.quote(sectionLabel) + "[^\\n]*?)(\\d{1,4})\\s*[\\n\\r]",
            Pattern.CASE_INSENSITIVE);

        Matcher m = tocLinePattern.matcher(fullText);
        m.region(tocStart, Math.min(tocStart + 8000, fullText.length()));

        int targetPage = -1;
        int nextPage = -1;

        if (!m.find()) return "";
        targetPage = parsePageNum(m.group(2));

        // Find the NEXT ToC entry after the target to know where this section ends.
        int afterTarget = m.end();
        Pattern nextItemPattern = Pattern.compile(
            "(?i)Item\\s+\\d+[^\\n]*?(\\d{1,4})\\s*[\\n\\r]");
        Matcher nm = nextItemPattern.matcher(fullText);
        nm.region(afterTarget, Math.min(afterTarget + 5000, fullText.length()));
        if (nm.find())
        {
            nextPage = parsePageNum(nm.group(1));
        }

        if (targetPage < 0) return "";

        // Now find that page in the body text (after ToC).
        // Page markers appear as: standalone numbers on a line, "Page N", or form-feed + number.
        int bodyStart = Math.min(tocStart + 8000, fullText.length());

        int sectionStart = findPageMarker(fullText, targetPage, bodyStart);
        if (sectionStart < 0)
        {
            // Fall back: search for the heading itself in the body.
            sectionStart = findHeadingInBody(fullText, sectionLabel, bodyStart);
        }
        if (sectionStart < 0) return "";

        int sectionEnd = fullText.length();
        if (nextPage > 0)
        {
            int nextMarker = findPageMarker(fullText, nextPage, sectionStart + 10);
            if (nextMarker > sectionStart) sectionEnd = nextMarker;
        }
        else
        {
            // Fall back to heading-based end boundary.
            sectionEnd = findNextSameLevelHeading(fullText, sectionStart + 10, sectionLabel);
            if (sectionEnd <= sectionStart) sectionEnd = fullText.length();
        }

        return fullText.substring(sectionStart, Math.min(sectionEnd, fullText.length())).trim();
    }

    private static int findTocStart(String text)
    {
        // Common Table of Contents indicators.
        String[] tocMarkers = {
            "TABLE OF CONTENTS", "Table of Contents", "CONTENTS", "Contents"
        };
        for (String marker : tocMarkers)
        {
            int idx = text.indexOf(marker);
            if (idx >= 0) return idx;
        }
        // Detect by presence of "Item 1" + "Item 2" near each other in the first 20k chars.
        int cap = Math.min(20000, text.length());
        int i1 = text.indexOf("Item 1");
        int i2 = text.indexOf("Item 2");
        if (i1 >= 0 && i2 >= 0 && i2 - i1 < 500 && i1 < cap) return i1;
        return -1;
    }

    private static int findPageMarker(String text, int pageNum, int searchFrom)
    {
        // Patterns: "\n14\n", "Page 14", "\fPage 14", line that is ONLY the number.
        String num = String.valueOf(pageNum);

        Pattern[] patterns = {
            Pattern.compile("(?m)^\\s*" + Pattern.quote(num) + "\\s*$"),
            Pattern.compile("(?i)\\bPage\\s+" + Pattern.quote(num) + "\\b"),
            Pattern.compile("\\f\\s*" + Pattern.quote(num) + "\\s*\\n")
        };

        int best = -1;
        for (Pattern p : patterns)
        {
            Matcher m = p.matcher(text);
            if (searchFrom > 0) m.region(searchFrom, text.length());
            if (m.find())
            {
                int pos = m.start();
                if (best < 0 || pos < best) best = pos;
            }
        }
        return best;
    }

    private static int parsePageNum(String raw)
    {
        if (isBlank(raw)) return -1;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { return -1; }
    }

    // -----------------------------------------------------------------------
    // Strategy 2 — Heading regex
    // -----------------------------------------------------------------------

    private static String extractViaHeadingRegex(String fullText, String sectionLabel)
    {
        int headingPos = findHeadingInBody(fullText, sectionLabel, 0);
        if (headingPos < 0) return "";

        int nextHeading = findNextSameLevelHeading(fullText, headingPos + sectionLabel.length(), sectionLabel);
        if (nextHeading <= headingPos) nextHeading = fullText.length();

        return fullText.substring(headingPos, Math.min(nextHeading, fullText.length())).trim();
    }

    private static int findHeadingInBody(String text, String sectionLabel, int searchFrom)
    {
        String lower = text.toLowerCase();
        String target = sectionLabel.toLowerCase();
        int idx = lower.indexOf(target, searchFrom);
        while (idx >= 0)
        {
            // Accept if at line start (or near it) — heading, not inline mention.
            int lineStart = text.lastIndexOf('\n', idx);
            String prefix = text.substring(Math.max(lineStart + 1, 0), idx).trim();
            if (prefix.isEmpty() || prefix.matches("\\d+\\.?"))
            {
                return idx;
            }
            idx = lower.indexOf(target, idx + 1);
        }
        return -1;
    }

    private static int findNextSameLevelHeading(String text, int searchFrom, String currentLabel)
    {
        // "Item N" heading pattern — works for ADV Form items.
        Pattern itemPat = Pattern.compile("(?m)^\\s*Item\\s+\\d+", Pattern.CASE_INSENSITIVE);
        Matcher m = itemPat.matcher(text);
        if (searchFrom > 0) m.region(searchFrom, text.length());
        if (m.find()) return m.start();
        return text.length();
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static String truncate(String text, int maxLen)
    {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }

    private static boolean isBlank(String s)
    {
        return s == null || s.trim().isEmpty();
    }
}
