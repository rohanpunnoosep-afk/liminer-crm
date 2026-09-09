package com.liminer.scout;

import com.liminer.core.InvestorProfile;
import com.liminer.core.SessionContext;
import com.liminer.core.UserAccount;

import java.util.ArrayList;

/*
 * CandidateScoringBasisTestMain — fully offline verification that the scoring basis
 * comes from the GP's declared client profile (context.user.client*), not from an
 * averaged CRM profile. No network, no Sheets, no OpenAI calls. Prints
 * SCORING_BASIS_OK on success.
 */
public class CandidateScoringBasisTestMain
{
    public static void main(String[] args0)
    {
        try
        {
            testBasisUsesDeclaredClientProfile();
            testPromptCarriesDeclaredTagsNotCrmAverage();
            testBlankClientProfileYieldsEmptyBasis();

            System.out.println("SCORING_BASIS_OK");
        }
        catch (Throwable t0)
        {
            System.err.println("SCORING_BASIS_FAILED: " + t0);
            t0.printStackTrace();
            System.exit(1);
        }
    }

    private static void testBasisUsesDeclaredClientProfile() throws Exception
    {
        SessionContext context0 = buildContextWithRealUserProfile();
        CandidateScorer scorer0 = new CandidateScorer();
        InvestorProfile basis0 = scorer0.buildBasisProfileFromClientProfile(context0);

        assertTrue(contains(basis0.sectors, "Healthcare"), "sectors should contain Healthcare, got " + java.util.Arrays.toString(basis0.sectors));
        assertTrue(contains(basis0.sectors, "Diagnostics"), "sectors should contain Diagnostics, got " + java.util.Arrays.toString(basis0.sectors));
        assertTrue(!contains(basis0.sectors, "Finance"), "sectors should NOT contain Finance, got " + java.util.Arrays.toString(basis0.sectors));
        assertTrue(!contains(basis0.sectors, "Banking"), "sectors should NOT contain Banking, got " + java.util.Arrays.toString(basis0.sectors));

        assertTrue(contains(basis0.microsectors, "AI Diagnostics"), "microsectors should contain AI Diagnostics, got " + java.util.Arrays.toString(basis0.microsectors));
        assertTrue(contains(basis0.geographies, "India"), "geographies should contain India, got " + java.util.Arrays.toString(basis0.geographies));
        assertTrue(basis0.investmentThesis.equals(declaredThesis()), "investmentThesis should equal declared thesis, got " + basis0.investmentThesis);
    }

    private static void testPromptCarriesDeclaredTagsNotCrmAverage() throws Exception
    {
        SessionContext context0 = buildContextWithRealUserProfile();
        CandidateScorer scorer0 = new CandidateScorer();
        InvestorProfile basis0 = scorer0.buildBasisProfileFromClientProfile(context0);

        CandidateInvestor candidate0 = new CandidateInvestor("Some Fund", "https://example.com");
        candidate0.ip = new InvestorProfile(
            "Some Fund",
            "VC",
            new String[] {"Healthcare"},
            new String[] {"HealthTech"},
            new String[] {"Canada"},
            new String[0],
            "Backing healthcare companies.",
            ""
        );

        String prompt0 = scorer0.buildOpenAIScoringPrompt(basis0, candidate0);

        assertTrue(prompt0.contains("Healthcare"), "prompt should contain Healthcare");
        assertTrue(!prompt0.contains("Average First Interest Or Better Investor"),
            "prompt should not reference the CRM average basis fund name");
    }

    private static void testBlankClientProfileYieldsEmptyBasis() throws Exception
    {
        UserAccount user0 = buildUser("", "", "", "", "");
        SessionContext context0 = new SessionContext(user0, null);

        CandidateScorer scorer0 = new CandidateScorer();
        InvestorProfile basis0 = scorer0.buildBasisProfileFromClientProfile(context0);

        boolean empty0 = !hasAny(basis0.sectors)
            && !hasAny(basis0.microsectors)
            && !hasAny(basis0.geographies)
            && isBlank(basis0.investmentThesis);

        assertTrue(empty0, "blank client profile should yield an empty basis");

        InvestorProfile nullContextBasis0 = scorer0.buildBasisProfileFromClientProfile(null);
        assertTrue(nullContextBasis0 != null, "null context should yield a non-null empty basis");
        assertTrue(!hasAny(nullContextBasis0.sectors), "null context basis should have no sectors");
    }

    // ---- helpers ---------------------------------------------------------------

    private static SessionContext buildContextWithRealUserProfile()
    {
        UserAccount user0 = buildUser(
            "Healthcare|DevelopingMarkets|Diagnostics|AI Health",
            "Medical Testing|AI Diagnostics|Medical Devices|Point of Care",
            "Africa|India|Canada",
            "",
            declaredThesis()
        );

        return new SessionContext(user0, null);
    }

    private static String declaredThesis()
    {
        return "Invest in firms building AI healthcare diagnostics, medical device manufacturing, "
            + "and healthcare testing devices in countries other than the US for decentralized healthcare";
    }

    private static UserAccount buildUser(
        String sectors0,
        String microsectors0,
        String geography0,
        String stages0,
        String thesis0)
    {
        return new UserAccount(
            "user-1",
            "gp@example.com",
            "Example Fund",
            "crm-1",
            new ArrayList<String>(),
            new ArrayList<String>(),
            "Example Fund",
            "https://example.com",
            "",
            sectors0,
            microsectors0,
            geography0,
            stages0,
            thesis0,
            ""
        );
    }

    private static boolean contains(String[] values0, String target0)
    {
        if (values0 == null)
        {
            return false;
        }

        for (String value0 : values0)
        {
            if (target0.equals(value0))
            {
                return true;
            }
        }

        return false;
    }

    private static boolean hasAny(String[] values0)
    {
        return values0 != null && values0.length > 0;
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }

    private static void assertTrue(boolean condition0, String message0)
    {
        if (!condition0) throw new AssertionError(message0);
    }
}
