package com.liminer.scout;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * ScoutScoredRecord — wrapper POJO produced by ScoutUniverseIndexer: one
 * ScoutUniverseRecord plus its deterministic, client-independent tags,
 * cached ScoutSignalScorer resources/probabilityNow (nullable when no cached
 * score exists for this crd), and a list of named client-independent
 * exclusion gates it failed. Per the task's TAG, DON'T DROP rule, a record
 * with a non-empty exclusions list is still present here -- exclusions are
 * metadata, never a reason to omit the record from the full scored file.
 * Wraps the record rather than widening ScoutUniverseRecord so the raw ADV
 * snapshot schema (FileScoutUniverseStore) stays untouched.
 */
public class ScoutScoredRecord
{
    public ScoutUniverseRecord record;
    public List<String> tags;
    public List<String> exclusions;
    public Integer resources;
    public Integer probabilityNow;

    public ScoutScoredRecord()
    {
        record = new ScoutUniverseRecord();
        tags = new ArrayList<String>();
        exclusions = new ArrayList<String>();
        resources = null;
        probabilityNow = null;
    }

    public boolean isHotEligible(int hotResourcesFloor0, int hotProbabilityNowFloor0)
    {
        if (exclusions != null && !exclusions.isEmpty()) return false;
        if (resources == null || probabilityNow == null) return false;
        return resources >= hotResourcesFloor0 && probabilityNow >= hotProbabilityNowFloor0;
    }

    public JSONObject toJson()
    {
        JSONObject o0 = new JSONObject();
        o0.put("record", record == null ? new JSONObject() : record.toJson());
        o0.put("tags", new JSONArray(tags == null ? new ArrayList<String>() : tags));
        o0.put("exclusions", new JSONArray(exclusions == null ? new ArrayList<String>() : exclusions));
        o0.put("resources", resources == null ? JSONObject.NULL : resources);
        o0.put("probabilityNow", probabilityNow == null ? JSONObject.NULL : probabilityNow);
        return o0;
    }

    public static ScoutScoredRecord fromJson(JSONObject o0)
    {
        ScoutScoredRecord s0 = new ScoutScoredRecord();
        if (o0 == null) return s0;

        s0.record = ScoutUniverseRecord.fromJson(o0.optJSONObject("record"));

        s0.tags = new ArrayList<String>();
        JSONArray tagsJson0 = o0.optJSONArray("tags");
        if (tagsJson0 != null)
        {
            for (int i0 = 0; i0 < tagsJson0.length(); i0++) s0.tags.add(tagsJson0.optString(i0, ""));
        }

        s0.exclusions = new ArrayList<String>();
        JSONArray exclusionsJson0 = o0.optJSONArray("exclusions");
        if (exclusionsJson0 != null)
        {
            for (int i0 = 0; i0 < exclusionsJson0.length(); i0++) s0.exclusions.add(exclusionsJson0.optString(i0, ""));
        }

        s0.resources = o0.isNull("resources") || !o0.has("resources") ? null : o0.optInt("resources");
        s0.probabilityNow = o0.isNull("probabilityNow") || !o0.has("probabilityNow") ? null : o0.optInt("probabilityNow");

        return s0;
    }
}
