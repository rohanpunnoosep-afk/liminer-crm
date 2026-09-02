package com.liminer.core;

import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * InvestorProfile is the reusable structured profile object for LP intelligence.
 *
 * This object is intentionally simple and public-field based because the project
 * is moving quickly. It is used in two places:
 *
 * 1. LPEnrichmentProcessor:
 *    website -> InvestorProfile -> CRM columns + Intelligence JSON
 *
 * 2. Pipeline 3:
 *    candidate website -> InvestorProfile -> similarity scoring
 */
public class InvestorProfile
{
    public String fundName;
    public String allocatorType;
    public String[] sectors;
    public String[] microsectors;
    public String[] geographies;
    public String[] priorBackedFunds;
    public String investmentThesis;

    /*
     * Keep the full JSON because LPEnrichmentProcessor still needs to write the
     * complete intelligence object back into the CRM.
     */
    public String intelligenceJson;

    public InvestorProfile()
    {
        fundName = "";
        allocatorType = "";
        sectors = new String[0];
        microsectors = new String[0];
        geographies = new String[0];
        priorBackedFunds = new String[0];
        investmentThesis = "";
        intelligenceJson = "";
    }

    public InvestorProfile(
        String fundName0,
        String allocatorType0,
        String[] sectors0,
        String[] microsectors0,
        String[] geographies0,
        String[] priorBackedFunds0,
        String investmentThesis0,
        String intelligenceJson0)
    {
        fundName = safeString(fundName0);
        allocatorType = safeString(allocatorType0);
        sectors = safeArray(sectors0);
        microsectors = safeArray(microsectors0);
        geographies = safeArray(geographies0);
        priorBackedFunds = safeArray(priorBackedFunds0);
        investmentThesis = safeString(investmentThesis0);
        intelligenceJson = safeString(intelligenceJson0);
    }

    /*
     * Creates an InvestorProfile from the exact JSON structure produced by the
     * LP intelligence OpenAI prompt.
     */
    public static InvestorProfile fromIntelligenceJson(JSONObject intelligenceJson0)
    {
        InvestorProfile profile0 = new InvestorProfile();

        if (intelligenceJson0 == null)
        {
            return profile0;
        }

        profile0.allocatorType = extractAllocatorType(intelligenceJson0);
        profile0.sectors = extractSectorArray(intelligenceJson0);
        profile0.microsectors = extractMicrosectorArray(intelligenceJson0);
        profile0.geographies = extractGeographyArray(intelligenceJson0);
        profile0.priorBackedFunds = extractPriorBackedFundsArray(intelligenceJson0);
        profile0.investmentThesis = extractInvestmentThesis(intelligenceJson0);
        profile0.fundName = extractFundName(intelligenceJson0);
        profile0.intelligenceJson = intelligenceJson0.toString();

        return profile0;
    }

    public static InvestorProfile fromIntelligenceJsonString(String intelligenceJsonText0)
    {
        if (isBlank(intelligenceJsonText0))
        {
            return new InvestorProfile();
        }

        return fromIntelligenceJson(new JSONObject(intelligenceJsonText0));
    }

    public static String extractAllocatorType(JSONObject intelligenceJson0)
    {
        JSONObject allocatorProfile0 = intelligenceJson0.optJSONObject("allocator_profile");
        if (allocatorProfile0 == null) return "";

        JSONObject allocatorType0 = allocatorProfile0.optJSONObject("allocator_type");
        if (allocatorType0 == null) return "";

        return allocatorType0.optString("value", "");
    }

    public static String[] extractSectorArray(JSONObject intelligenceJson0)
    {
        JSONObject sectorFocus0 = intelligenceJson0.optJSONObject("sector_focus");
        if (sectorFocus0 == null) return new String[0];

        return jsonArrayObjectsToStringArray(
            sectorFocus0.optJSONArray("sector_tags"),
            "value"
        );
    }

    public static String[] extractMicrosectorArray(JSONObject intelligenceJson0)
    {
        JSONObject microsectorFocus0 = intelligenceJson0.optJSONObject("microsector_focus");
        if (microsectorFocus0 == null) return new String[0];

        return jsonArrayObjectsToStringArray(
            microsectorFocus0.optJSONArray("microsector_tags"),
            "value"
        );
    }

    public static String[] extractGeographyArray(JSONObject intelligenceJson0)
    {
        JSONObject geography0 = intelligenceJson0.optJSONObject("geography");
        if (geography0 == null) return new String[0];

        return jsonArrayObjectsToStringArray(
            geography0.optJSONArray("locations"),
            "value"
        );
    }

    public static String[] extractPriorBackedFundsArray(JSONObject intelligenceJson0)
    {
        JSONObject priorRelationships0 = intelligenceJson0.optJSONObject("prior_relationships");
        if (priorRelationships0 == null) return new String[0];

        return jsonArrayObjectsToStringArray(
            priorRelationships0.optJSONArray("prior_backed_funds"),
            "name"
        );
    }

    public static String extractInvestmentThesis(JSONObject intelligenceJson0)
    {
        JSONObject investmentThesis0 = intelligenceJson0.optJSONObject("investment_thesis");

        if (investmentThesis0 == null)
        {
            return "";
        }

        return investmentThesis0.optString("summary", "");
    }

    public static String extractFundName(JSONObject intelligenceJson0)
    {
        JSONObject allocatorProfile0 = intelligenceJson0.optJSONObject("allocator_profile");
        if (allocatorProfile0 == null) return "";

        JSONObject fundName0 = allocatorProfile0.optJSONObject("fund_name");
        if (fundName0 == null) return "";

        return fundName0.optString("value", "");
    }

    public static String joinWithPipe(String[] values0)
    {
        if (values0 == null || values0.length == 0)
        {
            return "";
        }

        String result0 = "";

        for (int i = 0; i < values0.length; i++)
        {
            String value0 = values0[i] == null ? "" : values0[i].trim();

            if (value0.length() == 0)
            {
                continue;
            }

            if (result0.length() > 0)
            {
                result0 += "|";
            }

            result0 += value0;
        }

        return result0;
    }

    private static String[] jsonArrayObjectsToStringArray(JSONArray array0, String key0)
    {
        if (array0 == null)
        {
            return new String[0];
        }

        java.util.ArrayList<String> values0 = new java.util.ArrayList<String>();

        for (int i = 0; i < array0.length(); i++)
        {
            JSONObject object0 = array0.optJSONObject(i);

            if (object0 == null)
            {
                continue;
            }

            String value0 = object0.optString(key0, "").trim();

            if (!isBlank(value0))
            {
                values0.add(value0);
            }
        }

        return values0.toArray(new String[0]);
    }

    private static String[] safeArray(String[] values0)
    {
        return values0 == null ? new String[0] : values0;
    }

    private static String safeString(String value0)
    {
        return value0 == null ? "" : value0;
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }

    public void printSummary()
    {
        System.out.println("===== INVESTOR PROFILE =====");
        System.out.println("Fund Name: " + fundName);
        System.out.println("Allocator Type: " + allocatorType);
        System.out.println("Sectors: " + Arrays.toString(sectors));
        System.out.println("Microsectors: " + Arrays.toString(microsectors));
        System.out.println("Geographies: " + Arrays.toString(geographies));
        System.out.println("Prior Backed Funds: " + Arrays.toString(priorBackedFunds));
        System.out.println("Investment Thesis: " + investmentThesis);
        System.out.println("Intelligence JSON: " + intelligenceJson);
    }
}
