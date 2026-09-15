package com.liminer.ask;

import com.liminer.core.InteractionRecord;
import com.liminer.intake.EmailIntakeProcessor;
import com.liminer.intake.InteractionSignalExtractor;
import com.liminer.sheets.CrmUpdater;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * Staging copy of the CRM interaction write that CrmUpdater performs during email
 * intake. CrmUpdater.applyIncomingUpdateToCrmRow computes the new Conversation
 * Status / Interaction History / Interaction Records / Last Contact Date values and
 * then writes them; this class computes those same values with the same pure
 * helpers (CrmUpdater.shouldUpdateStatus, prependInteractionHistory,
 * appendInteractionRecords) and stages them as ProposedChanges instead. Nothing is
 * written until the user accepts the proposal set, at which point AskApplier does
 * the write.
 *
 * The language model never decides what goes in these cells. Its only contribution
 * is the classification returned by InteractionAnalysisPort: one of the five
 * pre-designed conversation labels plus the extracted interaction-record fields --
 * exactly the analysis email intake runs. The cell text itself is derived here.
 */
public class AskInteractionStager
{
    private static final String DEFAULT_LABEL0 = "Reached Out";
    private static final String DEFAULT_TYPE0 = "NOTE";

    private static final String[] ALLOWED_TYPES0 = new String[] { "EMAIL", "CALL", "MEETING", "NOTE" };

    public static class StagedInteraction
    {
        public String conversationLabel = "";
        public String oneSentenceSummary = "";
        public String interactionDate = "";
        public String recordJson = "";
        public List<String> stagedColumns = new ArrayList<>();
        public List<String> unchangedColumns = new ArrayList<>();
        public List<String> missingColumns = new ArrayList<>();
    }

    public static StagedInteraction record(
        AskContext context,
        int row0,
        String interactionText0,
        String dateHint0) throws Exception
    {
        if (context.analysisPort == null)
        {
            throw new Exception("No interaction analysis port is configured.");
        }

        StagedInteraction staged0 = new StagedInteraction();

        JSONObject analysis0 = context.analysisPort.analyze(interactionText0);

        String label0 = analysis0.optString("conversationLabel", "").trim();

        if (!EmailIntakeProcessor.isAllowedConversationLabel(label0))
        {
            label0 = DEFAULT_LABEL0;
        }

        String interactionDate0 = resolveDate(dateHint0);

        // Same construction as EmailIntakeProcessor.processRowsInOpenAIBatches: parse
        // the extraction object into a record, then stamp date / type / label.
        InteractionRecord record0 = InteractionRecord.fromJSON(analysis0);
        record0.date = interactionDate0;
        record0.type = resolveType(analysis0.optString("type", ""));
        record0.conversationLabel = label0;

        if (isBlank(record0.oneSentenceSummary))
        {
            record0.oneSentenceSummary = fallbackSummary(interactionText0);
        }

        staged0.conversationLabel = label0;
        staged0.oneSentenceSummary = record0.oneSentenceSummary;
        staged0.interactionDate = interactionDate0;
        staged0.recordJson = record0.toJSON().toString();

        // appendInteractionRecords reads a records ARRAY (a bare object would be
        // mistaken for the stored wrapper), matching CrmUpdater.wrapAsJsonArray.
        String incomingRecordsJson0 = new JSONArray().put(record0.toJSON()).toString();

        stageConversationStatus(context, row0, label0, interactionDate0, staged0);
        stageInteractionHistory(context, row0, interactionDate0, record0.oneSentenceSummary, staged0);
        stageInteractionRecords(context, row0, incomingRecordsJson0, staged0);
        stageLastContactDate(context, row0, interactionDate0, staged0);

        return staged0;
    }

    // #2: the status becomes another of the pre-designed labels, promoted by the same
    // rank rule intake uses -- never free text chosen by the model. The row's last
    // contact date rides along so the rule's Rejected-revival branch can tell a genuine
    // re-engagement from an interaction logged out of order.
    private static void stageConversationStatus(
        AskContext context,
        int row0,
        String label0,
        String interactionDate0,
        StagedInteraction staged0) throws Exception
    {
        String header0 = context.session.config.getCol("mainTabStatusCol");
        String current0 = readCell(context, row0, header0, staged0);

        if (current0 == null)
        {
            return;
        }

        String lastContact0 = readCellQuietly(
            context, row0, context.session.config.getCol("mainTabLastContactDateCol"));

        if (!CrmUpdater.shouldUpdateStatus(current0, label0, lastContact0, interactionDate0))
        {
            staged0.unchangedColumns.add(header0);
            return;
        }

        stageIfDifferent(context, row0, header0, current0, label0, staged0);
    }

    // #3a: one dated line prepended to the history, built by the intake helper.
    private static void stageInteractionHistory(
        AskContext context,
        int row0,
        String interactionDate0,
        String oneSentenceSummary0,
        StagedInteraction staged0) throws Exception
    {
        String header0 = context.session.config.getCol("mainTabInteractionHistoryCol");
        String current0 = readCell(context, row0, header0, staged0);

        if (current0 == null)
        {
            return;
        }

        String updated0 = CrmUpdater.prependInteractionHistory(current0, interactionDate0, oneSentenceSummary0);

        stageIfDifferent(context, row0, header0, current0, updated0, staged0);
    }

    // #3b: the new record appended into the stored wrapper by the intake helper,
    // which also de-duplicates, re-sorts by date and refreshes asOfDate.
    private static void stageInteractionRecords(
        AskContext context,
        int row0,
        String recordJson0,
        StagedInteraction staged0) throws Exception
    {
        String header0 = context.session.config.getCol("mainTabInteractionRecordsCol");
        String current0 = readCell(context, row0, header0, staged0);

        if (current0 == null)
        {
            return;
        }

        String updated0 = CrmUpdater.appendInteractionRecords(current0, recordJson0);

        stageIfDifferent(context, row0, header0, current0, updated0, staged0);
    }

    // Kept in step with the records cell: the recency signals downstream
    // (InteractionSignalExtractor) fall back on this column, so a recorded
    // interaction that left it stale would read as older than it is.
    private static void stageLastContactDate(
        AskContext context,
        int row0,
        String interactionDate0,
        StagedInteraction staged0) throws Exception
    {
        String header0 = context.session.config.getCol("mainTabLastContactDateCol");
        String current0 = readCell(context, row0, header0, staged0);

        if (current0 == null)
        {
            return;
        }

        LocalDate incoming0 = InteractionSignalExtractor.parseDate(interactionDate0);
        LocalDate existing0 = InteractionSignalExtractor.parseDate(current0);

        if (incoming0 == null)
        {
            staged0.unchangedColumns.add(header0);
            return;
        }

        if (existing0 != null && !incoming0.isAfter(existing0))
        {
            staged0.unchangedColumns.add(header0);
            return;
        }

        stageIfDifferent(context, row0, header0, current0, interactionDate0, staged0);
    }

    private static void stageIfDifferent(
        AskContext context,
        int row0,
        String header0,
        String current0,
        String updated0,
        StagedInteraction staged0) throws Exception
    {
        if (updated0 == null || updated0.equals(current0))
        {
            staged0.unchangedColumns.add(header0);
            return;
        }

        AskProposals.stage(context, row0, header0, current0, updated0);
        staged0.stagedColumns.add(header0);
    }

    // Returns null (and notes the column) when the sheet has no such header, so a
    // CRM missing one of these columns still stages the others.
    private static String readCell(
        AskContext context,
        int row0,
        String header0,
        StagedInteraction staged0) throws Exception
    {
        if (header0 == null || header0.trim().isEmpty())
        {
            staged0.missingColumns.add("(unconfigured column)");
            return null;
        }

        Integer col0 = context.headerMap.get(header0);

        if (col0 == null)
        {
            staged0.missingColumns.add(header0);
            return null;
        }

        String value0 = context.port.readCell(row0, col0);
        return value0 == null ? "" : value0;
    }

    // Same read, but a missing column is not worth reporting: this value only refines
    // the status rule, and stageLastContactDate already notes the column when absent.
    private static String readCellQuietly(
        AskContext context,
        int row0,
        String header0) throws Exception
    {
        if (header0 == null || header0.trim().isEmpty())
        {
            return "";
        }

        Integer col0 = context.headerMap.get(header0);

        if (col0 == null)
        {
            return "";
        }

        String value0 = context.port.readCell(row0, col0);
        return value0 == null ? "" : value0;
    }

    private static String resolveDate(String dateHint0)
    {
        LocalDate parsed0 = InteractionSignalExtractor.parseDate(dateHint0);

        if (parsed0 != null)
        {
            return parsed0.toString();
        }

        return LocalDate.now().toString();
    }

    private static String resolveType(String type0)
    {
        String candidate0 = type0 == null ? "" : type0.trim().toUpperCase();

        for (int i0 = 0; i0 < ALLOWED_TYPES0.length; i0++)
        {
            if (ALLOWED_TYPES0[i0].equals(candidate0))
            {
                return candidate0;
            }
        }

        return DEFAULT_TYPE0;
    }

    // Last resort only: the analysis returned no summary, so the history line uses
    // the user's own words rather than anything the model composed.
    private static String fallbackSummary(String interactionText0)
    {
        String text0 = interactionText0 == null ? "" : interactionText0.trim().replaceAll("\\s+", " ");

        if (text0.length() > 240)
        {
            text0 = text0.substring(0, 240).trim() + "…";
        }

        return text0;
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().isEmpty();
    }
}
