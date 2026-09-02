package com.liminer.intake;

import com.liminer.core.InteractionRecord;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * InteractionSignalExtractor — deterministic parse of the stored "Full Interaction
 * Record" wrapper into the relationship signals the Tier-1 priority layer needs
 * (priorityscoringv2 §7.3). This absorbs the recency / stage / who-owes-reply logic
 * that PriorityActionProcessor and FollowUpRecommender each reimplemented, so the
 * email->row join and the two unreconciled scores collapse into one source.
 *
 * No LLM call — pure parse over InteractionRecord.extractRecordsArray / fromJSON.
 */
public class InteractionSignalExtractor
{
    // Conversation-label stage ranks (most-advanced wins). Terminal labels are
    // handled separately via the `rejected` flag.
    public static final String STAGE_REACHED_OUT      = "Reached Out";
    public static final String STAGE_FIRST_INTEREST   = "First Interest";
    public static final String STAGE_MEETINGS         = "Meetings";
    public static final String STAGE_PROSPECTIVE_CLOSE = "Prospective Close";
    public static final String STAGE_REJECTED         = "Rejected";

    public static class InteractionSignals
    {
        public boolean hasRecords        = false;
        public int     recordCount       = 0;
        public boolean owesReply         = false;   // most-recent record is inbound
        public int     daysSinceLastContact = -1;   // -1 = unknown
        public int     daysSinceLastInbound  = -1;
        public int     openCommitmentCount = 0;
        public String  lastSentiment      = "";
        public String  stage              = "";     // most-advanced non-terminal label
        public boolean rejected           = false;  // latest label is Rejected / Do Not Contact
        public String  asOfDate           = "";
    }

    public static int stageRank(String label0)
    {
        if (label0 == null) return 0;
        String l0 = label0.trim();
        if (STAGE_PROSPECTIVE_CLOSE.equalsIgnoreCase(l0)) return 4;
        if (STAGE_MEETINGS.equalsIgnoreCase(l0)) return 3;
        if (STAGE_FIRST_INTEREST.equalsIgnoreCase(l0)) return 2;
        if (STAGE_REACHED_OUT.equalsIgnoreCase(l0)) return 1;
        return 0;
    }

    public static boolean isTerminalLabel(String label0)
    {
        if (label0 == null) return false;
        String l0 = label0.trim();
        return STAGE_REJECTED.equalsIgnoreCase(l0)
            || "Do Not Contact".equalsIgnoreCase(l0);
    }

    // Parse the stored records cell + fallbacks into the relationship signal vector.
    // conversationStatus0 and lastContactDate0 are the CRM column fallbacks used when
    // the records themselves are absent.
    public static InteractionSignals extract(
        String storedRecordsJson0,
        String conversationStatus0,
        String lastContactDate0,
        LocalDate today0)
    {
        InteractionSignals sig0 = new InteractionSignals();
        if (today0 == null) today0 = LocalDate.now();

        // Wrapper asOfDate (if present).
        if (storedRecordsJson0 != null && storedRecordsJson0.trim().startsWith("{"))
        {
            try { sig0.asOfDate = new JSONObject(storedRecordsJson0.trim()).optString("asOfDate", ""); }
            catch (Exception ignore0) { }
        }

        JSONArray records0 = InteractionRecord.extractRecordsArray(storedRecordsJson0);
        sig0.recordCount = records0.length();
        sig0.hasRecords = sig0.recordCount > 0;

        LocalDate latestAny0     = null;
        String    latestAnyDir0  = "";
        String    latestAnyLabel0 = "";
        String    latestAnySentiment0 = "";
        LocalDate latestInbound0 = null;

        int    bestStageRank0 = 0;
        String bestStage0     = "";
        HashSet<String> commitments0 = new HashSet<>();

        for (int i0 = 0; i0 < records0.length(); i0++)
        {
            JSONObject o0 = records0.optJSONObject(i0);
            if (o0 == null) continue;
            InteractionRecord rec0 = InteractionRecord.fromJSON(o0);

            // Most-advanced non-terminal stage.
            int rank0 = stageRank(rec0.conversationLabel);
            if (rank0 > bestStageRank0)
            {
                bestStageRank0 = rank0;
                bestStage0 = rec0.conversationLabel.trim();
            }

            // Unique unresolved GP commitments (count-only, priorityscoringv2 §11).
            if (rec0.commitmentsMadeByGP != null)
            {
                for (String c0 : rec0.commitmentsMadeByGP)
                {
                    if (c0 != null && !c0.trim().isEmpty()) commitments0.add(c0.trim().toLowerCase());
                }
            }

            LocalDate d0 = parseDate(rec0.date);
            if (d0 != null)
            {
                if (latestAny0 == null || d0.isAfter(latestAny0))
                {
                    latestAny0 = d0;
                    latestAnyDir0 = safe(rec0.direction);
                    latestAnyLabel0 = safe(rec0.conversationLabel);
                    if (!isBlank(rec0.lpSentiment)) latestAnySentiment0 = rec0.lpSentiment.trim();
                }
                if (isInbound(rec0.direction) && (latestInbound0 == null || d0.isAfter(latestInbound0)))
                {
                    latestInbound0 = d0;
                }
            }
            // Carry a sentiment even when dates are missing (best-effort).
            if (isBlank(latestAnySentiment0) && !isBlank(rec0.lpSentiment))
            {
                latestAnySentiment0 = rec0.lpSentiment.trim();
            }
        }

        sig0.stage = bestStage0;
        sig0.openCommitmentCount = commitments0.size();
        sig0.lastSentiment = latestAnySentiment0;

        // owesReply: direction of the most recent dated record.
        sig0.owesReply = isInbound(latestAnyDir0);

        // Rejection: latest dated label terminal, else the CRM Conversation Status.
        sig0.rejected = isTerminalLabel(latestAnyLabel0) || isTerminalLabel(conversationStatus0);

        // Days-since from record dates, with Last Contact Date as a fallback.
        if (latestAny0 != null)
        {
            sig0.daysSinceLastContact = (int) java.time.temporal.ChronoUnit.DAYS.between(latestAny0, today0);
        }
        else
        {
            LocalDate lc0 = parseDate(lastContactDate0);
            if (lc0 != null)
            {
                sig0.daysSinceLastContact = (int) java.time.temporal.ChronoUnit.DAYS.between(lc0, today0);
            }
        }
        if (latestInbound0 != null)
        {
            sig0.daysSinceLastInbound = (int) java.time.temporal.ChronoUnit.DAYS.between(latestInbound0, today0);
        }

        // Stage fallback: if no record carried a stage, use Conversation Status.
        if (isBlank(sig0.stage) && !isBlank(conversationStatus0) && stageRank(conversationStatus0) > 0)
        {
            sig0.stage = conversationStatus0.trim();
        }

        // Negative day counts (future-dated) clamp to 0.
        if (sig0.daysSinceLastContact < -1) sig0.daysSinceLastContact = 0;
        if (sig0.daysSinceLastInbound < -1) sig0.daysSinceLastInbound = 0;

        return sig0;
    }

    private static boolean isInbound(String direction0)
    {
        return direction0 != null && direction0.trim().equalsIgnoreCase("inbound");
    }

    // Tolerant date parse: ISO first, then a few common CRM formats.
    private static final DateTimeFormatter[] FORMATS = {
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("M/d/yyyy"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd")
    };

    public static LocalDate parseDate(String s0)
    {
        if (s0 == null) return null;
        String t0 = s0.trim();
        if (t0.isEmpty()) return null;
        // Trim any time component.
        int tIdx0 = t0.indexOf('T');
        if (tIdx0 > 0) t0 = t0.substring(0, tIdx0);
        int spaceIdx0 = t0.indexOf(' ');
        if (spaceIdx0 > 0) t0 = t0.substring(0, spaceIdx0);
        for (DateTimeFormatter f0 : FORMATS)
        {
            try { return LocalDate.parse(t0, f0); }
            catch (Exception ignore0) { }
        }
        return null;
    }

    private static String safe(String s0) { return s0 == null ? "" : s0; }
    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
