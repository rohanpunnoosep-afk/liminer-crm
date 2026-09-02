package com.liminer.core;

import com.liminer.sheets.SheetsApp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

public class CRMFieldRegistry
{
    private static final ArrayList<CRMField> ALL_FIELDS = new ArrayList<>();
    private static final HashMap<String, CRMField> BY_KEY = new HashMap<>();

    // The visual sentinel header for the divider column (dividerlists.md step 2).
    // Kept ASCII so it is source-encoding-safe; the real divider is the SOLID
    // vertical border drawn by SheetsApp.columnBorder on this column's right edge.
    public static final String DIVIDER_HEADER = "|| Liminer ||";

    // The "BEFORE the divider" human-facing set (dividerlists.md §"BEFORE the divider").
    // Any main field whose key is in this set is provisioned LEFT of the divider.
    // Every other main field defaults to side="machine" (right of the divider).
    // Priority Reason / Strategic Value / Action Urgency are the promote-left
    // projections from priorityscoringv2 §1.
    private static final HashSet<String> HUMAN_KEYS = new HashSet<>(Arrays.asList(
        "mainTabFundNameCol",
        "mainTabContact1FirstNameCol",
        "mainTabContact1LastNameCol",
        "mainTabContact1EmailCol",
        "mainTabContact1PositionCol",
        "mainTabContactLinkedInCol",
        "mainTabContact2FirstNameCol",
        "mainTabContact2LastNameCol",
        "mainTabContact2EmailCol",
        "mainTabContact2PositionCol",
        "mainTabLinkedIn2Col",
        "mainTabCompanyLinkedInCol",
        "mainTabTypeOfInvestorCol",
        "mainTabStatusCol",
        "mainTabWebsiteCol",
        "mainTabCountryCol",
        "mainTabCityCol",
        "mainTabNotesCol",
        "mainTabCommentsCol",
        "mainTabColdEmailCol",
        "mainTabLastContactDateCol",
        "mainTabNextActionCol",
        "mainTabInteractionHistoryCol",
        "mainTabOutstandingCommitmentsCol",
        "mainTabStrategicValueCol",
        "mainTabActionUrgencyCol",
        "mainTabPriorityReasonCol"
    ));

    static
    {
        // ============================================================
        // MAIN CRM TAB FIELDS
        // ============================================================

        reg("mainTabFundNameCol", "Fund Name", "Fund Name", "text", "main", true, false, true, "", "");
        reg("mainTabContact1FirstNameCol", "Contact 1 First Name", "Contact 1 First Name", "text", "main", true, false, true, "", "");
        reg("mainTabContact1LastNameCol", "Contact 1 Last Name", "Contact 1 Last Name", "text", "main", true, false, true, "", "");
        reg("mainTabContact1EmailCol", "Contact 1 Email Address", "Contact 1 Email", "text", "main", true, false, true, "", "");
        reg("mainTabContact1PositionCol", "Contact 1 Position", "Contact 1 Position", "text", "main", true, false, true, "", "");
        reg("mainTabContactLinkedInCol", "Contact 1 LinkedIn", "Contact 1 LinkedIn", "text", "main", true, false, true, "", "");
        reg("mainTabContact2FirstNameCol", "Contact 2 First Name", "Contact 2 First Name", "text", "main", true, false, true, "", "");
        reg("mainTabContact2LastNameCol", "Contact 2 Last Name", "Contact 2 Last Name", "text", "main", true, false, true, "", "");
        reg("mainTabContact2EmailCol", "Contact 2 Email Address", "Contact 2 Email", "text", "main", true, false, true, "", "");
        reg("mainTabContact2PositionCol", "Contact 2 Position", "Contact 2 Position", "text", "main", true, false, true, "", "");
        reg("mainTabLinkedIn2Col", "Contact 2 LinkedIn", "Contact 2 LinkedIn", "text", "main", true, false, true, "", "");
        reg("mainTabCompanyLinkedInCol", "Company LinkedIn", "Company LinkedIn", "text", "main", true, false, true, "", "");
        reg("mainTabTypeOfInvestorCol", "Type of Investor", "Type Of Investor", "text", "main", true, false, true, "", "");
        reg("mainTabStatusCol", "Conversation Status", "Status", "status", "main", true, false, true, "", "");
        reg("mainTabWebsiteCol", "Fund Website", "Website", "text", "main", true, false, true, "", "");
        reg("mainTabCountryCol", "Country", "Country", "text", "main", true, false, true, "", "");
        reg("mainTabCityCol", "City", "City", "text", "main", true, false, true, "", "");
        reg("mainTabNotesCol", "Notes", "Notes", "text", "main", true, false, true, "", "");
        reg("mainTabColdEmailCol", "Cold Email", "Cold Email", "text", "main", true, false, true, "", "");
        reg("mainTabNameLinkedInQueryCol", "Name LinkedIn Query", "Name LinkedIn Query", "text", "main", true, false, true, "", "");
        reg("mainTabCompanyLinkedInQueryCol", "Company LinkedIn Query", "Company LinkedIn Query", "text", "main", true, false, true, "", "");
        reg("mainTabCommentsCol", "Comments", "Comments", "text", "main", true, false, true, "", "");
        reg("mainTabLastContactDateCol", "Last Contact Date", "Last Contact Date", "date", "main", true, false, true, "", "");
        reg("mainTabNextActionCol", "Next Action", "Next Action", "text", "main", true, false, true, "", "");
        // DEPRECATED (priorityscoringv2 §1): Follow Up Recommendation, Priority Score and
        // Priority Last Calculated At are the two old, unreconciled priority scores. Kept
        // readable during shadow-mode migration, then retired. Superseded by the unified
        // Tier-1 engine (Strategic Value + Action Urgency + Priority Signal JSON).
        reg("mainTabFollowUpRecommendationCol", "Follow Up Recommendation", "Follow Up Recommendation", "text", "main", false, false, true, "", "", CRMField.SIDE_MACHINE, true);
        reg("mainTabPriorityScoreCol", "Priority Score", "Priority Score", "number", "main", true, false, true, "", "", CRMField.SIDE_MACHINE, true);
        // Priority Reason is REPURPOSED (priorityscoringv2 §1) as the human-facing one-line
        // deterministic fallback of top signals; it is marked human via HUMAN_KEYS below.
        reg("mainTabPriorityReasonCol", "Priority Reason", "Priority Reason", "text", "main", true, false, true, "", "");
        reg("mainTabPriorityLastCalculatedAtCol", "Priority Last Calculated At", "Priority Last Calculated At", "date", "main", true, false, true, "", "", CRMField.SIDE_MACHINE, true);

        // ------------------------------------------------------------
        // PRIORITY SCORING v2 — Tier-1 signal layer (priorityscoringv2 §1)
        // ------------------------------------------------------------
        // Machine side (right of divider): the full Tier-1 signal vector + its
        // computed-date staleness gate.
        reg("mainTabPrioritySignalJsonCol", "Priority Signal JSON", "Priority Signal JSON", "json", "main", true, false, true, "", "");
        reg("mainTabPrioritySignalDateCol", "Priority Signal Date", "Priority Signal Date", "date", "main", true, false, true, "", "");
        // Human side (left of divider): the two orthogonal denormalized projections.
        // Marked human via HUMAN_KEYS below (Priority Reason already registered above).
        reg("mainTabStrategicValueCol", "Strategic Value", "Strategic Value", "number", "main", true, false, true, "", "");
        reg("mainTabActionUrgencyCol", "Action Urgency", "Action Urgency", "number", "main", true, false, true, "", "");
        reg("mainTabInteractionHistoryCol", "Interaction History", "Interaction History", "text", "main", true, false, true, "", "");
        reg("mainTabInteractionRecordsCol", "Interaction Records", "Interaction Records", "json", "main", true, false, true, "", "");
        reg("mainTabInvestorProfileSimilarityCol", "Investor Profile Similarity", "Investor Profile Similarity", "text", "main", true, false, true, "", "");
        reg("mainTabSectorTagsCol", "Sector Tags", "Sector Tags", "text", "main", true, false, true, "", "");
        reg("mainTabMicrosectorTagsCol", "Microsector Tags", "Microsector Tags", "text", "main", true, false, true, "", "");
        reg("mainTabGeographyCol", "Geography", "Geography", "text", "main", true, false, true, "", "");
        reg("mainTabPriorBackedFundsCol", "Prior Backed Funds", "Prior Backed Funds", "text", "main", true, false, true, "", "");
        reg("mainTabIntelligenceJsonCol", "Intelligence JSON", "Intelligence JSON", "json", "main", true, false, true, "", "");
        reg("mainTabLastEnrichedAtCol", "Last Enriched At", "Last Enriched At", "date", "main", true, false, true, "", "");
        reg("mainTabEnrichmentStatusCol", "Enrichment Status", "Enrichment Status", "text", "main", true, false, true, "", "");
        reg("mainTabInvestmentThesisCol", "Investment Thesis", "Investment Thesis", "text", "main", true, false, true, "", "");
        reg("mainTabContactLinkedInAboutCol", "Contact LinkedIn About", "Contact LinkedIn About", "text", "main", true, false, true, "", "");
        reg("mainTabContactPastWorkExperienceCol", "Contact Past Work Experience JSON", "Contact Past Work Experience", "json", "main", true, false, true, "", "");
        reg("mainTabFundLinkedInAboutCol", "Fund LinkedIn About", "Fund LinkedIn About", "text", "main", true, false, true, "", "");
        reg("mainTabContactWebsiteBioUrlCol", "Contact Website Bio URL", "Contact Website Bio URL", "text", "main", true, false, true, "", "");
        reg("mainTabContactWebsiteBioSummaryCol", "Contact Website Bio Summary", "Contact Website Bio Summary", "text", "main", true, false, true, "", "");
        reg("mainTabBackgroundCheckStatusCol", "Background Check Status", "Background Check Status", "text", "main", true, false, true, "", "");
        reg("mainTabBackgroundCheckConfidenceCol", "Background Check Confidence", "Background Check Confidence", "number", "main", true, false, true, "", "");
        reg("mainTabLastBackgroundCheckDateCol", "Last Background Check Date", "Last Background Check Date", "date", "main", true, false, true, "", "");
        reg("mainTabBackgroundCheckJsonCol", "Background Check JSON", "Background Check JSON", "json", "main", true, false, true, "", "");
        reg("mainTabContactLinkedInPostsSummaryCol", "Contact LinkedIn Posts Summary", "Contact LinkedIn Posts Summary", "text", "main", true, false, true, "", "");
        reg("mainTabContactFollowerCountCol", "Contact Follower Count", "Contact Follower Count", "number", "main", true, false, true, "", "");
        reg("mainTabContactBioCareerSummaryCol", "Contact Bio Career Summary", "Contact Bio Career Summary", "text", "main", true, false, true, "", "");
        reg("mainTabContactBioInstitutionsCol", "Contact Affiliated Institutions", "Contact Affiliated Institutions", "json", "main", true, false, true, "", "");
        reg("mainTabContactBioEducationCol", "Contact Bio Education", "Contact Bio Education", "json", "main", true, false, true, "", "");

        // ============================================================
        // MARKET INTELLIGENCE FIELDS
        // Identity-resolution keys (Theme 9) + the three per-LP score axes.
        // Market Intelligence writes its own JSON blob (macro/resources/fit/
        // probability_now) to mainTabMarketIntelligenceJsonCol so it does not
        // overwrite the website-enrichment Intelligence JSON, which has a
        // different schema and is consumed by candidate scoring / priority.
        // ============================================================

        reg("mainTabCrdNumberCol", "CRD Number", "CRD Number", "text", "main", true, false, true, "", "");
        reg("mainTabCikNumberCol", "CIK Number", "CIK Number", "text", "main", true, false, true, "", "");
        reg("mainTabLeiCol", "LEI", "LEI", "text", "main", true, false, true, "", "");
        reg("mainTabEinCol", "EIN", "EIN", "text", "main", true, false, true, "", "");
        reg("mainTabIdentityStatusCol", "Identity Resolution Status", "Identity Resolution Status", "text", "main", true, false, true, "", "");
        reg("mainTabResourcesScoreCol", "Resources Score", "Resources Score", "number", "main", true, false, true, "", "");
        reg("mainTabFitScoreCol", "Fit Score", "Fit Score", "number", "main", true, false, true, "", "");
        reg("mainTabProbabilityNowCol", "Probability Now", "Probability Now", "number", "main", true, false, true, "", "");
        reg("mainTabLastIntelDateCol", "Last Intel Date", "Last Intel Date", "date", "main", true, false, true, "", "");
        reg("mainTabIntelStatusCol", "Intel Status", "Intel Status", "text", "main", true, false, true, "", "");
        reg("mainTabMarketIntelligenceJsonCol", "Market Intelligence JSON", "Market Intelligence JSON", "json", "main", true, false, true, "", "");

        // ============================================================
        // INVESTOR SCOUT FIELDS (task 0060)
        // Written only by InvestorScoutProcessor's CRM append: the full
        // finalScore/subscores/whyNow/sources evidence blob backing a scout-
        // discovered row, kept separate from Market Intelligence JSON (which
        // covers existing CRM rows enriched by the LP workflow, not net-new
        // Scout candidates).
        // ============================================================

        reg("mainTabScoutEvidenceCol", "Scout Evidence", "Scout Evidence", "json", "main", true, false, true, "", "");

        // ============================================================
        // RELATIONSHIP SUMMARY FIELDS
        // Produced by the Relationship Summary workflow (RelationshipSummaryProcessor),
        // which reads the full Interaction Records for an LP and writes three columns:
        // the outstanding GP commitments, a JSON blob holding the full summary (interests,
        // sentiment, arc, commitments, and as-of date), and the analysis date. A blank
        // analysis date is the signal that a row has not yet been summarized — there is no
        // separate status column. Decoupled from market intelligence because the GP<->LP
        // relationship moves fast.
        // ============================================================

        reg("mainTabOutstandingCommitmentsCol", "Outstanding GP Commitments", "Outstanding GP Commitments", "text", "main", true, false, true, "", "");
        reg("mainTabRelationshipSummaryJsonCol", "Relationship Summary JSON", "Relationship Summary JSON", "json", "main", true, false, true, "", "");
        reg("mainTabRelationshipSummaryDateCol", "Last Relationship Summary Date", "Last Relationship Summary Date", "date", "main", true, false, true, "", "");

        // ============================================================
        // INVESTOR BRIEF FIELDS
        // Produced by the Investor Brief assembly workflow (InvestorBriefJsonProcessor),
        // which collects the outputs of the four upstream LP-enrichment workflows into one
        // structured per-LP brief and writes two columns: the serialized brief JSON and the
        // date it was generated. A blank Last Brief Generated date is the signal that a row
        // has not yet been briefed — there is no separate status column.
        // ============================================================

        reg("mainTabInvestorBriefJsonCol", "Investor Brief JSON", "Investor Brief JSON", "json", "main", true, false, true, "", "");
        reg("mainTabLastBriefGeneratedCol", "Last Brief Generated", "Last Brief Generated", "date", "main", true, false, true, "", "");

        // ============================================================
        // DIVIDER (dividerlists.md steps 1-2)
        // A single, narrow sentinel column that separates the GP's human-facing
        // columns (left) from Liminer's machine-generated enrichment (right).
        // includeInOnboarding=false so the generic onboarding loop never provisions
        // it as an ordinary column — it is placed explicitly by ensureDivider().
        // includeInSummary=false so it does not clutter the config summary.
        // ============================================================

        reg("mainTabDividerCol", DIVIDER_HEADER, "Divider", "divider", "main", false, false, false, "", "", CRMField.SIDE_DIVIDER, false);

        // ============================================================
        // INTAKE TAB FIELDS - SOURCE (AI-detected mappings from client sheet)
        // ============================================================

        reg("intakeTabIntakeIdCol", "Intake ID", "Intake ID", "text", "intake", false, false, true, "", "");
        reg("intakeTabGmailMessageIdCol", "Gmail Message ID", "Gmail Message ID", "text", "intake", false, false, true, "", "");
        reg("intakeTabGmailThreadIdCol", "Gmail Thread ID", "Gmail Thread ID", "text", "intake", false, false, true, "", "");
        reg("intakeTabIntakeTypeCol", "Intake Type", "Intake Type", "text", "intake", false, false, true, "", "");
        reg("intakeTabTimestampCol", "Timestamp", "Timestamp", "date", "intake", false, false, true, "", "");
        reg("intakeTabToCol", "To", "To", "text", "intake", false, false, true, "", "");
        reg("intakeTabFromCol", "From", "From", "text", "intake", false, false, true, "", "");
        reg("intakeTabSubjectCol", "Subject", "Subject", "text", "intake", false, false, true, "", "");
        reg("intakeTabBodyCol", "Body", "Body", "text", "intake", false, false, true, "", "");

        // ============================================================
        // INTAKE TAB FIELDS - SYSTEM-GENERATED (includeInOnboarding=true means auto-create)
        // ============================================================

        reg("intakeTabProcessingStatusCol", "Processing Status", "Processing Status", "text", "intake", true, false, true, "", "");

        reg("intakeTabCleanedEmailCol", "Cleaned Email", "Cleaned Email", "text", "intake", true, true, true,
            "cleanedEmail",
            "EMAIL RULES:\n"
            + "1. cleanedEmail should be the external CRM candidate email.\n"
            + "2. Use crmCandidateEmail as the primary source.\n"
            + "3. Only use to/from/body if crmCandidateEmail is blank or clearly malformed.\n"
            + "4. Never return an internal email.\n"
            + "5. If no valid external email can be found, return cleanedEmail as an empty string.");

        reg("intakeTabExtractedFirstNameCol", "Extracted First Name", "Extracted First Name", "text", "intake", true, true, true,
            "firstName",
            "NAME RULES:\n"
            + "1. firstName and lastName should belong to the external CRM candidate, not an internal person.\n"
            + "2. Names often appear in the email sender display name, email signature, or sign-off.\n"
            + "3. In inbound emails, the signature often belongs to the external investor.\n"
            + "4. In outbound emails, the signature often belongs to the internal sender, so do not use the outbound signature as investor identity.\n"
            + "5. If only the first name is clear, return firstName and leave lastName empty.\n"
            + "6. If only the last name is clear, return lastName and leave firstName empty unless firstName is clear elsewhere.\n"
            + "7. Do not infer a last name from an email domain unless the full name is clearly shown.");

        reg("intakeTabExtractedLastNameCol", "Extracted Last Name", "Extracted Last Name", "text", "intake", true, true, true,
            "lastName", "");

        reg("intakeTabExtractedFundNameCol", "Extracted Fund Name", "Extracted Fund Name", "text", "intake", true, true, true,
            "fundName",
            "FUND NAME RULES:\n"
            + "1. fundName should be the external investor's fund, firm, foundation, bank, family office, or investment organization.\n"
            + "2. Fund names often appear in email domains, signatures, sender organization names, or phrases like Partner at, Managing Director at, from, or on behalf of.\n"
            + "3. Do not return the internal fund name.\n"
            + "4. Do not invent a fund name from a generic email domain like gmail.com, outlook.com, yahoo.com, icloud.com, or protonmail.com.\n"
            + "5. If the organization is not clear, return an empty string.\n"
            + "6. Examples of fund-like names include: Sequoia Capital, Andreessen Horowitz, Accel, Bessemer Venture Partners, General Catalyst, Lightspeed Venture Partners, Founders Fund, Khosla Ventures, NEA, Insight Partners, Index Ventures, Union Square Ventures, Kapor Capital, Acumen, BlueOrchard, FMO, LGT Venture Philanthropy, Global Innovation Fund, ImpactAssets, Omidyar Network, Ford Foundation, Rockefeller Foundation.");

        reg("intakeTabExtractedFundWebsiteCol", "Extracted Fund Website", "Extracted Fund Website", "text", "intake", true, true, true,
            "fundWebsite",
            "WEBSITE RULES:\n"
            + "1. fundWebsite should be the external investor organization's website.\n"
            + "2. Do not return a random link mentioned in the email.\n"
            + "3. Do not return links to scheduling tools, LinkedIn, Zoom, Google Meet, DocSend, Dropbox, Google Drive, Calendly, YouTube, news articles, PDFs, unsubscribe links, or tracking links.\n"
            + "4. Do not return the internal website.\n"
            + "5. A good website usually matches or closely resembles the fundName or the external email domain.\n"
            + "6. If multiple links exist, only return the one that clearly belongs to the external investor's organization.\n"
            + "7. If the website is not clearly tied to the fundName or external email domain, return an empty string.\n"
            + "8. Return websites as domains only when possible, like examplefund.com.\n"
            + "9. If you do not see a website in the email body itself, look at the domain of the external email. "
            + "If that domain is not a common email provider like google.com, yahoo.com, aol.com, etc. and appears like a firm's domain, it is likely the website. \n"
            + "for example email address: jsmith@harborfoundation.ca, harborfoundation.ca is a good guess");

        reg("intakeTabConversationLabelCol", "Conversation Label", "Conversation Label", "text", "intake", true, true, true,
            "conversationLabel",
            "CONVERSATION LABEL RULES:\n"
            + "conversationLabel must be exactly one of these labels:\n"
            + "- Reached Out\n"
            + "- First Interest\n"
            + "- Meetings\n"
            + "- Prospective Close\n"
            + "- Rejected\n\n"
            + "Label definitions:\n"
            + "Reached Out = first contact, cold outreach, intro, or no meaningful reply yet.\n"
            + "First Interest = curiosity, reply, prior discussion, request for information, or early interest.\n"
            + "Meetings = call, meeting, deck review, diligence discussion, scheduling, or follow-up after a meeting. Key word: 'discussion' \n"
            + "Prospective Close = investor sounds close to committing, allocation approval, or final investment decision.\n"
            + "Rejected = investor declines, passes, says not a fit, or says they cannot invest.");

        reg("intakeTabConversationSummaryCol", "Conversation Summary", "Conversation Summary", "text", "intake", true, true, true,
            "oneSentenceSummary",
            "ONE SENTENCE SUMMARY RULES:\n"
            + "1. oneSentenceSummary should be one concise sentence summarizing the meaningful investor interaction.\n"
            + "2. Mention what happened, requested materials, interest level, scheduling, rejection, or next step if clear.\n"
            + "3. Do not include internal-only signature details.\n"
            + "4. If the email has little substance, summarize it conservatively.");

        reg("intakeTabUpdatedCrmCol", "Updated CRM", "Updated CRM", "text", "intake", true, false, true, "", "");
        reg("intakeTabNeedsReviewCol", "Needs Review", "Needs Review", "text", "intake", true, false, true, "", "");
        reg("intakeTabInteractionRecordCol", "Interaction Record JSON", "Interaction Record JSON", "json", "intake", true, false, true, "", "");
    }

    // Default registration: side is resolved from HUMAN_KEYS (human) else machine.
    private static void reg(
        String key,
        String columnName,
        String displayName,
        String fieldType,
        String tabGroup,
        boolean includeInOnboarding,
        boolean includeInAIExtraction,
        boolean includeInSummary,
        String extractionJsonKey,
        String aiExtractionInstruction)
    {
        String side = HUMAN_KEYS.contains(key) ? CRMField.SIDE_HUMAN : CRMField.SIDE_MACHINE;
        reg(key, columnName, displayName, fieldType, tabGroup,
            includeInOnboarding, includeInAIExtraction, includeInSummary,
            extractionJsonKey, aiExtractionInstruction, side, false);
    }

    // Explicit-side registration (used for the divider and deprecated columns).
    private static void reg(
        String key,
        String columnName,
        String displayName,
        String fieldType,
        String tabGroup,
        boolean includeInOnboarding,
        boolean includeInAIExtraction,
        boolean includeInSummary,
        String extractionJsonKey,
        String aiExtractionInstruction,
        String side,
        boolean deprecated)
    {
        CRMField field = new CRMField(
            key, columnName, displayName, fieldType, tabGroup,
            includeInOnboarding, includeInAIExtraction, includeInSummary,
            extractionJsonKey, aiExtractionInstruction, "",
            side, deprecated
        );
        ALL_FIELDS.add(field);
        BY_KEY.put(key, field);
    }

    public static ArrayList<CRMField> getAllFields()
    {
        return new ArrayList<>(ALL_FIELDS);
    }

    public static ArrayList<CRMField> getMainTabFields()
    {
        ArrayList<CRMField> result = new ArrayList<>();
        for (CRMField f : ALL_FIELDS)
        {
            if ("main".equals(f.tabGroup))
            {
                result.add(f);
            }
        }
        return result;
    }

    // Main fields provisioned LEFT of the divider (dividerlists.md step 1).
    public static ArrayList<CRMField> getMainHumanFields()
    {
        ArrayList<CRMField> result = new ArrayList<>();
        for (CRMField f : ALL_FIELDS)
        {
            if ("main".equals(f.tabGroup) && CRMField.SIDE_HUMAN.equals(f.side))
            {
                result.add(f);
            }
        }
        return result;
    }

    // Main fields provisioned RIGHT of the divider (dividerlists.md step 1).
    public static ArrayList<CRMField> getMainMachineFields()
    {
        ArrayList<CRMField> result = new ArrayList<>();
        for (CRMField f : ALL_FIELDS)
        {
            if ("main".equals(f.tabGroup) && CRMField.SIDE_MACHINE.equals(f.side))
            {
                result.add(f);
            }
        }
        return result;
    }

    // The single divider field (dividerlists.md step 2), or null if none registered.
    public static CRMField getDividerField()
    {
        for (CRMField f : ALL_FIELDS)
        {
            if (CRMField.SIDE_DIVIDER.equals(f.side))
            {
                return f;
            }
        }
        return null;
    }

    public static ArrayList<CRMField> getIntakeTabFields()
    {
        ArrayList<CRMField> result = new ArrayList<>();
        for (CRMField f : ALL_FIELDS)
        {
            if ("intake".equals(f.tabGroup))
            {
                result.add(f);
            }
        }
        return result;
    }

    public static ArrayList<CRMField> getOnboardingFields()
    {
        ArrayList<CRMField> result = new ArrayList<>();
        for (CRMField f : ALL_FIELDS)
        {
            if (f.includeInOnboarding)
            {
                result.add(f);
            }
        }
        return result;
    }

    public static ArrayList<CRMField> getAIExtractionFields()
    {
        ArrayList<CRMField> result = new ArrayList<>();
        for (CRMField f : ALL_FIELDS)
        {
            if (f.includeInAIExtraction)
            {
                result.add(f);
            }
        }
        return result;
    }

    public static ArrayList<CRMField> getSummaryFields()
    {
        ArrayList<CRMField> result = new ArrayList<>();
        for (CRMField f : ALL_FIELDS)
        {
            if (f.includeInSummary)
            {
                result.add(f);
            }
        }
        return result;
    }

    public static CRMField getByKey(String key)
    {
        return BY_KEY.get(key);
    }
    // ============================================================
    // COLUMN PROVISIONING — shared helpers (dividerlists.md steps 4 & 6)
    // ============================================================
    //
    // All machine-side provisioning routes through ensureColumnsRightOfDivider,
    // which enforces the "err toward a new column" safeguard: if a machine header
    // resolves to a column strictly LEFT of the divider it is treated as the GP's
    // own column and a new, disambiguated "(Liminer)" column is provisioned to the
    // right instead of overwriting it. When no divider is present yet (legacy
    // sheets) the guard is inert and behavior matches the original append-if-missing
    // provisioners. Idempotent; run ONCE, single-threaded, before parallel row work.
    // Column-by-column writes only. Writing the header row as one rectangle
    // would clobber every GP column that happens to fall between the ones we
    // provision — see README "Sheets I/O".

    private static final String LIMINER_SUFFIX = " (Liminer)";

    // The identity + score columns the market-intelligence rollup writes.
    private static final String[] MARKET_INTELLIGENCE_KEYS = {
        "mainTabCrdNumberCol",
        "mainTabCikNumberCol",
        "mainTabLeiCol",
        "mainTabEinCol",
        "mainTabIdentityStatusCol",
        "mainTabResourcesScoreCol",
        "mainTabFitScoreCol",
        "mainTabProbabilityNowCol",
        "mainTabLastIntelDateCol",
        "mainTabIntelStatusCol",
        "mainTabIntelligenceJsonCol"
    };

    // The three output columns the Relationship Summary workflow writes.
    private static final String[] RELATIONSHIP_SUMMARY_KEYS = {
        "mainTabOutstandingCommitmentsCol",
        "mainTabRelationshipSummaryJsonCol",
        "mainTabRelationshipSummaryDateCol"
    };

    // The two output columns the Investor Brief workflow writes.
    private static final String[] INVESTOR_BRIEF_KEYS = {
        "mainTabInvestorBriefJsonCol",
        "mainTabLastBriefGeneratedCol"
    };

    // The Investor Scout evidence column (identity-key columns reuse the
    // existing Market Intelligence keys via ensureMarketIntelligenceColumns).
    private static final String[] SCOUT_EVIDENCE_KEYS = {
        "mainTabScoutEvidenceCol"
    };

    // The Tier-1 priority-signal columns (priorityscoringv2 §7.2). The two human
    // projections (Strategic Value, Action Urgency) plus Priority Reason are human
    // side but the Tier-1 processor still needs them provisioned; ensureColumns
    // RightOfDivider will leave any that already exist (left of divider) alone via
    // the collision guard, so they resolve to the GP-visible human columns.
    private static final String[] PRIORITY_SIGNAL_KEYS = {
        "mainTabPrioritySignalJsonCol",
        "mainTabPrioritySignalDateCol"
    };

    // Resolve the divider's 1-indexed column from the header map, or -1 if absent.
    private static int resolveDividerColumn(
        SessionContext context0, HashMap<String, Integer> headerMap0)
    {
        String configured0 = context0 == null || context0.config == null
            ? "" : context0.config.getCol("mainTabDividerCol");
        if (configured0 != null && !configured0.trim().isEmpty())
        {
            Integer c0 = headerMap0.get(configured0);
            if (c0 != null) return c0;
        }
        Integer c1 = headerMap0.get(DIVIDER_HEADER);
        return c1 == null ? -1 : c1;
    }

    private static int nextEmptyColumn(HashMap<String, Integer> headerMap0)
    {
        int nextCol0 = 1;
        for (int col0 : headerMap0.values())
        {
            if (col0 >= nextCol0) nextCol0 = col0 + 1;
        }
        return nextCol0;
    }

    // Append a batch of headers contiguously starting at the next empty column:
    // ONE grid expansion + ONE header-row write for the whole batch, instead of
    // two write calls per header (which blows the Sheets 60-writes/min quota
    // during onboarding). The written range covers only brand-new columns past
    // the last used column, so no existing data sits inside it. Updates
    // headerMap0 in place with each header's 1-indexed column.
    private static void appendHeaderColumns(
        String spreadsheetId0, String mainTabName0, int headerRow0,
        HashMap<String, Integer> headerMap0, ArrayList<String> headers0) throws Exception
    {
        if (headers0 == null || headers0.isEmpty()) return;

        int startCol0 = nextEmptyColumn(headerMap0);
        int endCol0 = startCol0 + headers0.size() - 1;
        SheetsApp.expandSheetColumnsIfNeeded(spreadsheetId0, mainTabName0, endCol0);

        String[][] rowValues0 = new String[1][headers0.size()];
        for (int i0 = 0; i0 < headers0.size(); i0++)
        {
            rowValues0[0][i0] = headers0.get(i0);
            headerMap0.put(headers0.get(i0), startCol0 + i0);
            System.out.println("  Added column \"" + headers0.get(i0)
                + "\" at column " + (startCol0 + i0));
        }

        SheetsApp.updateRangeMatrix(
            spreadsheetId0, mainTabName0, headerRow0, startCol0, rowValues0);
    }

    // Ensure each keyed machine column exists to the RIGHT of the divider, applying
    // the left-of-divider collision guard. Updates headerMap0 + context0.config in place.
    public static void ensureColumnsRightOfDivider(
        SessionContext context0,
        String spreadsheetId0,
        String mainTabName0,
        int headerRow0,
        HashMap<String, Integer> headerMap0,
        String[] keys0) throws Exception
    {
        int dividerCol0 = resolveDividerColumn(context0, headerMap0);

        // Pass 1: resolve every key and collect the headers that must be appended.
        ArrayList<String> headersToAppend0 = new ArrayList<>();

        for (String key0 : keys0)
        {
            CRMField field0 = getByKey(key0);
            if (field0 == null) continue;

            String configuredHeader0 = context0.config.getCol(key0);
            String headerToUse0 = (configuredHeader0 != null && !configuredHeader0.trim().isEmpty())
                ? configuredHeader0 : field0.columnName;

            Integer existing0 = headerMap0.get(headerToUse0);

            if (existing0 != null)
            {
                if (dividerCol0 > 0 && existing0 < dividerCol0)
                {
                    // Collision: a GP column of the same name predates the divider.
                    // Never overwrite it — provision a disambiguated Liminer column.
                    String liminerHeader0 = headerToUse0 + LIMINER_SUFFIX;
                    if (!headerMap0.containsKey(liminerHeader0)
                        && !headersToAppend0.contains(liminerHeader0))
                    {
                        headersToAppend0.add(liminerHeader0);
                    }
                    context0.config.setCol(key0, liminerHeader0);
                }
                else
                {
                    // Already provisioned (at/right of divider) — reuse (idempotent).
                    if (configuredHeader0 == null || configuredHeader0.trim().isEmpty())
                    {
                        context0.config.setCol(key0, headerToUse0);
                    }
                }
                continue;
            }

            // Missing entirely — queue for the batch append (right of divider).
            if (!headersToAppend0.contains(headerToUse0))
            {
                headersToAppend0.add(headerToUse0);
            }
            context0.config.setCol(key0, headerToUse0);
        }

        // Pass 2: one grid expansion + one header-row write for all new columns.
        appendHeaderColumns(spreadsheetId0, mainTabName0, headerRow0,
            headerMap0, headersToAppend0);
    }

    public static void ensureMarketIntelligenceColumns(
        SessionContext context0, String spreadsheetId0, String mainTabName0,
        int headerRow0, HashMap<String, Integer> headerMap0) throws Exception
    {
        ensureColumnsRightOfDivider(context0, spreadsheetId0, mainTabName0,
            headerRow0, headerMap0, MARKET_INTELLIGENCE_KEYS);
    }

    public static void ensureRelationshipSummaryColumns(
        SessionContext context0, String spreadsheetId0, String mainTabName0,
        int headerRow0, HashMap<String, Integer> headerMap0) throws Exception
    {
        ensureColumnsRightOfDivider(context0, spreadsheetId0, mainTabName0,
            headerRow0, headerMap0, RELATIONSHIP_SUMMARY_KEYS);
    }

    public static void ensureInvestorBriefColumns(
        SessionContext context0, String spreadsheetId0, String mainTabName0,
        int headerRow0, HashMap<String, Integer> headerMap0) throws Exception
    {
        ensureColumnsRightOfDivider(context0, spreadsheetId0, mainTabName0,
            headerRow0, headerMap0, INVESTOR_BRIEF_KEYS);
    }

    public static void ensureScoutEvidenceColumns(
        SessionContext context0, String spreadsheetId0, String mainTabName0,
        int headerRow0, HashMap<String, Integer> headerMap0) throws Exception
    {
        ensureColumnsRightOfDivider(context0, spreadsheetId0, mainTabName0,
            headerRow0, headerMap0, SCOUT_EVIDENCE_KEYS);
    }

    // Provision the Tier-1 priority-signal columns (priorityscoringv2 §7.2).
    // The JSON + date land right of the divider; the human projections (Strategic
    // Value / Action Urgency / Priority Reason) are ensured left of the divider by
    // ensureHumanFacingColumns, so here we resolve them without disambiguation.
    public static void ensurePrioritySignalColumns(
        SessionContext context0, String spreadsheetId0, String mainTabName0,
        int headerRow0, HashMap<String, Integer> headerMap0) throws Exception
    {
        ensureColumnsRightOfDivider(context0, spreadsheetId0, mainTabName0,
            headerRow0, headerMap0, PRIORITY_SIGNAL_KEYS);

        // The human projections may not exist yet on legacy sheets — ensure them
        // (left of divider, no collision guard: these ARE the GP-visible columns).
        String[] humanProjectionKeys0 = {
            "mainTabStrategicValueCol",
            "mainTabActionUrgencyCol",
            "mainTabPriorityReasonCol"
        };
        ArrayList<String> headersToAppend0 = new ArrayList<>();
        for (String key0 : humanProjectionKeys0)
        {
            CRMField field0 = getByKey(key0);
            if (field0 == null) continue;
            String configuredHeader0 = context0.config.getCol(key0);
            String headerToUse0 = (configuredHeader0 != null && !configuredHeader0.trim().isEmpty())
                ? configuredHeader0 : field0.columnName;
            if (headerMap0.containsKey(headerToUse0))
            {
                if (configuredHeader0 == null || configuredHeader0.trim().isEmpty())
                {
                    context0.config.setCol(key0, headerToUse0);
                }
                continue;
            }
            if (!headersToAppend0.contains(headerToUse0))
            {
                headersToAppend0.add(headerToUse0);
            }
            context0.config.setCol(key0, headerToUse0);
        }
        appendHeaderColumns(spreadsheetId0, mainTabName0, headerRow0,
            headerMap0, headersToAppend0);
    }

    // ============================================================
    // DIVIDER-LAYOUT PROVISIONING (dividerlists.md step 4)
    // ============================================================

    // Ensure every human-facing column exists, LEFT of the divider. A GP's own
    // header still maps onto the GP's existing column (config header was set at
    // onboarding); any missing human column is appended. No collision guard — human
    // columns are the GP's by definition. All missing headers are appended in one
    // batched header-row write (rate-limit safe).
    public static void ensureHumanFacingColumns(
        SessionContext context0, String spreadsheetId0, String mainTabName0,
        int headerRow0, HashMap<String, Integer> headerMap0) throws Exception
    {
        ArrayList<String> headersToAppend0 = new ArrayList<>();
        for (CRMField field0 : getMainHumanFields())
        {
            String configuredHeader0 = context0.config.getCol(field0.key);
            String headerToUse0 = (configuredHeader0 != null && !configuredHeader0.trim().isEmpty())
                ? configuredHeader0 : field0.columnName;
            if (headerMap0.containsKey(headerToUse0))
            {
                if (configuredHeader0 == null || configuredHeader0.trim().isEmpty())
                {
                    context0.config.setCol(field0.key, headerToUse0);
                }
                continue;
            }
            if (!headersToAppend0.contains(headerToUse0))
            {
                headersToAppend0.add(headerToUse0);
            }
            context0.config.setCol(field0.key, headerToUse0);
        }
        appendHeaderColumns(spreadsheetId0, mainTabName0, headerRow0,
            headerMap0, headersToAppend0);
    }

    // Place the single divider column (dividerlists.md step 4). Idempotent:
    //   1. If the divider already resolves in the header map -> FREEZE (never move it).
    //   2. Otherwise place it immediately after the last human-facing column. If that
    //      slot is already occupied (legacy sheet with interleaved machine columns),
    //      INSERT a real column so nothing is overwritten and machine columns shift
    //      right; else append at the next empty column.
    //   3. Draw the SOLID vertical border on the divider's right edge.
    // headerMap0 + context0.config are updated in place.
    public static void ensureDivider(
        SessionContext context0, String spreadsheetId0, String mainTabName0,
        int headerRow0, HashMap<String, Integer> headerMap0) throws Exception
    {
        CRMField dividerField0 = getDividerField();
        String dividerHeader0 = dividerField0 != null ? dividerField0.columnName : DIVIDER_HEADER;

        String configuredHeader0 = context0.config.getCol("mainTabDividerCol");
        String headerToUse0 = (configuredHeader0 != null && !configuredHeader0.trim().isEmpty())
            ? configuredHeader0 : dividerHeader0;

        // Step 1: freeze if already present.
        if (headerMap0.containsKey(headerToUse0))
        {
            context0.config.setCol("mainTabDividerCol", headerToUse0);
            System.out.println("  Divider already present at column "
                + headerMap0.get(headerToUse0) + " — frozen.");
            return;
        }

        // Step 2: compute the slot immediately after the last human-facing column.
        int lastHumanCol0 = 0;
        for (CRMField field0 : getMainHumanFields())
        {
            String h0 = context0.config.getCol(field0.key);
            if (h0 == null || h0.trim().isEmpty()) h0 = field0.columnName;
            Integer c0 = headerMap0.get(h0);
            if (c0 != null && c0 > lastHumanCol0) lastHumanCol0 = c0;
        }

        int dividerCol0;
        int nextEmpty0 = nextEmptyColumn(headerMap0);

        if (lastHumanCol0 == 0 || lastHumanCol0 + 1 >= nextEmpty0)
        {
            // No human columns yet, or the slot after the last human column is the
            // next empty column already (fresh onboarding order human->divider->machine).
            dividerCol0 = nextEmpty0;
            SheetsApp.expandSheetColumnsIfNeeded(spreadsheetId0, mainTabName0, dividerCol0);
        }
        else
        {
            // Legacy sheet: machine columns already sit after the last human column.
            // Insert a real column so the divider lands between them, non-destructively.
            dividerCol0 = lastHumanCol0 + 1;
            SheetsApp.insertColumn(spreadsheetId0, mainTabName0, dividerCol0);
            // Shift every mapped column at/after the insert point right by one.
            HashMap<String, Integer> shifted0 = new HashMap<>();
            for (java.util.Map.Entry<String, Integer> e0 : headerMap0.entrySet())
            {
                int v0 = e0.getValue();
                shifted0.put(e0.getKey(), v0 >= dividerCol0 ? v0 + 1 : v0);
            }
            headerMap0.clear();
            headerMap0.putAll(shifted0);
        }

        SheetsApp.updateCell(spreadsheetId0, mainTabName0, headerRow0, dividerCol0, headerToUse0);
        headerMap0.put(headerToUse0, dividerCol0);
        context0.config.setCol("mainTabDividerCol", headerToUse0);

        // Step 3: draw the visual divider border on the column's right edge.
        try
        {
            SheetsApp.columnBorder(spreadsheetId0, mainTabName0, dividerCol0, "SOLID");
        }
        catch (Exception e0)
        {
            System.out.println("  WARNING: could not draw divider border: " + e0.getMessage());
        }

        System.out.println("  Placed divider \"" + headerToUse0 + "\" at column " + dividerCol0 + ".");
    }

    // Ensure every machine-facing column exists to the RIGHT of the divider, with
    // the collision guard (dividerlists.md steps 4 & 6).
    public static void ensureMachineFacingColumns(
        SessionContext context0, String spreadsheetId0, String mainTabName0,
        int headerRow0, HashMap<String, Integer> headerMap0) throws Exception
    {
        ArrayList<CRMField> machineFields0 = getMainMachineFields();
        String[] keys0 = new String[machineFields0.size()];
        for (int i0 = 0; i0 < machineFields0.size(); i0++)
        {
            keys0[i0] = machineFields0.get(i0).key;
        }
        ensureColumnsRightOfDivider(context0, spreadsheetId0, mainTabName0,
            headerRow0, headerMap0, keys0);
    }
}
