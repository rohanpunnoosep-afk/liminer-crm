package com.liminer.scout;

import com.liminer.brief.DocumentSectionExtractor;
import com.liminer.core.InvestorProfile;
import com.liminer.pipeline.InvestorProfileExtractor;

import java.time.Instant;
import org.json.JSONObject;

/*
 * ScoutNonLinkedInProfileSource — the brochure-then-website half of
 * ScoutFitTierB's profile-extraction waterfall, extracted so a second caller
 * (ScoutProfilePrefetch, task 0089) can run the exact same cost-ordered
 * extraction without duplicating it or ever reaching the LinkedIn/Bright Data
 * step. ScoutFitTierB still owns the LinkedIn fallback and the cache
 * read/write around this; this class only answers "what would extraction
 * from brochure/website produce" and returns null when neither source is
 * available, leaving the LinkedIn/NONE decision to the caller.
 */
public class ScoutNonLinkedInProfileSource
{
    public ScoutNonLinkedInProfileSource()
    {
    }

    public ScoutFitProfileCache.CachedProfile acquire(ScoutUniverseRecord record0) throws Exception
    {
        String brochureText0 = fetchAdvBrochureText(record0);
        if (!isBlank(brochureText0))
        {
            String item8Text0 = DocumentSectionExtractor.extractSection(brochureText0, "Item 8");
            if (isBlank(item8Text0))
            {
                item8Text0 = brochureText0.length() > 8000 ? brochureText0.substring(0, 8000) : brochureText0;
            }
            InvestorProfile profile0 = buildProfileFromText(record0.firmName, item8Text0, "adv-brochure:" + record0.crd);
            return new ScoutFitProfileCache.CachedProfile(profile0, ScoutFitResult.SOURCE_BROCHURE);
        }

        if (!isBlank(record0.website))
        {
            InvestorProfile profile0 = new InvestorProfileExtractor().getInvestorProfile(record0.website);
            return new ScoutFitProfileCache.CachedProfile(profile0, ScoutFitResult.SOURCE_WEBSITE);
        }

        return null;
    }

    // Fetches the ADV Part 2 brochure text for a firm's CRD. Not yet wired to a
    // live source (mirrors ADVStrategyIndicator's fetchBrochureText stub, same
    // reason: the adviserinfo.sec.gov brochure-lookup path is unverified from
    // this environment). Returns "" so the waterfall correctly falls through to
    // the website step until a later task wires this up.
    private String fetchAdvBrochureText(ScoutUniverseRecord record0)
    {
        return "";
    }

    private InvestorProfile buildProfileFromText(String sourceName0, String sourceText0, String sourceLabel0) throws Exception
    {
        JSONObject intelligenceJson0 = InvestorProfileExtractor.analyzeWithOpenAI(sourceName0, sourceText0);
        InvestorProfileExtractor.forceMetadata(intelligenceJson0, Instant.now().toString(), "completed", sourceLabel0);

        InvestorProfile profile0 = InvestorProfile.fromIntelligenceJson(intelligenceJson0);
        profile0.intelligenceJson = intelligenceJson0.toString();
        return profile0;
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }
}
