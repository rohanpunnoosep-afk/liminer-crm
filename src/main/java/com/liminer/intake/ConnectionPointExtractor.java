package com.liminer.intake;

import com.liminer.core.ConnectionPoint;
import com.liminer.core.InteractionRecord;
import com.liminer.llm.OpenAIClient;
import com.liminer.pipeline.InvestorProfileExtractor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/*
 * ConnectionPointExtractor reads the earliest message in a GP-LP email thread -- the
 * greeting is where the connection between them is almost always stated -- and
 * classifies how they are connected into the ConnectionPoint taxonomy (com.liminer.core,
 * task 0170). Connection point is the heaviest block in the LP profile vector (ALPHA =
 * 0.25) and is otherwise stated once and never repeated, so callers must merge a fresh
 * classification with any stored one via merge() rather than overwriting blindly.
 */
public class ConnectionPointExtractor
{
    public static class Result
    {
        public ConnectionPoint category = ConnectionPoint.UNKNOWN;
        public double confidence = 0.0;
        public String referrerName = "";
        public String referrerRelationToGp = "";
        public String evidenceQuote = "";
        public String sourceMessageId = "";
        public String extractedAt = "";

        public JSONObject toJson()
        {
            JSONObject obj0 = new JSONObject();
            obj0.put("category", category == null ? ConnectionPoint.UNKNOWN.name() : category.name());
            obj0.put("confidence", confidence);
            obj0.put("referrerName", referrerName == null ? "" : referrerName);
            obj0.put("referrerRelationToGp", referrerRelationToGp == null ? "" : referrerRelationToGp);
            obj0.put("evidenceQuote", evidenceQuote == null ? "" : evidenceQuote);
            obj0.put("sourceMessageId", sourceMessageId == null ? "" : sourceMessageId);
            obj0.put("extractedAt", extractedAt == null ? "" : extractedAt);
            return obj0;
        }

        public static Result fromJson(JSONObject obj0)
        {
            Result result0 = new Result();

            if (obj0 == null)
            {
                return result0;
            }

            result0.category = ConnectionPoint.fromLabel(obj0.optString("category", "UNKNOWN"));
            result0.confidence = obj0.optDouble("confidence", 0.0);
            result0.referrerName = obj0.optString("referrerName", "");
            result0.referrerRelationToGp = obj0.optString("referrerRelationToGp", "");
            result0.evidenceQuote = obj0.optString("evidenceQuote", "");
            result0.sourceMessageId = obj0.optString("sourceMessageId", "");
            result0.extractedAt = obj0.optString("extractedAt", "");
            return result0;
        }
    }

    // Seam over the LLM call so tests never hit the network. Production impl delegates
    // to OpenAIClient.getTextResponse; tests inject a stub.
    public interface LlmSeam
    {
        String complete(String prompt0) throws Exception;
    }

    public static class OpenAiLlmSeam implements LlmSeam
    {
        @Override
        public String complete(String prompt0) throws Exception
        {
            return OpenAIClient.getTextResponse(prompt0);
        }
    }

    private final LlmSeam llmSeam0;

    public ConnectionPointExtractor()
    {
        this(new OpenAiLlmSeam());
    }

    public ConnectionPointExtractor(LlmSeam llmSeam0)
    {
        this.llmSeam0 = llmSeam0;
    }

    // One classification call over a single email body.
    public Result extractFromEmailBody(String body0, String direction0, String fromAddress0, String subject0)
    {
        if (body0 == null || body0.trim().isEmpty())
        {
            return unknownResult();
        }

        String prompt0 = buildPrompt(body0, direction0, fromAddress0, subject0);

        try
        {
            String response0 = llmSeam0.complete(prompt0);
            return parseResponse(response0);
        }
        catch (Exception exception0)
        {
            return unknownResult();
        }
    }

    // Parses the stored "Full Interaction Record" wrapper and classifies from the
    // EARLIEST record(s), not the latest -- the connection is stated once, at the start
    // of the thread. Returns UNKNOWN when there are no usable records.
    public Result extractFromInteractionRecords(String storedRecordsJson0)
    {
        JSONArray recordsArray0 = InteractionRecord.extractRecordsArray(storedRecordsJson0);

        List<InteractionRecord> records0 = new ArrayList<>();
        for (int i0 = 0; i0 < recordsArray0.length(); i0++)
        {
            JSONObject o0 = recordsArray0.optJSONObject(i0);
            if (o0 != null)
            {
                records0.add(InteractionRecord.fromJSON(o0));
            }
        }

        if (records0.isEmpty())
        {
            return unknownResult();
        }

        records0.sort((a0, b0) -> {
            boolean aBlank0 = isBlank(a0.date);
            boolean bBlank0 = isBlank(b0.date);
            if (aBlank0 && bBlank0) return 0;
            if (aBlank0) return 1;
            if (bBlank0) return -1;
            return a0.date.compareTo(b0.date);
        });

        InteractionRecord earliest0 = records0.get(0);

        StringBuilder text0 = new StringBuilder();
        text0.append(summarize(earliest0));
        if (records0.size() > 1)
        {
            text0.append("\n\n").append(summarize(records0.get(1)));
        }

        String prompt0 = buildPrompt(text0.toString(), earliest0.direction, "", "");

        try
        {
            String response0 = llmSeam0.complete(prompt0);
            return parseResponse(response0);
        }
        catch (Exception exception0)
        {
            return unknownResult();
        }
    }

    // THE NEVER-DOWNGRADE RULE: a connection point is stated once, in the first email,
    // and never mentioned again, so every later message would classify as UNKNOWN. A
    // fresh UNKNOWN must never replace a stored non-UNKNOWN category. Otherwise, prefer
    // whichever result has the higher confidence. Null-safe on both sides.
    public static Result merge(Result stored0, Result fresh0)
    {
        if (stored0 == null)
        {
            return fresh0;
        }

        if (fresh0 == null)
        {
            return stored0;
        }

        if (fresh0.category == ConnectionPoint.UNKNOWN && stored0.category != ConnectionPoint.UNKNOWN)
        {
            return stored0;
        }

        if (fresh0.confidence >= stored0.confidence)
        {
            return fresh0;
        }

        return stored0;
    }

    private Result parseResponse(String response0)
    {
        if (response0 == null || response0.trim().isEmpty())
        {
            return unknownResult();
        }

        try
        {
            JSONObject json0 = InvestorProfileExtractor.parseJsonObjectFromText(response0);

            Result result0 = new Result();
            result0.category = ConnectionPoint.fromLabel(json0.optString("category", "UNKNOWN"));
            result0.confidence = json0.optDouble("confidence", 0.0);
            result0.referrerName = json0.optString("referrer_name", "");
            result0.referrerRelationToGp = json0.optString("referrer_relation_to_gp", "");
            result0.evidenceQuote = json0.optString("evidence_quote", "");
            result0.extractedAt = Instant.now().toString();

            if (result0.category == ConnectionPoint.UNKNOWN)
            {
                result0.confidence = 0.0;
            }

            return result0;
        }
        catch (Exception exception0)
        {
            return unknownResult();
        }
    }

    private String summarize(InteractionRecord record0)
    {
        StringBuilder sb0 = new StringBuilder();
        sb0.append("Date: ").append(safe(record0.date)).append("\n");
        sb0.append("Direction: ").append(safe(record0.direction)).append("\n");
        sb0.append("Summary: ").append(safe(record0.oneSentenceSummary)).append("\n");

        if (record0.relationshipSignals != null && !record0.relationshipSignals.isEmpty())
        {
            sb0.append("Relationship signals: ")
                .append(String.join("; ", record0.relationshipSignals))
                .append("\n");
        }

        return sb0.toString();
    }

    private String buildPrompt(String body0, String direction0, String fromAddress0, String subject0)
    {
        return "You are analyzing the opening message of a GP (general partner, fundraiser) and LP "
            + "(limited partner, investor) email thread for a venture capital fundraising CRM.\n"
            + "Determine the CONNECTION POINT: how the GP and LP came to know each other, based only "
            + "on what this text states or clearly implies. Never guess a category from tone alone.\n\n"
            + "Categories, ranked weakest to strongest tie -- each is a distinct BASIS of trust, not a "
            + "shade of another:\n"
            + "1. COLD_OUTBOUND - GP initiated contact, no prior tie.\n"
            + "2. SHARED_INSTITUTION - alumni, military, or community tie, no work overlap.\n"
            + "3. PLATFORM_INTRODUCTION - a placement agent, LP database, accelerator, or syndicate "
            + "introduced them.\n"
            + "4. EVENT_ENCOUNTER - met at a conference, demo day, or pitch event.\n"
            + "5. INBOUND - LP reached out first, with no referrer or prior tie named.\n"
            + "6. WEAK_REFERRAL - referred by someone met during THIS raise; that referrer has NO "
            + "financial history with the GP.\n"
            + "7. PAST_WORK_COLLEAGUE - the GP and LP worked at the same firm.\n"
            + "8. STRONG_REFERRAL - referred by someone who HAS a financial relationship with the GP "
            + "(a prior LP, a co-investor, or a portfolio founder).\n"
            + "9. PAST_COINVESTOR - the LP has co-invested alongside the GP before.\n"
            + "10. PRIOR_LP - the LP invested in the GP's prior fund or SPV.\n"
            + "If the text does not clearly support one of these, return UNKNOWN.\n\n"
            + "The discriminator between WEAK_REFERRAL and STRONG_REFERRAL is whether the referrer has "
            + "a FINANCIAL history with the GP (invested with them, co-invested, or is a portfolio "
            + "founder) -- not how warm or personal the introduction sounds.\n\n"
            + "Direction of this message: " + safe(direction0) + "\n"
            + "From: " + safe(fromAddress0) + "\n"
            + "Subject: " + safe(subject0) + "\n"
            + "Email text:\n" + body0 + "\n\n"
            + "Return ONLY valid JSON. No markdown. No explanation. Use exactly this structure:\n"
            + "{\n"
            + "  \"category\": \"<one of the ten category names above, or UNKNOWN>\",\n"
            + "  \"confidence\": 0.0,\n"
            + "  \"referrer_name\": \"\",\n"
            + "  \"referrer_relation_to_gp\": \"\",\n"
            + "  \"evidence_quote\": \"\"\n"
            + "}\n\n"
            + "evidence_quote must be copied VERBATIM from the email text above, or left empty when "
            + "category is UNKNOWN. Do not invent a referrer, relationship, or category that the text "
            + "does not support.";
    }

    private Result unknownResult()
    {
        Result result0 = new Result();
        result0.category = ConnectionPoint.UNKNOWN;
        result0.confidence = 0.0;
        result0.extractedAt = Instant.now().toString();
        return result0;
    }

    private static boolean isBlank(String s0)
    {
        return s0 == null || s0.trim().isEmpty();
    }

    private static String safe(String s0)
    {
        return s0 == null ? "" : s0;
    }
}
