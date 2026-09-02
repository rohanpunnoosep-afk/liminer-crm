package com.liminer.scout;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * ScoutSignalScore — output of ScoutSignalScorer.score(): two strictly-separate
 * 0-100 axes (RESOURCES, PROBABILITY_NOW — never multiplied or averaged into one
 * number) plus human-readable reasons for the "why now" CRM column. crd is
 * populated by the scorer for cache round-trips; it is not an input to the pure
 * scoring math.
 */
public class ScoutSignalScore
{
    public int crd;
    public int resources;
    public int probabilityNow;
    public List<String> reasons;

    public ScoutSignalScore()
    {
        crd = 0;
        resources = 0;
        probabilityNow = 0;
        reasons = new ArrayList<String>();
    }

    public JSONObject toJson()
    {
        JSONObject o0 = new JSONObject();
        o0.put("crd", crd);
        o0.put("resources", resources);
        o0.put("probabilityNow", probabilityNow);
        o0.put("reasons", new JSONArray(reasons == null ? new ArrayList<String>() : reasons));
        return o0;
    }

    public static ScoutSignalScore fromJson(JSONObject o0)
    {
        ScoutSignalScore s0 = new ScoutSignalScore();
        if (o0 == null) return s0;

        s0.crd = o0.optInt("crd", 0);
        s0.resources = o0.optInt("resources", 0);
        s0.probabilityNow = o0.optInt("probabilityNow", 0);

        s0.reasons = new ArrayList<String>();
        JSONArray arr0 = o0.optJSONArray("reasons");
        if (arr0 != null)
        {
            for (int i0 = 0; i0 < arr0.length(); i0++) s0.reasons.add(arr0.optString(i0, ""));
        }
        return s0;
    }
}
