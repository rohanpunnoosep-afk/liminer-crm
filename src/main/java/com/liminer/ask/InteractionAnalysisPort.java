package com.liminer.ask;

import org.json.JSONObject;

// Seam over the same OpenAI analysis the email intake pipeline runs, applied to a
// free-text interaction the user described in the Ask panel. The model's ONLY job
// here is classification and extraction -- it picks one of the pre-designed
// conversation labels and fills the interaction-record fields. It never decides
// what a CRM cell should literally contain; AskInteractionStager derives every
// cell value programmatically from this output.
//
// Production implementation is OpenAiInteractionAnalysisPort; tests use a fake.
public interface InteractionAnalysisPort
{
    /*
     * Returns a JSON object carrying the intake extraction keys:
     *   conversationLabel   one of the five allowed labels
     *   oneSentenceSummary  one-line summary of the interaction
     *   direction           INBOUND | OUTBOUND
     *   type                EMAIL | CALL | MEETING | NOTE
     *   keyTopicsDiscussed, lpQuestionsAsked, commitmentsMadeByGP, relationshipSignals  arrays
     *   lpSentiment         POSITIVE | NEUTRAL | CAUTIOUS | NEGATIVE
     */
    JSONObject analyze(String interactionText) throws Exception;
}
