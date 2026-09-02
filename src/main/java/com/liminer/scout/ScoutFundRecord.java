package com.liminer.scout;

import org.json.JSONObject;

/*
 * ScoutFundRecord — one Schedule D Section 7.B.1 private fund entry reported by
 * a registered investment adviser (fund name, fund type, gross asset value,
 * beneficial-owner count). Nested inside ScoutUniverseRecord.funds.
 */
public class ScoutFundRecord
{
    public String name;
    public String type;
    public double grossAssetValue;
    public int ownerCount;

    // Optional, nullable Schedule D 7.B.1 fields (see ExtraDocuments/adv-column-audit.md).
    // UNVERIFIED header names against the real bulk CSV layout; resolved via optional
    // (non-throwing) header lookup, so these stay null when the source column is absent.
    // fundCik/formDFileNumber key a Form D join without a fuzzy name match; masterFeederFlag
    // and pctOwnedByFundOfFunds are dedupe/allocator-fit signals for a later task.
    public String fundCik;
    public String formDFileNumber;
    public String masterFeederFlag;
    public Double minimumInvestment;
    public Double pctOwnedByFundOfFunds;

    public ScoutFundRecord()
    {
        name = "";
        type = "";
        grossAssetValue = 0.0;
        ownerCount = 0;
        fundCik = "";
        formDFileNumber = "";
        masterFeederFlag = "";
        minimumInvestment = null;
        pctOwnedByFundOfFunds = null;
    }

    public JSONObject toJson()
    {
        JSONObject o0 = new JSONObject();
        o0.put("name", safe(name));
        o0.put("type", safe(type));
        o0.put("grossAssetValue", grossAssetValue);
        o0.put("ownerCount", ownerCount);
        o0.put("fundCik", safe(fundCik));
        o0.put("formDFileNumber", safe(formDFileNumber));
        o0.put("masterFeederFlag", safe(masterFeederFlag));
        o0.put("minimumInvestment", minimumInvestment == null ? JSONObject.NULL : minimumInvestment);
        o0.put("pctOwnedByFundOfFunds", pctOwnedByFundOfFunds == null ? JSONObject.NULL : pctOwnedByFundOfFunds);
        return o0;
    }

    public static ScoutFundRecord fromJson(JSONObject o0)
    {
        ScoutFundRecord f0 = new ScoutFundRecord();
        if (o0 == null) return f0;
        f0.name = o0.optString("name", "");
        f0.type = o0.optString("type", "");
        f0.grossAssetValue = o0.optDouble("grossAssetValue", 0.0);
        f0.ownerCount = o0.optInt("ownerCount", 0);
        f0.fundCik = o0.optString("fundCik", "");
        f0.formDFileNumber = o0.optString("formDFileNumber", "");
        f0.masterFeederFlag = o0.optString("masterFeederFlag", "");
        f0.minimumInvestment = o0.isNull("minimumInvestment") || !o0.has("minimumInvestment")
            ? null : o0.optDouble("minimumInvestment");
        f0.pctOwnedByFundOfFunds = o0.isNull("pctOwnedByFundOfFunds") || !o0.has("pctOwnedByFundOfFunds")
            ? null : o0.optDouble("pctOwnedByFundOfFunds");
        return f0;
    }

    private static String safe(String s0) { return s0 == null ? "" : s0; }
}
