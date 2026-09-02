package com.liminer.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/*
 * RelationshipSummary — the output of the Relationship Summary workflow. It is a
 * plain data holder (mirrors InvestorProfile / IndicatorResult) carrying the four
 * relationship data points the LLM extracts from an LP's full interaction history,
 * plus the analysis date, a serialized JSON blob, and a run status.
 *
 * Decoupled from market intelligence on purpose: the GP<->LP relationship is a
 * fast-moving signal and is summarized on its own cadence.
 */
public class RelationshipSummary
{
    // Status values written to the Relationship Summary Status column.
    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_FAILED   = "FAILED";

    public List<String> aggregatedInterests;        // themes the LP raised across conversations
    public String       sentimentChangesOverTime;   // improving|flat|cooling narrative + reasoning
    public String       narrativeArc;               // how it started -> now, key moments
    public List<String> outstandingCommitments;     // flagged unresolved GP commitments
    public String       analysisDate;               // ISO date the summary was generated
    public String       summaryJson;                // full JSON of all fields incl. analysisDate
    public String       status;                     // COMPLETE | FAILED

    public RelationshipSummary()
    {
        this.aggregatedInterests = new ArrayList<>();
        this.sentimentChangesOverTime = "";
        this.narrativeArc = "";
        this.outstandingCommitments = new ArrayList<>();
        this.analysisDate = LocalDate.now().toString();
        this.summaryJson = "{}";
        this.status = STATUS_FAILED;
    }

    // Full JSON of every field, including analysisDate, for the JSON column.
    public JSONObject toJSON()
    {
        JSONObject obj = new JSONObject();
        obj.put("aggregatedInterests", toJSONArray(aggregatedInterests));
        obj.put("sentimentChangesOverTime", safe(sentimentChangesOverTime));
        obj.put("narrativeArc", safe(narrativeArc));
        obj.put("outstandingCommitments", toJSONArray(outstandingCommitments));
        obj.put("analysisDate", safe(analysisDate));
        return obj;
    }

    // Parses the LLM response object (keys: aggregatedInterests, sentimentChangesOverTime,
    // narrativeArc, outstandingCommitments). analysisDate/status/summaryJson are set by the
    // caller after a successful parse.
    public static RelationshipSummary fromOpenAiJson(JSONObject obj)
    {
        RelationshipSummary s = new RelationshipSummary();
        if (obj == null) return s;
        s.aggregatedInterests = parseStringList(obj.optJSONArray("aggregatedInterests"));
        s.sentimentChangesOverTime = obj.optString("sentimentChangesOverTime", "");
        s.narrativeArc = obj.optString("narrativeArc", "");
        s.outstandingCommitments = parseStringList(obj.optJSONArray("outstandingCommitments"));
        return s;
    }

    // Convenience for the text columns: join a list into a readable bullet string.
    public static String joinList(List<String> list)
    {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++)
        {
            String item = list.get(i);
            if (item == null || item.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append("- ").append(item.trim());
        }
        return sb.toString();
    }

    private static JSONArray toJSONArray(List<String> list)
    {
        JSONArray arr = new JSONArray();
        if (list != null)
        {
            for (String item : list)
            {
                arr.put(item == null ? "" : item);
            }
        }
        return arr;
    }

    private static List<String> parseStringList(JSONArray arr)
    {
        List<String> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++)
        {
            String v = arr.optString(i, "");
            if (v != null && !v.trim().isEmpty()) list.add(v.trim());
        }
        return list;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
