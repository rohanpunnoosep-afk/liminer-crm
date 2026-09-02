package com.liminer.brief;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;

import org.json.JSONArray;
import org.json.JSONObject;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfWriter;

/*
 * InvestorBriefPdfRenderer — the rendering layer for the investor brief (steps #2 and #3 of
 * ExtraDocuments/pdfinfraneeds.md). It takes the assembled investor-brief JSON (the same shape
 * produced by InvestorBriefJson.toJSON() and stored in the "Investor Brief JSON" sheet column)
 * and formats it into a meeting-brief PDF using OpenPDF.
 *
 * Single-responsibility, static-method style mirroring the project's processor classes. No
 * computation — it only walks the controlled schema and emits document elements.
 *
 *   render(brief)                 -> byte[]  : the PDF bytes (in memory)              (#2)
 *   renderToFile(brief, dir)      -> Path    : write to dir, return absolute path     (#3)
 *   renderFromStoredJson(cell,..) -> Path    : parse the stored column cell, then write
 *                                              (truncation-tolerant via parseBlob)     (bridge)
 *
 * The stored cell is truncated to 49,000 chars (InvestorBriefJsonProcessor.BRIEF_JSON_MAX), so the
 * bridge uses InvestorBriefJson.parseBlob, which tolerates LLM/partial JSON and throws a clear
 * error only when the value is not a usable object.
 */
public class InvestorBriefPdfRenderer
{
    private static final Path DEFAULT_OUTPUT_DIR = Paths.get("ExtraDocuments", "briefs");

    private static final Font TITLE_FONT   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, Font.BOLD);
    private static final Font SUBTITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA, 13);
    private static final Font META_FONT    = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9);
    private static final Font SECTION_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Font.BOLD);
    private static final Font LABEL_FONT   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font BODY_FONT    = FontFactory.getFont(FontFactory.HELVETICA, 10);

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    // #2: render the brief into PDF bytes.
    public static byte[] render(JSONObject brief)
    {
        if (brief == null) brief = new JSONObject();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.LETTER, 54, 54, 54, 54);
        try
        {
            PdfWriter.getInstance(doc, baos);
            doc.open();

            JSONObject contact = brief.optJSONObject("contactAndFirmProfile");
            if (contact == null) contact = new JSONObject();

            addHeaderBlock(doc, contact, brief.optString("asOfDate", ""));
            addExecutiveSummary(doc, brief.optString("executiveSummary", ""));
            addContactAndFirmProfile(doc, contact);
            addMarketIntelligence(doc, brief.optJSONObject("marketIntelligence"));
            addRelationshipSummary(doc, brief.optJSONObject("relationshipSummary"));
            addCallPreparation(doc, brief.optJSONObject("callPreparation"));
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to render investor brief PDF: " + e.getMessage(), e);
        }
        finally
        {
            if (doc.isOpen()) doc.close();
        }
        return baos.toByteArray();
    }

    // #3: render and write to a folder, returning the absolute path. Overwrites a same-named file.
    public static Path renderToFile(JSONObject brief, Path outputDir) throws Exception
    {
        if (outputDir == null) outputDir = DEFAULT_OUTPUT_DIR;
        byte[] pdf = render(brief);

        Files.createDirectories(outputDir);
        JSONObject contact = brief == null ? new JSONObject() : brief.optJSONObject("contactAndFirmProfile");
        if (contact == null) contact = new JSONObject();
        String fileName = buildFileName(contact, brief == null ? "" : brief.optString("asOfDate", ""));

        Path out = outputDir.resolve(fileName);
        Files.write(out, pdf);
        return out.toAbsolutePath();
    }

    public static Path renderToFile(JSONObject brief) throws Exception
    {
        return renderToFile(brief, DEFAULT_OUTPUT_DIR);
    }

    // Bridge: render directly from the raw "Investor Brief JSON" column cell value. Uses the
    // truncation-tolerant parser so a partially-stored cell still renders when recoverable.
    public static Path renderFromStoredJson(String columnCellValue, Path outputDir) throws Exception
    {
        Object parsed = InvestorBriefJson.parseBlob(columnCellValue);
        if (!(parsed instanceof JSONObject))
        {
            throw new IllegalArgumentException(
                "Stored investor brief JSON is not a parseable object (possibly empty or truncated beyond recovery).");
        }
        return renderToFile((JSONObject) parsed, outputDir);
    }

    public static Path renderFromStoredJson(String columnCellValue) throws Exception
    {
        return renderFromStoredJson(columnCellValue, DEFAULT_OUTPUT_DIR);
    }

    // -----------------------------------------------------------------------
    // Sections
    // -----------------------------------------------------------------------

    private static void addHeaderBlock(Document doc, JSONObject contact, String asOfDate) throws Exception
    {
        String name = (contact.optString("firstName", "").trim() + " "
                     + contact.optString("lastName", "").trim()).trim();
        if (name.isEmpty()) name = "Investor Brief";

        Paragraph title = new Paragraph(name, TITLE_FONT);
        doc.add(title);

        String fund = contact.optString("fundName", "").trim();
        if (!fund.isEmpty()) doc.add(new Paragraph(fund, SUBTITLE_FONT));

        String asOf = dateOnly(asOfDate);
        if (!asOf.isEmpty()) doc.add(new Paragraph("As of " + asOf, META_FONT));

        addSpacer(doc);
    }

    private static void addExecutiveSummary(Document doc, String summary)
    {
        if (isBlank(summary)) return;
        addSectionHeading(doc, "Executive Summary");
        addParagraph(doc, summary);
        addSpacer(doc);
    }

    private static void addContactAndFirmProfile(Document doc, JSONObject c)
    {
        if (c == null || c.length() == 0) return;
        addSectionHeading(doc, "Contact & Firm Profile");

        addLabeledLine(doc, "Email", c.optString("email", ""));
        addLabeledLine(doc, "Website", c.optString("website", ""));
        addLabeledLine(doc, "Investor Type", c.optString("typeOfInvestor", ""));
        addLabeledLine(doc, "Sectors", joinArray(c.optJSONArray("sectorTags")));
        addLabeledLine(doc, "Microsectors", joinArray(c.optJSONArray("microsectorTags")));
        addLabeledLine(doc, "Geography", joinArray(c.optJSONArray("geography")));
        addLabeledLine(doc, "Prior Backed Funds", joinArray(c.optJSONArray("priorBackedFunds")));

        String thesis = c.optString("investmentThesis", "");
        if (!isBlank(thesis))
        {
            addLabeledLine(doc, "Investment Thesis", "");
            addParagraph(doc, thesis);
        }

        String bio = firstNonBlank(
            c.optString("contactBioCareerSummary", ""),
            c.optString("contactWebsiteBioSummary", ""),
            c.optString("contactLinkedInAbout", ""));
        if (!isBlank(bio))
        {
            addLabeledLine(doc, "Background", "");
            addParagraph(doc, bio);
        }

        addSpacer(doc);
    }

    private static void addMarketIntelligence(Document doc, JSONObject m)
    {
        if (m == null || m.length() == 0) return;
        addSectionHeading(doc, "Market Intelligence");

        addLabeledLine(doc, "Funding Status", m.optString("fundingStatus", ""));
        addLabeledLine(doc, "Resources Score", numOrText(m, "resourcesScore"));
        addLabeledLine(doc, "Fit Score", numOrText(m, "fitScore"));
        addLabeledLine(doc, "Probability Now", numOrText(m, "probabilityNow"));
        addLabeledLine(doc, "Identity Status", m.optString("identityStatus", ""));
        addLabeledLine(doc, "CRD #", m.optString("crdNumber", ""));
        addLabeledLine(doc, "CIK #", m.optString("cikNumber", ""));
        addLabeledLine(doc, "LEI", m.optString("lei", ""));
        addLabeledLine(doc, "EIN", m.optString("ein", ""));

        addSpacer(doc);
    }

    private static void addRelationshipSummary(Document doc, JSONObject r)
    {
        if (r == null || r.length() == 0) return;
        addSectionHeading(doc, "Relationship Summary");

        addLabeledLine(doc, "Analysis Date", dateOnly(r.optString("analysisDate", "")));
        addLabeledLine(doc, "Interests", joinArray(r.optJSONArray("aggregatedInterests")));

        String sentiment = r.optString("sentimentChangesOverTime", "");
        if (!isBlank(sentiment))
        {
            addLabeledLine(doc, "Sentiment Over Time", "");
            addParagraph(doc, sentiment);
        }

        String arc = r.optString("narrativeArc", "");
        if (!isBlank(arc))
        {
            addLabeledLine(doc, "Narrative Arc", "");
            addParagraph(doc, arc);
        }

        JSONArray commitments = r.optJSONArray("outstandingCommitments");
        if (commitments != null && commitments.length() > 0)
        {
            addLabeledLine(doc, "Outstanding Commitments", "");
            addBulletList(doc, commitments);
        }

        addSpacer(doc);
    }

    private static void addCallPreparation(Document doc, JSONObject cp)
    {
        if (cp == null || cp.length() == 0) return;
        addSectionHeading(doc, "Call Preparation");

        addBulletSubsection(doc, "Talking Points", cp.optJSONArray("talkingPoints"));
        addBulletSubsection(doc, "Suggested Questions", cp.optJSONArray("suggestedQuestions"));
        addBulletSubsection(doc, "Relationship-Building Opportunities", cp.optJSONArray("relationshipBuildingOpportunities"));
        addBulletSubsection(doc, "Recommended Next Steps", cp.optJSONArray("recommendedNextSteps"));

        JSONArray objections = cp.optJSONArray("anticipatedObjections");
        if (objections != null && objections.length() > 0)
        {
            addLabeledLine(doc, "Anticipated Objections", "");
            com.lowagie.text.List list = new com.lowagie.text.List(false, 12);
            for (int i = 0; i < objections.length(); i++)
            {
                JSONObject o = objections.optJSONObject(i);
                String text;
                if (o != null)
                {
                    String objection = o.optString("objection", "").trim();
                    String navigation = o.optString("navigation", "").trim();
                    text = navigation.isEmpty() ? objection : objection + " → " + navigation;
                }
                else
                {
                    text = objections.optString(i, "").trim();
                }
                if (!text.isEmpty()) list.add(new com.lowagie.text.ListItem(text, BODY_FONT));
            }
            if (!list.isEmpty()) doc.add(list);
        }
    }

    // -----------------------------------------------------------------------
    // Element helpers
    // -----------------------------------------------------------------------

    private static void addSectionHeading(Document doc, String text)
    {
        try
        {
            Paragraph p = new Paragraph(text, SECTION_FONT);
            p.setSpacingBefore(6);
            p.setSpacingAfter(4);
            doc.add(p);
        }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    // Label + value on one line; skips entirely when the value is blank. An empty value with a
    // non-empty label renders just the label (used as a mini-heading before a paragraph/list).
    private static void addLabeledLine(Document doc, String label, String value)
    {
        try
        {
            boolean hasValue = !isBlank(value);
            Paragraph p = new Paragraph();
            p.setFont(BODY_FONT);
            p.add(new Phrase(label + ": ", LABEL_FONT));
            if (hasValue) p.add(new Phrase(value.trim(), BODY_FONT));
            doc.add(p);
        }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void addParagraph(Document doc, String text)
    {
        if (isBlank(text)) return;
        try
        {
            Paragraph p = new Paragraph(text.trim(), BODY_FONT);
            p.setSpacingAfter(4);
            doc.add(p);
        }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void addBulletSubsection(Document doc, String label, JSONArray arr)
    {
        if (arr == null || arr.length() == 0) return;
        addLabeledLine(doc, label, "");
        addBulletList(doc, arr);
    }

    private static void addBulletList(Document doc, JSONArray arr)
    {
        if (arr == null || arr.length() == 0) return;
        try
        {
            com.lowagie.text.List list = new com.lowagie.text.List(false, 12);
            for (int i = 0; i < arr.length(); i++)
            {
                String item = arr.optString(i, "").trim();
                if (!item.isEmpty()) list.add(new com.lowagie.text.ListItem(item, BODY_FONT));
            }
            if (!list.isEmpty()) doc.add(list);
        }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void addSpacer(Document doc)
    {
        try
        {
            Paragraph p = new Paragraph(" ", BODY_FONT);
            p.setSpacingAfter(2);
            doc.add(p);
        }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    // -----------------------------------------------------------------------
    // Value helpers
    // -----------------------------------------------------------------------

    // Filename: LastName_FirstName_YYYY-MM-DD.pdf, sanitized; falls back to "Unknown" / today.
    private static String buildFileName(JSONObject contact, String asOfDate)
    {
        String last  = sanitize(contact.optString("lastName", ""));
        String first = sanitize(contact.optString("firstName", ""));
        if (last.isEmpty() && first.isEmpty()) last = "Unknown";

        String date = dateOnly(asOfDate);
        if (date.isEmpty()) date = LocalDate.now().toString();

        StringBuilder sb = new StringBuilder();
        if (!last.isEmpty()) sb.append(last);
        if (!first.isEmpty()) { if (sb.length() > 0) sb.append('_'); sb.append(first); }
        sb.append('_').append(date).append(".pdf");
        return sb.toString();
    }

    // org.json renders a number-or-text value either way; present it as a trimmed string.
    private static String numOrText(JSONObject obj, String key)
    {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return "";
        return obj.opt(key).toString().trim();
    }

    private static String joinArray(JSONArray arr)
    {
        if (arr == null || arr.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++)
        {
            String item = arr.optString(i, "").trim();
            if (item.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(item);
        }
        return sb.toString();
    }

    // Keep just the date portion of an ISO instant ("2026-06-23T15:30:00Z" -> "2026-06-23").
    private static String dateOnly(String s)
    {
        if (isBlank(s)) return "";
        String t = s.trim();
        int tIdx = t.indexOf('T');
        return tIdx > 0 ? t.substring(0, tIdx) : t;
    }

    private static String sanitize(String s)
    {
        if (s == null) return "";
        return s.trim().replaceAll("[^A-Za-z0-9._-]", "");
    }

    private static String firstNonBlank(String... vals)
    {
        if (vals == null) return "";
        for (String v : vals) { if (!isBlank(v)) return v; }
        return "";
    }

    private static boolean isBlank(String s)
    {
        return s == null || s.trim().isEmpty();
    }
}
