package com.liminer.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class InteractionRecord
{
    // Keys for the "Full Interaction Record" wrapper stored in the CRM cell:
    //   { "asOfDate": "<last processed date>", "records": [ {record}, ... ] }
    public static final String RECORDS_KEY = "records";
    public static final String AS_OF_DATE_KEY = "asOfDate";

    // Actual interaction date (when the email was sent). The processing/"as of"
    // date now lives on the wrapper object (see AS_OF_DATE_KEY), not per record.
    public String date;
    public String direction;
    public String type;
    public String oneSentenceSummary;
    public String conversationLabel;
    public List<String> keyTopicsDiscussed;
    public List<String> lpQuestionsAsked;
    // Used by InvestorBriefs to surface outstanding GP commitments before a meeting
    public List<String> commitmentsMadeByGP;
    public String lpSentiment;
    public List<String> relationshipSignals;

    public InteractionRecord()
    {
        this.date = "";
        this.direction = "";
        this.type = "EMAIL";
        this.oneSentenceSummary = "";
        this.conversationLabel = "";
        this.keyTopicsDiscussed = new ArrayList<>();
        this.lpQuestionsAsked = new ArrayList<>();
        this.commitmentsMadeByGP = new ArrayList<>();
        this.lpSentiment = "";
        this.relationshipSignals = new ArrayList<>();
    }

    public JSONObject toJSON()
    {
        JSONObject obj = new JSONObject();
        obj.put("date", date == null ? "" : date);
        obj.put("direction", direction == null ? "" : direction);
        obj.put("type", type == null ? "EMAIL" : type);
        obj.put("oneSentenceSummary", oneSentenceSummary == null ? "" : oneSentenceSummary);
        obj.put("conversationLabel", conversationLabel == null ? "" : conversationLabel);
        obj.put("keyTopicsDiscussed", toJSONArray(keyTopicsDiscussed));
        obj.put("lpQuestionsAsked", toJSONArray(lpQuestionsAsked));
        obj.put("commitmentsMadeByGP", toJSONArray(commitmentsMadeByGP));
        obj.put("lpSentiment", lpSentiment == null ? "" : lpSentiment);
        obj.put("relationshipSignals", toJSONArray(relationshipSignals));
        return obj;
    }

    public static InteractionRecord fromJSON(JSONObject obj)
    {
        InteractionRecord record = new InteractionRecord();
        record.date = obj.optString("date", "");
        record.direction = obj.optString("direction", "");
        record.type = obj.optString("type", "EMAIL");
        record.oneSentenceSummary = obj.optString("oneSentenceSummary", "");
        record.conversationLabel = obj.optString("conversationLabel", "");
        record.keyTopicsDiscussed = parseStringList(obj.optJSONArray("keyTopicsDiscussed"));
        record.lpQuestionsAsked = parseStringList(obj.optJSONArray("lpQuestionsAsked"));
        record.commitmentsMadeByGP = parseStringList(obj.optJSONArray("commitmentsMadeByGP"));
        record.lpSentiment = obj.optString("lpSentiment", "");
        record.relationshipSignals = parseStringList(obj.optJSONArray("relationshipSignals"));
        return record;
    }

    public static InteractionRecord fallback(String rawText, String conversationLabel)
    {
        InteractionRecord record = new InteractionRecord();
        record.oneSentenceSummary = rawText == null ? "" : rawText.trim();
        record.conversationLabel = conversationLabel == null ? "" : conversationLabel;
        return record;
    }

    // Tolerantly extract the records array from a stored CRM cell. Accepts both
    // the new wrapper object { asOfDate, records:[...] } and the legacy bare
    // array [...]. Blank or unparseable input yields an empty array.
    public static JSONArray extractRecordsArray(String stored)
    {
        if (stored == null)
        {
            return new JSONArray();
        }

        String trimmed = stored.trim();

        if (trimmed.isEmpty())
        {
            return new JSONArray();
        }

        try
        {
            if (trimmed.startsWith("{"))
            {
                JSONArray records = new JSONObject(trimmed).optJSONArray(RECORDS_KEY);
                return records == null ? new JSONArray() : records;
            }

            if (trimmed.startsWith("["))
            {
                return new JSONArray(trimmed);
            }
        }
        catch (Exception e)
        {
            // fall through to empty
        }

        return new JSONArray();
    }

    // Build the "Full Interaction Record" wrapper object stored in the CRM cell.
    public static JSONObject buildWrapper(JSONArray records, String asOfDate)
    {
        JSONObject wrapper = new JSONObject();
        wrapper.put(AS_OF_DATE_KEY, asOfDate == null ? "" : asOfDate);
        wrapper.put(RECORDS_KEY, records == null ? new JSONArray() : records);
        return wrapper;
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
        if (arr == null)
        {
            return list;
        }
        for (int i = 0; i < arr.length(); i++)
        {
            list.add(arr.optString(i, ""));
        }
        return list;
    }
}
