package com.liminer.scout;

import com.liminer.core.InvestorProfile;
import com.liminer.enrich.BrightDataLinkedInClient;
import com.liminer.enrich.BrightDataThrottle;
import com.liminer.enrich.LinkedInScrapeResult;
import com.liminer.llm.OpenAIClient;
import com.liminer.pipeline.InvestorProfileExtractor;

import java.time.Instant;
import org.json.JSONObject;

/*
 * ScoutFitTierB — LLM fit scoring for Tier A survivors only (Tier B of the
 * per-client FIT axis). For each candidate it acquires an InvestorProfile from
 * the cheapest sufficient source, in strict cost order:
 *   (a) SEC ADV Part 2 brochure (Item 8 strategy section, via
 *       DocumentSectionExtractor, same extraction pattern as ADVStrategyIndicator)
 *   (b) else a website crawl (WebsiteCrawlerService, via InvestorProfileExtractor)
 *   (c) else a LinkedIn company page (BrightDataLinkedInClient, throttled via
 *       BrightDataThrottle) — only reachable once a later pipeline stage
 *       resolves ScoutUniverseRecord.linkedinCompanyUrl
 * and caches the extracted profile client-independently (crd + snapshotMonth,
 * via ScoutFitProfileCache) so a second client scoring the same candidate pays
 * $0 for extraction. Only the per-client comparison + rationale runs per call.
 *
 * The fit comparison itself is delegated to CandidateScorer (existing sector
 * .30 / microsector .30 / geo .20 / prior-backed .10 / thesis .10 weights) —
 * this class never duplicates those weights. Extraction calls use OpenAIClient's
 * default cheap model; the final rationale uses OpenAIClient.BETTER_MODEL0.
 */
public class ScoutFitTierB
{
    private final ScoutFitProfileCache cache0;
    private final CandidateScorer candidateScorer0;
    private final ScoutNonLinkedInProfileSource nonLinkedInSource0;

    public ScoutFitTierB()
    {
        this(new ScoutFitProfileCache());
    }

    public ScoutFitTierB(ScoutFitProfileCache cache0)
    {
        this.cache0 = cache0;
        this.candidateScorer0 = new CandidateScorer();
        this.nonLinkedInSource0 = new ScoutNonLinkedInProfileSource();
    }

    /*
     * Mutates result0 in place: sets tierBScore, fitScore, rationale, and
     * profileSource. Never touches tierAScore/matchedTerms.
     */
    public void applyTierB(ScoutFitResult result0, ScoutClientProfile client0) throws Exception
    {
        if (result0 == null || result0.record == null)
        {
            return;
        }

        ScoutFitProfileCache.CachedProfile cached0 = acquireProfile(result0.record);

        InvestorProfile basis0 = adaptClientProfile(client0);
        CandidateInvestor candidate0 = new CandidateInvestor(result0.record.firmName, result0.record.website);
        candidate0.ip = cached0.profile;

        candidateScorer0.scoreCandidate(basis0, candidate0);

        int tierBScore0 = (int) Math.round(clamp01(candidate0.finalScore) * 100.0);

        result0.tierBScore = tierBScore0;
        result0.fitScore = tierBScore0;
        result0.profileSource = cached0.profileSource;
        result0.rationale = buildRationale(basis0, candidate0, tierBScore0);
    }

    // -----------------------------------------------------------------------
    // Profile acquisition waterfall + cache
    // -----------------------------------------------------------------------

    private ScoutFitProfileCache.CachedProfile acquireProfile(ScoutUniverseRecord record0) throws Exception
    {
        ScoutFitProfileCache.CachedProfile cached0 = cache0.load(record0.crd, record0.snapshotMonth);
        if (cached0 != null)
        {
            return cached0;
        }

        ScoutFitProfileCache.CachedProfile toCache0 = nonLinkedInSource0.acquire(record0);
        if (toCache0 == null)
        {
            if (!isBlank(record0.linkedinCompanyUrl))
            {
                InvestorProfile profile0 = buildProfileFromLinkedIn(record0);
                toCache0 = new ScoutFitProfileCache.CachedProfile(profile0, ScoutFitResult.SOURCE_LINKEDIN);
            }
            else
            {
                toCache0 = new ScoutFitProfileCache.CachedProfile(new InvestorProfile(), ScoutFitResult.SOURCE_NONE);
            }
        }

        cache0.save(record0.crd, record0.snapshotMonth, toCache0);
        return toCache0;
    }

    private InvestorProfile buildProfileFromText(String sourceName0, String sourceText0, String sourceLabel0) throws Exception
    {
        JSONObject intelligenceJson0 = InvestorProfileExtractor.analyzeWithOpenAI(sourceName0, sourceText0);
        InvestorProfileExtractor.forceMetadata(intelligenceJson0, Instant.now().toString(), "completed", sourceLabel0);

        InvestorProfile profile0 = InvestorProfile.fromIntelligenceJson(intelligenceJson0);
        profile0.intelligenceJson = intelligenceJson0.toString();
        return profile0;
    }

    private InvestorProfile buildProfileFromLinkedIn(ScoutUniverseRecord record0) throws Exception
    {
        BrightDataThrottle.acquire();
        LinkedInScrapeResult scrape0;
        try
        {
            scrape0 = new BrightDataLinkedInClient().scrapeCompany(record0.linkedinCompanyUrl);
        }
        finally
        {
            BrightDataThrottle.release();
        }

        String evidenceText0 = buildLinkedInEvidenceText(scrape0);
        return buildProfileFromText(record0.firmName, evidenceText0, record0.linkedinCompanyUrl);
    }

    private String buildLinkedInEvidenceText(LinkedInScrapeResult scrape0)
    {
        if (scrape0 == null)
        {
            return "";
        }

        StringBuilder sb0 = new StringBuilder();
        sb0.append("Name: ").append(scrape0.name).append("\n");
        sb0.append("Headline: ").append(scrape0.headline).append("\n");
        sb0.append("About: ").append(scrape0.about).append("\n");
        sb0.append("Location: ").append(scrape0.location).append("\n");
        sb0.append("Website: ").append(scrape0.companyWebsite).append("\n");
        return sb0.toString();
    }

    // -----------------------------------------------------------------------
    // Client profile adapter + rationale
    // -----------------------------------------------------------------------

    private InvestorProfile adaptClientProfile(ScoutClientProfile client0)
    {
        InvestorProfile basis0 = new InvestorProfile();
        if (client0 == null)
        {
            return basis0;
        }

        basis0.sectors = client0.sectorTags == null
            ? new String[0] : client0.sectorTags.toArray(new String[0]);
        basis0.geographies = isBlank(client0.geography) ? new String[0] : new String[] { client0.geography };
        basis0.microsectors = new String[0];
        basis0.priorBackedFunds = new String[0];
        basis0.investmentThesis = "";
        return basis0;
    }

    private String buildRationale(InvestorProfile basis0, CandidateInvestor candidate0, int tierBScore0)
    {
        try
        {
            String prompt0 = "You are writing a one-sentence rationale for a ranked investor-fit list in a VC "
                + "fundraising CRM. The candidate scored " + tierBScore0 + "/100 fit against the client's target "
                + "profile.\n\nClient sectors: " + String.join(", ", basis0.sectors)
                + "\nClient geography: " + String.join(", ", basis0.geographies)
                + "\nCandidate fund name: " + safe(candidate0.fundName)
                + "\nExisting subscore explanation: " + safe(candidate0.scoreExplanation)
                + "\n\nReturn one plain-text sentence, no markdown, explaining why this is (or isn't) a good fit.";

            return OpenAIClient.getTextResponse(prompt0, OpenAIClient.BETTER_MODEL0).trim();
        }
        catch (Exception exception0)
        {
            // Fall back to CandidateScorer's own explanation rather than failing
            // the whole candidate over a rationale-polish call.
            return candidate0.scoreExplanation;
        }
    }

    private static double clamp01(double v0)
    {
        if (Double.isNaN(v0) || v0 < 0.0) return 0.0;
        if (v0 > 1.0) return 1.0;
        return v0;
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }

    private static String safe(String value0)
    {
        return value0 == null ? "" : value0;
    }
}
