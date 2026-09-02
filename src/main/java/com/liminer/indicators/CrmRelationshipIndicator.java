package com.liminer.indicators;

import com.liminer.core.InteractionRecord;
import com.liminer.core.LpContext;
import com.liminer.enrich.ScrapeCache;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.List;

/*
 * CrmRelationshipIndicator (Fit 2A) — the highest value-per-dollar leaf in the
 * tree. A prior tie in the GP's OWN CRM dominates every external prior (V10). Zero
 * external calls: a local join over the interaction history already loaded into
 * LpContext (interaction records JSON, interaction history text, conversation
 * status, last-contact date). Fully thread-safe (read-only, no I/O).
 *
 * Tie strength ladder (commitment > meeting > interest > cold > passed):
 *   - a GP commitment or a near-close conversation  -> highest confidence
 *   - a prior meeting / diligence discussion        -> high
 *   - a warm relationship signal / early interest   -> medium
 *   - cold outreach / generic prior contact         -> low
 *   - a prior pass / rejection                       -> low (known but negative)
 * asOfDate is the date of the MOST RECENT interaction (point-in-time honesty).
 * No tie -> IndicatorResult.empty(FIT).
 */
public class CrmRelationshipIndicator implements Indicator
{
    @Override
    public String axis() { return AXIS_FIT; }

    @Override
    public String name() { return "CrmRelationship"; }

    @Override
    public IndicatorResult fetch(LpContext ctx, ScrapeCache cache)
    {
        if (ctx == null) return IndicatorResult.empty(AXIS_FIT);

        double bestConfidence0 = 0.0;
        String bestTie0 = "";
        String mostRecentDate0 = "";
        int interactionCount0 = 0;

        // Primary source: structured interaction records.
        JSONArray records0 = parseArray(ctx.interactionRecordsJson);
        if (records0 != null)
        {
            for (int i = 0; i < records0.length(); i++)
            {
                JSONObject obj0 = records0.optJSONObject(i);
                if (obj0 == null) continue;

                InteractionRecord rec0 = InteractionRecord.fromJSON(obj0);
                interactionCount0++;

                double strength0 = strengthOf(rec0);
                String label0 = tieLabel(rec0);
                if (strength0 > bestConfidence0)
                {
                    bestConfidence0 = strength0;
                    bestTie0 = label0;
                }
                mostRecentDate0 = laterDate(mostRecentDate0, safe(rec0.date));
            }
        }

        // Fallback: unstructured history / status when there are no structured records.
        if (interactionCount0 == 0)
        {
            boolean hasHistory0 = !isBlank(ctx.interactionHistory)
                || !isBlank(ctx.conversationStatus)
                || !isBlank(ctx.lastContactDate);
            if (!hasHistory0)
            {
                return IndicatorResult.empty(AXIS_FIT);
            }
            bestConfidence0 = statusStrength(ctx.conversationStatus);
            bestTie0 = isBlank(ctx.conversationStatus)
                ? "Prior contact on record" : ("Prior contact (" + ctx.conversationStatus.trim() + ")");
            mostRecentDate0 = laterDate(mostRecentDate0, safe(ctx.lastContactDate));
        }

        if (bestConfidence0 <= 0.0)
        {
            return IndicatorResult.empty(AXIS_FIT);
        }

        String asOf0 = isBlank(mostRecentDate0) ? LocalDate.now().toString() : mostRecentDate0;
        String value0 = "CRM tie: " + bestTie0 + "; " + interactionCountLabel(interactionCount0)
            + "; most recent " + asOf0;

        return new IndicatorResult(value0, bestConfidence0, "CRM", asOf0, AXIS_FIT,
            "Local join over GP CRM interaction history; no external call.");
    }

    // Map one interaction to a tie strength. Tune the ladder HERE (keep zero-cost).
    private double strengthOf(InteractionRecord rec0)
    {
        boolean hasCommitment0 = rec0.commitmentsMadeByGP != null && !rec0.commitmentsMadeByGP.isEmpty();
        boolean hasRelationshipSignal0 = rec0.relationshipSignals != null && !rec0.relationshipSignals.isEmpty();
        String label0 = safe(rec0.conversationLabel).trim().toLowerCase();

        if (hasCommitment0 || label0.contains("prospective close")) return 0.92;
        if (label0.contains("meeting")) return 0.78;
        if (hasRelationshipSignal0) return 0.70;
        if (label0.contains("first interest")) return 0.55;
        if (label0.contains("rejected")) return 0.25;
        if (label0.contains("reached out")) return 0.35;
        // Any recorded interaction is still a (weak) tie.
        return 0.30;
    }

    private String tieLabel(InteractionRecord rec0)
    {
        boolean hasCommitment0 = rec0.commitmentsMadeByGP != null && !rec0.commitmentsMadeByGP.isEmpty();
        if (hasCommitment0) return "prior GP commitment / near-close";
        String label0 = safe(rec0.conversationLabel).trim();
        if (!isBlank(label0)) return label0;
        boolean hasRelationshipSignal0 = rec0.relationshipSignals != null && !rec0.relationshipSignals.isEmpty();
        if (hasRelationshipSignal0) return "warm relationship signal";
        return "prior interaction";
    }

    private double statusStrength(String status0)
    {
        String s0 = safe(status0).trim().toLowerCase();
        if (s0.contains("close") || s0.contains("commit")) return 0.85;
        if (s0.contains("meeting")) return 0.75;
        if (s0.contains("interest")) return 0.55;
        if (s0.contains("rejected") || s0.contains("pass")) return 0.25;
        if (s0.contains("reached")) return 0.35;
        if (isBlank(s0)) return 0.30;
        return 0.35;
    }

    private String interactionCountLabel(int n0)
    {
        if (n0 <= 0) return "history on record";
        return n0 + (n0 == 1 ? " interaction" : " interactions");
    }

    // Return the later of two date strings. Prefers ISO-8601 ordering; falls back
    // to lexicographic comparison, then to whichever is non-blank.
    private String laterDate(String a0, String b0)
    {
        if (isBlank(a0)) return safe(b0);
        if (isBlank(b0)) return safe(a0);
        LocalDate da0 = tryParse(a0);
        LocalDate db0 = tryParse(b0);
        if (da0 != null && db0 != null) return da0.isAfter(db0) ? a0 : b0;
        return a0.compareTo(b0) >= 0 ? a0 : b0;
    }

    private LocalDate tryParse(String s0)
    {
        try { return LocalDate.parse(s0.trim()); }
        catch (Exception e0) { return null; }
    }

    // Accepts the "Full Interaction Record" wrapper { asOfDate, records:[...] }
    // as well as legacy bare-array cells; returns the records array (never null).
    private JSONArray parseArray(String json0)
    {
        return InteractionRecord.extractRecordsArray(json0);
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
    private static String safe(String s0) { return s0 == null ? "" : s0; }
}
