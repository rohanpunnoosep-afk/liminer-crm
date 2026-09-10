package com.liminer.brief;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Offline cover for the heading discrimination DocumentSectionExtractor needs on
 * real ADV Part 2A brochures.
 *
 * The fixture reproduces the shape that made the extractor return "" for the
 * Nelson Capital Advisors brochure: OpenPDF emits a whole brochure page as ONE
 * line, so the Table of Contents, a mid-sentence cross-reference and the real
 * heading all sit on the same line as surrounding text and none of them starts a
 * line. "Item 8 at the start of a line" identifies none of them; what separates
 * them is the dot leaders (ToC) and the prose running into the label
 * (cross-reference).
 */
public class DocumentSectionExtractorTest
{
    private static final String FLAT_BROCHURE =
        "Form ADV Part 2A Firm Brochure\n"
        + "Item 3 - Table of Contents  Item 7 - Types of Clients ...................... 8  "
        + "Item 8 - Methods of Analysis, Investment Strategies and Risk of Loss ......... 9  "
        + "Item 9 - Disciplinary Information ...................... 11\n"
        + "Advisory services are tailored to each client. (Please refer to Item 8 - Methods "
        + "of Analysis, Investment Strategies and Risk of Loss for more detail.)  "
        + "Fees are billed quarterly in arrears.\n"
        + "Minimum investment amounts are negotiable.  "
        + "Item 8 - Methods of Analysis, Investment Strategies and Risk of Loss  "
        + "Methods of Analysis  The firm builds concentrated portfolios of listed equities "
        + "and screens each position on balance-sheet quality.  "
        + "Item 9 - Disciplinary Information  There are no legal or disciplinary events.\n";

    @Test
    public void extractsRealHeadingFromSingleLineBrochureText()
    {
        String section = DocumentSectionExtractor.extractSection(FLAT_BROCHURE, "Item 8");

        assertFalse(section.trim().isEmpty(),
            "Item 8 should extract even when the brochure has no line-start headings");
        assertTrue(section.startsWith("Item 8"),
            "Section should open on the Item 8 heading, not mid-document: " + head(section));
        assertTrue(section.contains("concentrated portfolios of listed equities"),
            "Section should carry the real Item 8 body text");
    }

    @Test
    public void skipsTableOfContentsAndCrossReference()
    {
        String section = DocumentSectionExtractor.extractSection(FLAT_BROCHURE, "Item 8");

        assertFalse(section.contains("......"),
            "A ToC entry was picked up as the heading: " + head(section));
        assertFalse(section.contains("Please refer to"),
            "A cross-reference was picked up as the heading: " + head(section));
    }

    @Test
    public void stopsAtTheNextItemHeading()
    {
        String section = DocumentSectionExtractor.extractSection(FLAT_BROCHURE, "Item 8");

        assertFalse(section.contains("There are no legal or disciplinary events"),
            "Section ran past Item 9 into the next section: " + head(section));
    }

    @Test
    public void returnsEmptyWhenSectionIsAbsent()
    {
        assertTrue(DocumentSectionExtractor.extractSection(FLAT_BROCHURE, "Item 42").isEmpty(),
            "A section that is not in the document must not resolve to some other span");
    }

    private static String head(String s)
    {
        return s.substring(0, Math.min(160, s.length())).replaceAll("\\s+", " ");
    }
}
