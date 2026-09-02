package com.liminer.scout;

import org.json.JSONObject;

/*
 * ScoutContact — one discovered contact for a ScoutUniverseRecord candidate,
 * produced by EmailFinder's layered waterfall. confidence is one of
 * VERIFIED (published by the firm itself, e.g. website mailto/visible email),
 * LINKEDIN_ONLY (a person identified via Bright Data SERP LinkedIn search —
 * name/title/linkedinUrl, no email), or FIRM_LEVEL (the ADV Item 1.J
 * compliance mailbox — a guaranteed-valid firm address but not necessarily a
 * decision maker). PATTERN_VERIFIED is kept only so old persisted data
 * deserializes; no code path produces it anymore (the email-verifier/Hunter
 * layer that generated it has been removed). source records which layer
 * produced the contact and the originating URL, kept for the evidence JSON so
 * a client can honor GDPR/PECR lookups. No code path may set confidence to
 * anything else, and no unverified pattern-guessed email may ever be stored
 * here.
 */
public class ScoutContact
{
    public static final String CONFIDENCE_VERIFIED = "VERIFIED";
    public static final String CONFIDENCE_PATTERN_VERIFIED = "PATTERN_VERIFIED";
    public static final String CONFIDENCE_FIRM_LEVEL = "FIRM_LEVEL";
    public static final String CONFIDENCE_LINKEDIN_ONLY = "LINKEDIN_ONLY";

    public String name;
    public String title;
    public String email;
    public String linkedinUrl;
    public String confidence;
    public String source;

    public ScoutContact()
    {
        name = "";
        title = "";
        email = "";
        linkedinUrl = "";
        confidence = "";
        source = "";
    }

    public ScoutContact(String name0, String title0, String email0, String linkedinUrl0, String confidence0, String source0)
    {
        name = safe(name0);
        title = safe(title0);
        email = safe(email0);
        linkedinUrl = safe(linkedinUrl0);
        confidence = safe(confidence0);
        source = safe(source0);
    }

    public JSONObject toJson()
    {
        JSONObject o0 = new JSONObject();
        o0.put("name", name);
        o0.put("title", title);
        o0.put("email", email);
        o0.put("linkedinUrl", linkedinUrl);
        o0.put("confidence", confidence);
        o0.put("source", source);
        return o0;
    }

    public static ScoutContact fromJson(JSONObject o0)
    {
        ScoutContact c0 = new ScoutContact();
        if (o0 == null) return c0;
        c0.name = o0.optString("name", "");
        c0.title = o0.optString("title", "");
        c0.email = o0.optString("email", "");
        c0.linkedinUrl = o0.optString("linkedinUrl", "");
        c0.confidence = o0.optString("confidence", "");
        c0.source = o0.optString("source", "");
        return c0;
    }

    private static String safe(String s0) { return s0 == null ? "" : s0; }
}
