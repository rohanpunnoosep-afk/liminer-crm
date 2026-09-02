package com.liminer.brief;

import com.liminer.pipeline.InvestorProfileExtractor;

import org.json.JSONArray;
import org.json.JSONObject;

/*
 * InvestorBriefJson — the output of the Investor Brief assembly workflow. It is a
 * plain data holder (mirrors RelationshipSummary / InvestorProfile) that collects the
 * scattered outputs of the four upstream LP-enrichment workflows into one coherent,
 * per-LP intelligence package a rendering layer can turn into a meeting brief with no
 * further computation.
 *
 * The brief carries four assembled sections plus two GPT synthesis outputs:
 *   - contactAndFirmProfile : identity + enrichment + background-check bio/career
 *   - marketIntelligence    : scores, identity keys, the MI JSON blob, and the
 *                             GPT-inferred fundingStatus (hoisted to top level)
 *   - relationshipSummary   : interests / sentiment / arc + a scannable
 *                             outstandingCommitments checklist array
 *   - callPreparation       : GPT pass-1 strategy synthesis
 *   - executiveSummary      : GPT pass-2 5-7 sentence paragraph
 *
 * A blank Last Brief Generated date is the "not yet briefed" signal that drives
 * eligibility and re-runs (same idea as Relationship Summary's blank-date gate).
 */
public class InvestorBriefJson
{
    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_FAILED   = "FAILED";

    public String     asOfDate;               // ISO instant the brief was generated
    public JSONObject contactAndFirmProfile;  // identity + enrichment + bio
    public JSONObject marketIntelligence;     // scores + identity + MI blob + fundingStatus
    public JSONObject relationshipSummary;    // interests / sentiment / arc / commitments
    public JSONObject callPreparation;        // GPT pass-1 strategy synthesis
    public String     executiveSummary;       // GPT pass-2 paragraph
    public String     status;                 // COMPLETE | FAILED
    public String     briefJson;              // full serialized brief (truncated)

    public InvestorBriefJson()
    {
        this.asOfDate = "";
        this.contactAndFirmProfile = new JSONObject();
        this.marketIntelligence = new JSONObject();
        this.relationshipSummary = new JSONObject();
        this.callPreparation = new JSONObject();
        this.executiveSummary = "";
        this.status = STATUS_FAILED;
        this.briefJson = "{}";
    }

    // Assemble the full brief JSON. Keys are written in the documented order
    // (org.json does not guarantee key order, but consumers parse by key).
    public JSONObject toJSON()
    {
        JSONObject obj = new JSONObject();
        obj.put("asOfDate", safe(asOfDate));
        obj.put("contactAndFirmProfile", contactAndFirmProfile == null ? new JSONObject() : contactAndFirmProfile);
        obj.put("marketIntelligence", marketIntelligence == null ? new JSONObject() : marketIntelligence);
        obj.put("relationshipSummary", relationshipSummary == null ? new JSONObject() : relationshipSummary);
        obj.put("callPreparation", callPreparation == null ? new JSONObject() : callPreparation);
        obj.put("executiveSummary", safe(executiveSummary));
        return obj;
    }

    // -----------------------------------------------------------------------
    // Parsing helpers (shared by the processor)
    // -----------------------------------------------------------------------

    // Split a pipe-delimited cell ("a | b | c") into a JSONArray of trimmed,
    // non-empty items.
    public static JSONArray pipeToJsonArray(String s)
    {
        JSONArray arr = new JSONArray();
        if (s == null) return arr;
        for (String part : s.split("\\|"))
        {
            if (part != null && !part.trim().isEmpty()) arr.put(part.trim());
        }
        return arr;
    }

    // Split a newline-delimited list cell (as written by RelationshipSummary.joinList,
    // each line prefixed with "- ") into a JSONArray.
    public static JSONArray newlineToJsonArray(String s)
    {
        JSONArray arr = new JSONArray();
        if (s == null) return arr;
        for (String line : s.split("\\r?\\n"))
        {
            if (line == null) continue;
            String item = line.trim();
            if (item.startsWith("- ")) item = item.substring(2).trim();
            if (!item.isEmpty()) arr.put(item);
        }
        return arr;
    }

    // Safely parse a stored blob string into a JSONObject/JSONArray. Already-structured
    // blobs (written by upstream processors) parse with plain new JSONObject/JSONArray;
    // LLM-authored object blobs fall back to InvestorProfileExtractor's tolerant parser.
    // Returns the raw trimmed string when the value is not JSON at all.
    public static Object parseBlob(String s)
    {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";
        if (t.startsWith("["))
        {
            try { return new JSONArray(t); } catch (Exception ignore) { }
        }
        if (t.startsWith("{"))
        {
            try { return new JSONObject(t); } catch (Exception ignore) { }
            try
            {
                JSONObject parsed = InvestorProfileExtractor.parseJsonObjectFromText(t);
                if (parsed != null) return parsed;
            }
            catch (Exception ignore) { }
        }
        return t;
    }

    // Parse a blob that is expected to be a JSONObject; returns an empty object on failure.
    public static JSONObject parseBlobObject(String s)
    {
        Object o = parseBlob(s);
        return (o instanceof JSONObject) ? (JSONObject) o : new JSONObject();
    }

    // Parse a numeric cell into a double, or null when blank/unparseable so the caller
    // can decide whether to store a number or omit the key.
    public static Double parseNumber(String s)
    {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try { return Double.valueOf(t); } catch (Exception e) { return null; }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
