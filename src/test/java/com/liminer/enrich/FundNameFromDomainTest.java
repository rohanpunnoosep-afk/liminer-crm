package com.liminer.enrich;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the domain-anchored fund-name backstop.
 *
 * The bug it exists for: lloyd@nelsonadvisors.co.uk came through email intake with a
 * blank Extracted Fund Name, the background check then resolved his bio on
 * nelsonadvisors.co.uk and read "Co-Founder of Nelson Advisors" out of it, and Fund Name
 * in the CRM still finished the run empty — because every path that could set it went
 * through LinkedIn, and LinkedIn resolution had failed for this contact.
 *
 * Offline: no Sheets, no SERP, no OpenAI.
 */
public class FundNameFromDomainTest
{
    @Test
    public void confirmsEvidenceNameAgainstDomainLabel()
    {
        BasicBackgroundChecker.BackgroundCheckResult result = resultWithWorkHistory(
            "[{\"date_range\":\"Current\",\"company\":\"Nelson Advisors\","
            + "\"title\":\"Partner and Co-Founder\"},"
            + "{\"date_range\":\"2012-2020\",\"company\":\"Zesty\",\"title\":\"Co-Founder\"}]");

        BasicBackgroundChecker.resolveFundNameFromDomain(result, "nelsonadvisors.co.uk");

        assertEquals("Nelson Advisors", result.fundName.value);
        assertTrue(result.fundName.confidence >= 0.85,
            "a domain-confirmed name should clear the auto-write bar");
    }

    @Test
    public void matchesAfterDroppingATrailingDescriptorTheDomainOmits()
    {
        BasicBackgroundChecker.BackgroundCheckResult result = resultWithWorkHistory(
            "[{\"date_range\":\"Current\",\"company\":\"Harbor Capital LLC\",\"title\":\"MD\"}]");

        BasicBackgroundChecker.resolveFundNameFromDomain(result, "harborcapital.com");

        assertEquals("Harbor Capital LLC", result.fundName.value);
    }

    @Test
    public void fallsBackToTheDomainLabelWhenNoEvidenceNameMatches()
    {
        BasicBackgroundChecker.BackgroundCheckResult result = resultWithWorkHistory(
            "[{\"date_range\":\"Current\",\"company\":\"Some Other Firm\",\"title\":\"Analyst\"}]");

        BasicBackgroundChecker.resolveFundNameFromDomain(result, "meridian-partners.com");

        assertEquals("Meridian Partners", result.fundName.value);
        assertTrue(result.fundName.confidence >= BasicBackgroundChecker.FUND_NAME_BLANK_CELL_MIN_CONFIDENCE0,
            "a domain-only name must still be good enough to fill a blank cell");
    }

    @Test
    public void splitsARunTogetherDescriptorOffTheDomainLabel()
    {
        // The Nelson Advisors case with no bio evidence to confirm against: the label
        // alone must still read as a name, not as "Nelsonadvisors".
        assertEquals("Nelson Advisors", BasicBackgroundChecker.titleCaseDomainLabel("nelsonadvisors"));
        assertEquals("Harbor Foundation", BasicBackgroundChecker.titleCaseDomainLabel("harborfoundation"));
        assertEquals("Harbor Capital", BasicBackgroundChecker.titleCaseDomainLabel("harborcapital"));
        assertEquals("Meridian Partners", BasicBackgroundChecker.titleCaseDomainLabel("meridian-partners"));
    }

    @Test
    public void leavesOrdinaryWordsWhole()
    {
        // "bank" and "group" are not splittable descriptors precisely so these stay intact
        // — "Scotia Bank" and "Citi Group" are not how those firms write their names.
        assertEquals("Scotiabank", BasicBackgroundChecker.titleCaseDomainLabel("scotiabank"));
        assertEquals("Citigroup", BasicBackgroundChecker.titleCaseDomainLabel("citigroup"));
        // The stem would be too short to be a name, so no split happens.
        assertEquals("Advisors", BasicBackgroundChecker.titleCaseDomainLabel("advisors"));
    }

    @Test
    public void neverOverwritesAFundNameThatIsAlreadyResolved()
    {
        BasicBackgroundChecker.BackgroundCheckResult result = resultWithWorkHistory("[]");
        result.fundName = new BasicBackgroundChecker.ResolvedField("Acme Ventures", 0.90);

        BasicBackgroundChecker.resolveFundNameFromDomain(result, "nelsonadvisors.co.uk");

        assertEquals("Acme Ventures", result.fundName.value);
    }

    @Test
    public void leavesFundNameBlankForAPublicOrUselessDomain()
    {
        BasicBackgroundChecker.BackgroundCheckResult result = resultWithWorkHistory("[]");

        BasicBackgroundChecker.resolveFundNameFromDomain(result, "");

        assertEquals("", result.fundName.value);
    }

    @Test
    public void readsMultiPartPublicSuffixes()
    {
        assertEquals("nelsonadvisors", BasicBackgroundChecker.registrableLabel("nelsonadvisors.co.uk"));
        assertEquals("harborfoundation", BasicBackgroundChecker.registrableLabel("www.harborfoundation.ca"));
        assertEquals("meridian", BasicBackgroundChecker.registrableLabel("meridian.com.au"));
        assertEquals("", BasicBackgroundChecker.registrableLabel("localhost"));
    }

    @Test
    public void matchIgnoresSpacingPunctuationAndCase()
    {
        ArrayList<String> candidates = new ArrayList<>(
            Arrays.asList("Zesty", "O'Brien & Sons Capital"));

        assertEquals("O'Brien & Sons Capital",
            BasicBackgroundChecker.matchCandidateToDomainLabel(candidates, "obriensonscapital"));
        assertEquals("",
            BasicBackgroundChecker.matchCandidateToDomainLabel(candidates, "nothinglikethis"));
    }

    private static BasicBackgroundChecker.BackgroundCheckResult resultWithWorkHistory(String workHistoryJson)
    {
        BasicBackgroundChecker.BackgroundCheckResult result =
            new BasicBackgroundChecker.BackgroundCheckResult();
        result.contactPastWorkExperience =
            new BasicBackgroundChecker.ResolvedField(workHistoryJson, 0.85);
        return result;
    }
}
