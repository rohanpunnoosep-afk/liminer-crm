package com.liminer.embed;

import com.liminer.llm.OpenAIClient;
import com.liminer.pipeline.InvestorProfileExtractor;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * CanonicalProfileBuilder turns the prose and JSON already persisted in the CRM into a
 * CanonicalProfile via one LLM call. The LLM sits behind LlmSeam so this can be tested
 * fully offline: production code uses OpenAIClient.getTextResponse, tests inject a stub.
 *
 * On any parse failure this returns an empty CanonicalProfile rather than throwing,
 * matching how InvestorProfileExtractor.buildFailureJson degrades on bad LLM output.
 */
public class CanonicalProfileBuilder
{
    public interface LlmSeam
    {
        String complete(String prompt0) throws Exception;
    }

    // Raw CRM values the caller (task 0176) populates from column reads. Plain public
    // fields, matching the data-holder convention used across the codebase.
    public static class Input
    {
        public String investmentThesis;
        public String intelligenceJsonThesisSummary;
        public String fundLinkedInAbout;

        public String priorBackedFunds;
        public String intelligenceJsonPriorBackedFunds;

        public String sectorTags;
        public String microsectorTags;
        public String intelligenceJsonSectorFocus;
        public String intelligenceJsonMicrosectorFocus;

        public String marketIntelligenceJson;
        public String intelligenceJson;

        public String typeOfInvestor;
        public String intelligenceJsonAllocatorType;
    }

    private final LlmSeam llmSeam;

    public CanonicalProfileBuilder()
    {
        this(new LlmSeam()
        {
            @Override
            public String complete(String prompt0) throws Exception
            {
                return OpenAIClient.getTextResponse(prompt0);
            }
        });
    }

    public CanonicalProfileBuilder(LlmSeam llmSeam0)
    {
        llmSeam = llmSeam0;
    }

    public CanonicalProfile build(Input input0) throws Exception
    {
        String prompt0 = buildPrompt(input0);
        String response0 = llmSeam.complete(prompt0);

        JSONObject parsed0;

        try
        {
            parsed0 = InvestorProfileExtractor.parseJsonObjectFromText(response0);
        }
        catch (Exception exception0)
        {
            CanonicalProfile empty0 = new CanonicalProfile();
            empty0.canonicalize();
            return empty0;
        }

        CanonicalProfile profile0 = new CanonicalProfile();

        profile0.thesis = optStringList(parsed0, "thesis");
        profile0.pastInvestments = optStringList(parsed0, "pastInvestments");
        profile0.newInvestmentAreas = optStringList(parsed0, "newInvestmentAreas");
        profile0.allocatorType = parsed0.optString("allocatorType", "");
        profile0.aumUsd = optNullableDouble(parsed0, "aumUsd");
        profile0.capitalAllocatableUsd = optNullableDouble(parsed0, "capitalAllocatableUsd");
        profile0.pastInvestmentAmountUsd = optNullableDouble(parsed0, "pastInvestmentAmountUsd");
        profile0.timingMonthsSinceLastClose = optNullableDouble(parsed0, "timingMonthsSinceLastClose");

        profile0.canonicalize();
        return profile0;
    }

    private static List<String> optStringList(JSONObject o0, String key0)
    {
        List<String> list0 = new ArrayList<String>();
        JSONArray arr0 = o0.optJSONArray(key0);

        if (arr0 != null)
        {
            for (int i0 = 0; i0 < arr0.length(); i0++)
            {
                list0.add(arr0.optString(i0, ""));
            }
        }

        return list0;
    }

    private static double optNullableDouble(JSONObject o0, String key0)
    {
        if (!o0.has(key0) || o0.isNull(key0))
        {
            return Double.NaN;
        }

        return o0.optDouble(key0, Double.NaN);
    }

    private static String buildPrompt(Input input0)
    {
        StringBuilder sb0 = new StringBuilder();

        sb0.append("You canonicalize noisy fundraising-CRM data about a single LP (limited partner / ")
            .append("allocator) into a closed-vocabulary fact form. Follow these three rules exactly:\n\n");

        sb0.append("1. CLOSED VOCABULARY: allocatorType must be exactly one of these nine values, ")
            .append("or the empty string if none clearly applies:\n")
            .append(String.join(", ", ProfileVectorLayout.ALLOCATOR_TYPE_ORDER)).append("\n\n");

        sb0.append("2. ATOMIC FACTS IN FIXED TEMPLATES, NEVER ADJECTIVES: every thesis, ")
            .append("pastInvestments and newInvestmentAreas entry must be a short factual atom, e.g. ")
            .append("'backed <fund> | <sector> | <stage> | <geo>'. Never write marketing language, ")
            .append("adjectives, or subjective descriptions like 'a thoughtful long-horizon partner'.\n\n");

        sb0.append("3. Do not worry about ordering or duplicates in your answer; list every distinct ")
            .append("atom you can support from the evidence exactly once.\n\n");

        sb0.append("For any numeric field you cannot determine from the evidence, return JSON null -- ")
            .append("never 0. Numbers must be plain USD amounts (no currency symbols, no abbreviations ")
            .append("like '1.2M'). timingMonthsSinceLastClose is the number of months since the fund's ")
            .append("last close, or null if unknown.\n\n");

        sb0.append("Do NOT include anything derived from contact/interaction history -- no meeting ")
            .append("counts, sentiment, or conversation status. Only facts about the LP itself.\n\n");

        sb0.append("Return exactly this JSON shape and nothing else (no markdown fences, no prose):\n");
        sb0.append("{\n")
            .append("  \"thesis\": [\"...\"],\n")
            .append("  \"pastInvestments\": [\"...\"],\n")
            .append("  \"newInvestmentAreas\": [\"...\"],\n")
            .append("  \"allocatorType\": \"...\",\n")
            .append("  \"aumUsd\": null,\n")
            .append("  \"capitalAllocatableUsd\": null,\n")
            .append("  \"pastInvestmentAmountUsd\": null,\n")
            .append("  \"timingMonthsSinceLastClose\": null\n")
            .append("}\n\n");

        sb0.append("Evidence:\n\n");
        appendField(sb0, "Investment Thesis", input0.investmentThesis);
        appendField(sb0, "Intelligence JSON investment_thesis.summary", input0.intelligenceJsonThesisSummary);
        appendField(sb0, "Fund LinkedIn About", input0.fundLinkedInAbout);
        appendField(sb0, "Prior Backed Funds", input0.priorBackedFunds);
        appendField(sb0, "Intelligence JSON prior_relationships.prior_backed_funds", input0.intelligenceJsonPriorBackedFunds);
        appendField(sb0, "Sector Tags", input0.sectorTags);
        appendField(sb0, "Microsector Tags", input0.microsectorTags);
        appendField(sb0, "Intelligence JSON sector_focus", input0.intelligenceJsonSectorFocus);
        appendField(sb0, "Intelligence JSON microsector_focus", input0.intelligenceJsonMicrosectorFocus);
        appendField(sb0, "Market Intelligence JSON", input0.marketIntelligenceJson);
        appendField(sb0, "Intelligence JSON", input0.intelligenceJson);
        appendField(sb0, "Type of Investor", input0.typeOfInvestor);
        appendField(sb0, "Intelligence JSON allocator_profile.allocator_type", input0.intelligenceJsonAllocatorType);

        return sb0.toString();
    }

    private static void appendField(StringBuilder sb0, String label0, String value0)
    {
        if (value0 == null || value0.trim().isEmpty())
        {
            return;
        }

        sb0.append(label0).append(": ").append(value0.trim()).append("\n\n");
    }
}
