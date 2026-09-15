package com.liminer.ask;

import com.liminer.core.CRMField;
import com.liminer.core.CRMFieldRegistry;
import com.liminer.intake.EmailIntakeProcessor;
import com.liminer.llm.OpenAIClient;

import org.json.JSONObject;

// Production InteractionAnalysisPort. Runs the SAME conversation-label and
// interaction-intelligence rules the email intake extraction uses -- the rule text
// is pulled from CRMFieldRegistry and EmailIntakeProcessor rather than restated --
// against a free-text interaction the user described in the Ask panel.
public class OpenAiInteractionAnalysisPort implements InteractionAnalysisPort
{
    // Direction for a user-narrated interaction cannot come from email headers, so
    // this replaces EmailIntakeProcessor.DIRECTION_RULES_FOR_EMAIL.
    private static final String DIRECTION_RULES_FOR_NARRATED0 =
        "DIRECTION RULES:\n"
        + "direction must be INBOUND or OUTBOUND.\n"
        + "INBOUND means the LP reached out or replied to the GP.\n"
        + "OUTBOUND means the GP reached out, sent materials, or followed up.\n"
        + "A meeting or call that the GP took with the LP is OUTBOUND unless the LP clearly initiated it.\n"
        + "When the text does not say who initiated, use OUTBOUND.\n\n";

    private static final String TYPE_RULES0 =
        "TYPE RULES:\n"
        + "type must be exactly one of EMAIL, CALL, MEETING or NOTE.\n"
        + "Use MEETING for meetings, calls with video, pitches and diligence sessions.\n"
        + "Use CALL for phone calls. Use EMAIL for email threads. Use NOTE when nothing else fits.\n\n";

    @Override
    public JSONObject analyze(String interactionText) throws Exception
    {
        String prompt0 = buildAnalysisPrompt(interactionText);
        String output0 = OpenAIClient.getTextResponse(prompt0);

        return parseJsonObjectFromText(output0);
    }

    public static String buildAnalysisPrompt(String interactionText0)
    {
        String labelRules0 = instructionFor("intakeTabConversationLabelCol");
        String summaryRules0 = instructionFor("intakeTabConversationSummaryCol");

        return
            "You are labelling one investor interaction for a venture capital fundraising CRM.\n"
            + "The interaction was described by the GP in their own words.\n\n"

            + "Return ONLY valid JSON. Do not include markdown. Do not explain anything.\n\n"

            + "Return a single JSON object with exactly these fields:\n"
            + "- conversationLabel\n"
            + "- oneSentenceSummary\n"
            + "- type\n"
            + EmailIntakeProcessor.INTERACTION_RECORD_FIELD_LIST
            + "\n"

            + "GENERAL RULES:\n"
            + "1. Do not guess. Empty string or empty array is better than a guessed value.\n"
            + "2. Never invent a conversation label outside the allowed list.\n"
            + "3. Describe only what the GP's text supports.\n\n"

            + labelRules0
            + summaryRules0
            + DIRECTION_RULES_FOR_NARRATED0
            + TYPE_RULES0
            + EmailIntakeProcessor.INTERACTION_INTELLIGENCE_RULES

            + "Interaction described by the GP:\n"
            + (interactionText0 == null ? "" : interactionText0.trim());
    }

    private static String instructionFor(String fieldKey0)
    {
        CRMField field0 = CRMFieldRegistry.getByKey(fieldKey0);

        if (field0 == null || field0.aiExtractionInstruction == null || field0.aiExtractionInstruction.trim().isEmpty())
        {
            return "";
        }

        return field0.aiExtractionInstruction + "\n\n";
    }

    // Tolerant parse: accepts a bare object, or an object embedded in surrounding
    // text, mirroring EmailIntakeProcessor.parseJsonArrayFromText.
    public static JSONObject parseJsonObjectFromText(String text0)
    {
        String trimmed0 = text0 == null ? "" : text0.trim();

        try
        {
            return new JSONObject(trimmed0);
        }
        catch (Exception exception0)
        {
            int startIndex0 = trimmed0.indexOf("{");
            int endIndex0 = trimmed0.lastIndexOf("}");

            if (startIndex0 == -1 || endIndex0 == -1 || endIndex0 <= startIndex0)
            {
                throw exception0;
            }

            return new JSONObject(trimmed0.substring(startIndex0, endIndex0 + 1));
        }
    }
}
