package com.liminer.scout;

import com.liminer.core.CRMSchemaConfig;
import com.liminer.core.InvestorProfile;
import com.liminer.core.SessionContext;
import com.liminer.enrich.BrightDataLinkedInClient;
import com.liminer.enrich.BrightDataSerpClient;
import com.liminer.enrich.DiscoveredLinkedInTarget;
import com.liminer.enrich.LinkedInScrapeResult;
import com.liminer.enrich.LinkedInUrlExtractor;
import com.liminer.enrich.SerpResult;
import com.liminer.enrich.WebsiteCrawlerService;
import com.liminer.pipeline.InvestorProfileExtractor;
import com.liminer.sheets.SheetsApp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import org.json.JSONObject;

/*
 * CandidateDiscoveryProcessor orchestrates Pipeline 3 Candidate Discovery.
 *
 * Current behavior:
 * 1. Generate people-heavy LinkedIn SERP queries from the saved client profile.
 * 2. Discover LinkedIn person/company targets.
 * 3. For person targets, backfill fund/company LinkedIn + website when possible.
 * 4. For company targets, backfill Contact Person 1/2 when possible.
 * 5. Only append CRM-ready rows: Contact 1 + Fund Name + Fund Website + Fund LinkedIn.
 */
public class CandidateDiscoveryProcessor
{
    private static final int MAX_COLUMNS0 = 200;
    private static final int MAX_CRM_ROWS0 = 2000;

    private SearchTermGenerator searchTermGenerator;
    private BrightDataSerpClient serpClient;
    private LinkedInUrlExtractor linkedInUrlExtractor;
    private BrightDataLinkedInClient linkedInClient;
    private InvestorProfileExtractor investorProfileExtractor;

    public CandidateDiscoveryProcessor()
    {
        searchTermGenerator = new SearchTermGenerator();
        serpClient = new BrightDataSerpClient();
        linkedInUrlExtractor = new LinkedInUrlExtractor();
        linkedInClient = new BrightDataLinkedInClient();
        investorProfileExtractor = new InvestorProfileExtractor();
    }

    public ArrayList<CandidateInvestor> discoverCandidates(
        ArrayList<InvestorProfile> seedProfiles0,
        int maxResultsPerQuery0,
        int maxCandidates0,
        boolean scrapeLinkedIn0,
        boolean scrapeWebsites0,
        boolean extractInvestorProfiles0) throws Exception
    {
        return discoverCandidates(
            seedProfiles0,
            maxResultsPerQuery0,
            maxCandidates0,
            scrapeLinkedIn0,
            scrapeWebsites0,
            extractInvestorProfiles0,
            null
        );
    }

    private ArrayList<CandidateInvestor> discoverCandidates(
        ArrayList<InvestorProfile> seedProfiles0,
        int maxResultsPerQuery0,
        int maxCandidates0,
        boolean scrapeLinkedIn0,
        boolean scrapeWebsites0,
        boolean extractInvestorProfiles0,
        PreEnrichmentCrmIndex crmIndex0) throws Exception
    {
        ArrayList<CandidateInvestor> candidates0 = new ArrayList<CandidateInvestor>();

        if (seedProfiles0 == null || seedProfiles0.size() == 0)
        {
            return candidates0;
        }

        ArrayList<String> queries0 = searchTermGenerator.generateCandidateDiscoveryQueries(seedProfiles0);

        System.out.println("Generated " + queries0.size() + " candidate discovery queries.");
        for (int i = 0; i < queries0.size(); i++)
        {
            System.out.println("Query " + (i + 1) + ": " + queries0.get(i));
        }

        LinkedHashMap<String, DiscoveredLinkedInTarget> targetMap0 = new LinkedHashMap<String, DiscoveredLinkedInTarget>();
        int skippedKnownTargets0 = 0;

        for (String query0 : queries0)
        {
            if (targetMap0.size() >= maxCandidates0)
            {
                break;
            }

            System.out.println("Running SERP query: " + query0);
            ArrayList<SerpResult> serpResults0 = serpClient.search(query0, maxResultsPerQuery0);
            ArrayList<DiscoveredLinkedInTarget> targets0 = linkedInUrlExtractor.extract(serpResults0);
            int originalTargetCount0 = targets0.size();

            if (crmIndex0 != null)
            {
                targets0 = filterKnownCrmTargets(targets0, crmIndex0);
                skippedKnownTargets0 += originalTargetCount0 - targets0.size();
            }

            System.out.println(
                "SERP results: "
                + serpResults0.size()
                + " | LinkedIn targets: "
                + originalTargetCount0
                + " | New targets after CRM pre-filter: "
                + targets0.size()
            );

            addTargetsByType(targetMap0, targets0, maxCandidates0, DiscoveredLinkedInTarget.TYPE_PERSON);
            addTargetsByType(targetMap0, targets0, maxCandidates0, DiscoveredLinkedInTarget.TYPE_COMPANY);
        }

        if (crmIndex0 != null)
        {
            System.out.println("Pre-enrichment CRM duplicate targets skipped: " + skippedKnownTargets0);
        }

        int processedCount0 = 0;

        for (DiscoveredLinkedInTarget target0 : targetMap0.values())
        {
            processedCount0++;
            System.out.println("Building candidate " + processedCount0 + " of " + targetMap0.size() + ": " + target0.url);

            RawCandidateData raw0 = buildRawCandidateFromTarget(
                target0,
                scrapeLinkedIn0,
                scrapeWebsites0
            );

            CandidateInvestor candidate0 = buildCandidateFromRaw(raw0, extractInvestorProfiles0);
            candidates0.add(candidate0);
        }

        return dedupeCandidates(candidates0);
    }

    private void addTargetsByType(
        LinkedHashMap<String, DiscoveredLinkedInTarget> targetMap0,
        ArrayList<DiscoveredLinkedInTarget> targets0,
        int maxCandidates0,
        String type0)
    {
        for (DiscoveredLinkedInTarget target0 : targets0)
        {
            if (targetMap0.size() >= maxCandidates0)
            {
                break;
            }

            if (!type0.equals(target0.targetType))
            {
                continue;
            }

            if (!targetMap0.containsKey(target0.url))
            {
                targetMap0.put(target0.url, target0);
            }
        }
    }

    private ArrayList<DiscoveredLinkedInTarget> filterKnownCrmTargets(
        ArrayList<DiscoveredLinkedInTarget> targets0,
        PreEnrichmentCrmIndex crmIndex0)
    {
        ArrayList<DiscoveredLinkedInTarget> filtered0 = new ArrayList<DiscoveredLinkedInTarget>();

        if (targets0 == null)
        {
            return filtered0;
        }

        for (DiscoveredLinkedInTarget target0 : targets0)
        {
            if (target0 == null)
            {
                continue;
            }

            String skipReason0 = getPreEnrichmentSkipReason(target0, crmIndex0);

            if (!RawCandidateData.isBlank(skipReason0))
            {
                System.out.println(
                    "Skipping known CRM target before enrichment | "
                    + target0.url
                    + " | reason: "
                    + skipReason0
                );
                continue;
            }

            filtered0.add(target0);
        }

        return filtered0;
    }

    private String getPreEnrichmentSkipReason(
        DiscoveredLinkedInTarget target0,
        PreEnrichmentCrmIndex crmIndex0)
    {
        if (target0 == null || crmIndex0 == null)
        {
            return "";
        }

        String normalizedUrl0 = normalizeUrl(target0.url);

        if (target0.isPerson())
        {
            if (!RawCandidateData.isBlank(normalizedUrl0) && crmIndex0.contactLinkedInUrls.contains(normalizedUrl0))
            {
                return "contact LinkedIn already exists in CRM";
            }

            String nameKey0 = extractNameKeyFromLinkedInTitle(target0.serpTitle);
            if (!RawCandidateData.isBlank(nameKey0) && crmIndex0.contactNameKeys.contains(nameKey0))
            {
                return "person name from SERP title already exists in CRM";
            }
        }

        if (target0.isCompany())
        {
            if (!RawCandidateData.isBlank(normalizedUrl0) && crmIndex0.companyLinkedInUrls.contains(normalizedUrl0))
            {
                return "company LinkedIn already exists in CRM";
            }

            String companySlug0 = extractCompanySlugFromLinkedInUrl(target0.url);
            if (!RawCandidateData.isBlank(companySlug0) && crmIndex0.companyLinkedInSlugs.contains(companySlug0))
            {
                return "company LinkedIn slug already exists in CRM";
            }

            String fundFromTitle0 = cleanLinkedInTitle(target0.serpTitle);
            String normalizedFundFromTitle0 = normalizeText(fundFromTitle0);
            if (!RawCandidateData.isBlank(normalizedFundFromTitle0) && crmIndex0.fundNames.contains(normalizedFundFromTitle0))
            {
                return "fund name from SERP title already exists in CRM";
            }

            if (!RawCandidateData.isBlank(companySlug0) && crmIndex0.fundNames.contains(companySlug0))
            {
                return "company LinkedIn slug matches existing fund name";
            }
        }

        return "";
    }

    public String discoverAndAppendColdCandidates(
        SessionContext context0,
        ArrayList<InvestorProfile> seedProfiles0,
        int maxResultsPerQuery0,
        int maxCandidates0,
        boolean scrapeLinkedIn0,
        boolean scrapeWebsites0,
        boolean extractInvestorProfiles0) throws Exception
    {
        PreEnrichmentCrmIndex crmIndex0 = buildPreEnrichmentCrmIndex(context0);

        ArrayList<CandidateInvestor> candidates0 = discoverCandidates(
            seedProfiles0,
            maxResultsPerQuery0,
            maxCandidates0,
            scrapeLinkedIn0,
            scrapeWebsites0,
            extractInvestorProfiles0,
            crmIndex0
        );

        return appendColdCandidatesToCrm(context0, candidates0);
    }

    private RawCandidateData buildRawCandidateFromTarget(
        DiscoveredLinkedInTarget target0,
        boolean scrapeLinkedIn0,
        boolean scrapeWebsites0)
    {
        RawCandidateData raw0 = new RawCandidateData();

        raw0.candidateType = target0.targetType;
        raw0.discoveryQuery = target0.queryUsed;
        raw0.serpTitle = target0.serpTitle;
        raw0.serpSnippet = target0.serpSnippet;
        raw0.serpRank = target0.serpRank;

        if (target0.isPerson())
        {
            raw0.linkedinProfileUrl = target0.url;
        }
        else
        {
            raw0.linkedinCompanyUrl = target0.url;
        }

        if (scrapeLinkedIn0)
        {
            try
            {
                LinkedInScrapeResult scrape0 = linkedInClient.scrape(target0);
                applyLinkedInScrape(raw0, scrape0, true);
            }
            catch (Exception exception0)
            {
                System.out.println("Skipping LinkedIn enrichment for " + target0.url + ": " + exception0.getMessage());
            }
        }
        else
        {
            inferBasicFieldsFromSerp(raw0);
        }

        if (target0.isPerson())
        {
            backfillFundFromPerson(raw0, scrapeLinkedIn0);
        }
        else
        {
            backfillContactFromCompany(raw0, scrapeLinkedIn0);
        }

        if (scrapeWebsites0 && !RawCandidateData.isBlank(raw0.websiteUrl))
        {
            scrapeCandidateWebsite(raw0);
        }

        return raw0;
    }

    private void backfillFundFromPerson(
        RawCandidateData raw0,
        boolean scrapeLinkedIn0)
    {
        if (RawCandidateData.isBlank(raw0.linkedinCompanyUrl) && !RawCandidateData.isBlank(raw0.fundName))
        {
            DiscoveredLinkedInTarget companyTarget0 = findFirstCompanyTargetForFund(raw0.fundName);
            if (companyTarget0 != null)
            {
                raw0.linkedinCompanyUrl = companyTarget0.url;
            }
        }

        if (scrapeLinkedIn0 && !RawCandidateData.isBlank(raw0.linkedinCompanyUrl))
        {
            try
            {
                LinkedInScrapeResult companyScrape0 = linkedInClient.scrapeCompany(raw0.linkedinCompanyUrl);
                applyCompanyScrapeToRaw(raw0, companyScrape0);
            }
            catch (Exception exception0)
            {
                System.out.println("Company LinkedIn backfill failed for " + raw0.linkedinCompanyUrl + ": " + exception0.getMessage());
            }
        }

        backfillSecondContactFromFund(raw0, scrapeLinkedIn0);
    }

    private void backfillContactFromCompany(
        RawCandidateData raw0,
        boolean scrapeLinkedIn0)
    {
        if (RawCandidateData.isBlank(raw0.fundName))
        {
            raw0.fundName = firstNonBlank(raw0.name, raw0.serpTitle, "");
        }

        ArrayList<DiscoveredLinkedInTarget> peopleTargets0 = findPeopleTargetsForFund(raw0.fundName, 2);

        if (peopleTargets0.size() == 0)
        {
            return;
        }

        DiscoveredLinkedInTarget contact1Target0 = peopleTargets0.get(0);
        raw0.linkedinProfileUrl = contact1Target0.url;

        if (scrapeLinkedIn0)
        {
            try
            {
                LinkedInScrapeResult contactScrape0 = linkedInClient.scrapeProfile(contact1Target0.url);
                applyContactScrapeToRaw(raw0, contactScrape0);
            }
            catch (Exception exception0)
            {
                System.out.println("Contact LinkedIn backfill failed for " + contact1Target0.url + ": " + exception0.getMessage());
            }
        }
        else
        {
            inferPersonNameFromSerpTitle(raw0, contact1Target0.serpTitle);
        }

        if (peopleTargets0.size() > 1)
        {
            applySecondContactTarget(raw0, peopleTargets0.get(1), scrapeLinkedIn0);
        }
    }

    private void backfillSecondContactFromFund(
        RawCandidateData raw0,
        boolean scrapeLinkedIn0)
    {
        if (RawCandidateData.isBlank(raw0.fundName))
        {
            return;
        }

        ArrayList<DiscoveredLinkedInTarget> peopleTargets0 = findPeopleTargetsForFund(raw0.fundName, 3);

        for (DiscoveredLinkedInTarget target0 : peopleTargets0)
        {
            if (normalizeUrl(target0.url).equals(normalizeUrl(raw0.linkedinProfileUrl)))
            {
                continue;
            }

            applySecondContactTarget(raw0, target0, scrapeLinkedIn0);
            return;
        }
    }

    private void applySecondContactTarget(
        RawCandidateData raw0,
        DiscoveredLinkedInTarget target0,
        boolean scrapeLinkedIn0)
    {
        if (target0 == null || !target0.isPerson())
        {
            return;
        }

        raw0.contact2LinkedInUrl = target0.url;

        if (scrapeLinkedIn0)
        {
            try
            {
                LinkedInScrapeResult contact2Scrape0 = linkedInClient.scrapeProfile(target0.url);
                raw0.contact2FirstName = firstNonBlank(contact2Scrape0.firstName, raw0.contact2FirstName, "");
                raw0.contact2LastName = firstNonBlank(contact2Scrape0.lastName, raw0.contact2LastName, "");
                raw0.contact2Position = firstNonBlank(contact2Scrape0.position, contact2Scrape0.headline, raw0.contact2Position);
            }
            catch (Exception exception0)
            {
                System.out.println("Contact 2 LinkedIn backfill failed for " + target0.url + ": " + exception0.getMessage());
            }
        }
        else
        {
            inferSecondPersonNameFromSerpTitle(raw0, target0.serpTitle);
        }
    }

    private DiscoveredLinkedInTarget findFirstCompanyTargetForFund(String fundName0)
    {
        ArrayList<String> queries0 = searchTermGenerator.generateCompanyLinkedInQueries(fundName0);

        for (String query0 : queries0)
        {
            try
            {
                ArrayList<SerpResult> results0 = serpClient.search(query0, 5);
                ArrayList<DiscoveredLinkedInTarget> companies0 = linkedInUrlExtractor.extractCompanies(results0);

                if (!companies0.isEmpty())
                {
                    return companies0.get(0);
                }
            }
            catch (Exception exception0)
            {
                System.out.println("Company LinkedIn search failed for " + fundName0 + ": " + exception0.getMessage());
            }
        }

        return null;
    }

    private ArrayList<DiscoveredLinkedInTarget> findPeopleTargetsForFund(String fundName0, int maxPeople0)
    {
        ArrayList<DiscoveredLinkedInTarget> people0 = new ArrayList<DiscoveredLinkedInTarget>();
        HashSet<String> seen0 = new HashSet<String>();
        ArrayList<String> queries0 = searchTermGenerator.generatePeopleAtFundQueries(fundName0);

        for (String query0 : queries0)
        {
            if (people0.size() >= maxPeople0)
            {
                break;
            }

            try
            {
                ArrayList<SerpResult> results0 = serpClient.search(query0, 5);
                ArrayList<DiscoveredLinkedInTarget> personTargets0 = linkedInUrlExtractor.extractPeople(results0);

                for (DiscoveredLinkedInTarget target0 : personTargets0)
                {
                    if (people0.size() >= maxPeople0)
                    {
                        break;
                    }

                    if (!seen0.contains(target0.url))
                    {
                        seen0.add(target0.url);
                        people0.add(target0);
                    }
                }
            }
            catch (Exception exception0)
            {
                System.out.println("People-at-fund search failed for " + fundName0 + ": " + exception0.getMessage());
            }
        }

        return people0;
    }

    private void scrapeCandidateWebsite(RawCandidateData raw0)
    {
        try
        {
            String normalizedWebsite0 = WebsiteCrawlerService.normalizeRootUrl(raw0.websiteUrl);
            java.util.LinkedHashMap<String, String> scrapedPages0 = WebsiteCrawlerService.crawlWebsite(normalizedWebsite0);
            raw0.websiteUrl = normalizedWebsite0;
            raw0.rawWebsiteText = InvestorProfileExtractor.buildOpenAiSourceText(scrapedPages0);
            raw0.rawWebsiteJson = WebsiteCrawlerService.buildOutputJson(normalizedWebsite0, scrapedPages0);
        }
        catch (Exception exception0)
        {
            System.out.println("Website scrape failed for " + raw0.websiteUrl + ": " + exception0.getMessage());
        }
    }

    private void applyLinkedInScrape(RawCandidateData raw0, LinkedInScrapeResult scrape0, boolean originalScrape0)
    {
        if (scrape0 == null)
        {
            return;
        }

        if (originalScrape0)
        {
            raw0.rawLinkedInJson = scrape0.rawJson;
        }

        if (DiscoveredLinkedInTarget.TYPE_PERSON.equals(raw0.candidateType))
        {
            applyContactScrapeToRaw(raw0, scrape0);
        }
        else
        {
            applyCompanyScrapeToRaw(raw0, scrape0);
        }
    }

    private void applyContactScrapeToRaw(RawCandidateData raw0, LinkedInScrapeResult scrape0)
    {
        if (scrape0 == null)
        {
            return;
        }

        raw0.rawContactLinkedInJson = firstNonBlank(scrape0.rawJson, raw0.rawContactLinkedInJson, "");
        raw0.name = firstNonBlank(scrape0.name, raw0.name, raw0.serpTitle);
        raw0.firstName = firstNonBlank(scrape0.firstName, raw0.firstName, "");
        raw0.lastName = firstNonBlank(scrape0.lastName, raw0.lastName, "");
        raw0.position = firstNonBlank(scrape0.position, scrape0.headline, raw0.position);
        raw0.fundName = firstNonBlank(scrape0.currentCompanyName, raw0.fundName, "");
        raw0.websiteUrl = firstNonBlank(scrape0.companyWebsite, raw0.websiteUrl, "");
        raw0.linkedinProfileUrl = firstNonBlank(raw0.linkedinProfileUrl, scrape0.url, "");
        raw0.linkedinCompanyUrl = firstNonBlank(scrape0.currentCompanyLinkedInUrl, raw0.linkedinCompanyUrl, "");
        raw0.country = firstNonBlank(scrape0.country, raw0.country, "");
        raw0.region = firstNonBlank(scrape0.region, raw0.region, scrape0.location);
    }

    private void applyCompanyScrapeToRaw(RawCandidateData raw0, LinkedInScrapeResult scrape0)
    {
        if (scrape0 == null)
        {
            return;
        }

        raw0.rawCompanyLinkedInJson = firstNonBlank(scrape0.rawJson, raw0.rawCompanyLinkedInJson, "");
        raw0.fundName = firstNonBlank(scrape0.currentCompanyName, scrape0.name, raw0.fundName);
        raw0.name = firstNonBlank(raw0.name, scrape0.name, raw0.fundName);
        raw0.websiteUrl = firstNonBlank(scrape0.companyWebsite, raw0.websiteUrl, "");
        raw0.linkedinCompanyUrl = firstNonBlank(raw0.linkedinCompanyUrl, scrape0.currentCompanyLinkedInUrl, scrape0.url);
        raw0.country = firstNonBlank(scrape0.country, raw0.country, "");
        raw0.region = firstNonBlank(scrape0.region, raw0.region, scrape0.location);
    }

    private void inferBasicFieldsFromSerp(RawCandidateData raw0)
    {
        if (!RawCandidateData.isBlank(raw0.serpTitle))
        {
            raw0.name = raw0.serpTitle;
        }

        if (DiscoveredLinkedInTarget.TYPE_COMPANY.equals(raw0.candidateType))
        {
            raw0.fundName = cleanLinkedInTitle(raw0.serpTitle);
        }
        else
        {
            inferPersonNameFromSerpTitle(raw0, raw0.serpTitle);
        }
    }

    private void inferPersonNameFromSerpTitle(RawCandidateData raw0, String title0)
    {
        if (RawCandidateData.isBlank(title0))
        {
            return;
        }

        String cleaned0 = cleanLinkedInTitle(title0);
        String[] titlePieces0 = cleaned0.split(" - ");
        String namePart0 = titlePieces0.length > 0 ? titlePieces0[0].trim() : cleaned0;

        String[] pieces0 = namePart0.split("\\s+");
        if (pieces0.length >= 1 && RawCandidateData.isBlank(raw0.firstName))
        {
            raw0.firstName = pieces0[0];
        }
        if (pieces0.length >= 2 && RawCandidateData.isBlank(raw0.lastName))
        {
            raw0.lastName = pieces0[pieces0.length - 1];
        }
    }

    private void inferSecondPersonNameFromSerpTitle(RawCandidateData raw0, String title0)
    {
        if (RawCandidateData.isBlank(title0))
        {
            return;
        }

        String cleaned0 = cleanLinkedInTitle(title0);
        String[] titlePieces0 = cleaned0.split(" - ");
        String namePart0 = titlePieces0.length > 0 ? titlePieces0[0].trim() : cleaned0;

        String[] pieces0 = namePart0.split("\\s+");
        if (pieces0.length >= 1 && RawCandidateData.isBlank(raw0.contact2FirstName))
        {
            raw0.contact2FirstName = pieces0[0];
        }
        if (pieces0.length >= 2 && RawCandidateData.isBlank(raw0.contact2LastName))
        {
            raw0.contact2LastName = pieces0[pieces0.length - 1];
        }
    }

    private String cleanLinkedInTitle(String title0)
    {
        if (title0 == null)
        {
            return "";
        }

        return title0
            .replace("| LinkedIn", "")
            .replace("LinkedIn", "")
            .trim();
    }

    private CandidateInvestor buildCandidateFromRaw(
        RawCandidateData raw0,
        boolean extractInvestorProfile0)
    {
        CandidateInvestor candidate0 = CandidateInvestor.fromRawCandidateData(raw0);

        if (extractInvestorProfile0)
        {
            try
            {
                candidate0.ip = investorProfileExtractor.getInvestorProfile(raw0);

                if (candidate0.ip != null && RawCandidateData.isBlank(candidate0.fundName) && !RawCandidateData.isBlank(candidate0.ip.fundName))
                {
                    candidate0.fundName = candidate0.ip.fundName;
                }

                candidate0.syncLegacyAliases();
            }
            catch (Exception exception0)
            {
                System.out.println("InvestorProfile extraction failed for candidate " + candidate0.fundName + ": " + exception0.getMessage());
            }
        }

        return candidate0;
    }

    private ArrayList<CandidateInvestor> dedupeCandidates(ArrayList<CandidateInvestor> candidates0)
    {
        ArrayList<CandidateInvestor> deduped0 = new ArrayList<CandidateInvestor>();
        HashSet<String> seen0 = new HashSet<String>();

        for (CandidateInvestor candidate0 : candidates0)
        {
            if (candidate0 == null)
            {
                continue;
            }

            candidate0.syncLegacyAliases();
            String key0 = candidate0.getDeduplicationKey();

            if (!seen0.contains(key0))
            {
                seen0.add(key0);
                deduped0.add(candidate0);
            }
        }

        return deduped0;
    }

    public static String appendColdCandidatesToCrm(
        SessionContext context0,
        ArrayList<CandidateInvestor> candidates0) throws Exception
    {
        if (context0 == null || context0.config == null)
        {
            return "ERROR: Missing session context.";
        }

        if (candidates0 == null || candidates0.size() == 0)
        {
            return "Candidate discovery complete. No candidates to append.";
        }

        String spreadsheetId0 = context0.config.spreadsheetId;
        String crmTabName0 = context0.config.mainTabName;

        java.util.HashMap<String, Integer> crmHeaderMap0 = SheetsApp.buildHeaderMap(
            spreadsheetId0,
            crmTabName0,
            context0.config.mainTabHeaderRow,
            MAX_COLUMNS0
        );

        PreEnrichmentCrmIndex existingCrmIndex0 = buildPreEnrichmentCrmIndex(context0);

        ArrayList<CandidateInvestor> newCandidates0 = new ArrayList<CandidateInvestor>();
        int skippedNotReady0 = 0;
        int skippedDuplicates0 = 0;

        for (CandidateInvestor candidate0 : candidates0)
        {
            if (candidate0 == null)
            {
                continue;
            }

            candidate0.syncLegacyAliases();

            if (!candidate0.isCrmReady())
            {
                skippedNotReady0++;
                System.out.println(
                    "Skipping candidate not CRM-ready | "
                    + firstNonBlank(candidate0.fundName, candidate0.name, candidate0.linkedInUrl)
                    + " | missing: "
                    + candidate0.getMissingCrmReadyFields()
                );
                continue;
            }

            if (candidateExistsInCrmIndex(candidate0, existingCrmIndex0))
            {
                skippedDuplicates0++;
                continue;
            }

            newCandidates0.add(candidate0);
            addCandidateToCrmIndex(existingCrmIndex0, candidate0);
        }

        if (newCandidates0.size() == 0)
        {
            return "Candidate discovery complete. No CRM-ready new candidates added. Skipped not-ready: "
                + skippedNotReady0
                + ". Skipped duplicates: "
                + skippedDuplicates0
                + ".";
        }

        try
        {
            CandidateScorer scorer0 = new CandidateScorer();
            scorer0.scoreCandidates(context0, newCandidates0);
        }
        catch (Exception exception0)
        {
            System.out.println("Candidate scoring before CRM append failed: " + exception0.getMessage());
            System.out.println("Continuing CRM append without blocking candidate creation.");
        }

        int fundCol0 = getColumn(crmHeaderMap0, context0.config.getCol("mainTabFundNameCol"));
        if (fundCol0 <= 0)
        {
            return "ERROR: Could not find Fund Name column for CRM append.";
        }

        int nextRow0 = SheetsApp.findLastRow(
            spreadsheetId0,
            crmTabName0,
            fundCol0,
            fundCol0,
            MAX_CRM_ROWS0
        ) + 1;

        if (nextRow0 < context0.config.mainTabDataStartRow)
        {
            nextRow0 = context0.config.mainTabDataStartRow;
        }

        writeNewCandidatesToCrmByColumn(
            spreadsheetId0,
            crmTabName0,
            nextRow0,
            crmHeaderMap0,
            context0.config,
            newCandidates0
        );

        return "Candidate discovery complete. New cold CRM rows added: "
            + newCandidates0.size()
            + ". Skipped not-ready: "
            + skippedNotReady0
            + ". Skipped duplicates: "
            + skippedDuplicates0
            + ".";
    }

    private static void writeNewCandidatesToCrmByColumn(
        String spreadsheetId0,
        String crmTabName0,
        int startRow0,
        java.util.HashMap<String, Integer> headerMap0,
        CRMSchemaConfig config0,
        ArrayList<CandidateInvestor> candidates0) throws Exception
    {
        if (candidates0 == null || candidates0.size() == 0)
        {
            return;
        }

        int endRow0 = startRow0 + candidates0.size() - 1;
        String timestamp0 = java.time.Instant.now().toString();

        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabFundNameCol"), candidates0, "fundName", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabContact1FirstNameCol"), candidates0, "contact1FirstName", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabContact1LastNameCol"), candidates0, "contact1LastName", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabContact1PositionCol"), candidates0, "contact1Position", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabContactLinkedInCol"), candidates0, "contact1LinkedInUrl", timestamp0);

        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabContact2FirstNameCol"), candidates0, "contact2FirstName", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabContact2LastNameCol"), candidates0, "contact2LastName", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabContact2PositionCol"), candidates0, "contact2Position", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabLinkedIn2Col"), candidates0, "contact2LinkedInUrl", timestamp0);

        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabStatusCol"), candidates0, "conversationStatus", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabWebsiteCol"), candidates0, "website", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabCompanyLinkedInCol"), candidates0, "fundLinkedInUrl", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabCountryCol"), candidates0, "country", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabCityCol"), candidates0, "region", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabNotesCol"), candidates0, "discoveryReason", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabInvestorProfileSimilarityCol"), candidates0, "finalScore", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabCommentsCol"), candidates0, "scoreExplanation", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabLastEnrichedAtCol"), candidates0, "lastEnrichedAt", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabEnrichmentStatusCol"), candidates0, "enrichmentStatus", timestamp0);

        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabTypeOfInvestorCol"), candidates0, "allocatorType", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabSectorTagsCol"), candidates0, "sectorTags", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabMicrosectorTagsCol"), candidates0, "microsectorTags", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabGeographyCol"), candidates0, "geography", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabPriorBackedFundsCol"), candidates0, "priorBackedFunds", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabInvestmentThesisCol"), candidates0, "investmentThesis", timestamp0);
        writeCandidateAppendColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, config0.getCol("mainTabIntelligenceJsonCol"), candidates0, "intelligenceJson", timestamp0);
    }

    private static void writeCandidateAppendColumn(
        String spreadsheetId0,
        String crmTabName0,
        int startRow0,
        int endRow0,
        java.util.HashMap<String, Integer> headerMap0,
        String header0,
        ArrayList<CandidateInvestor> candidates0,
        String fieldName0,
        String timestamp0) throws Exception
    {
        int col0 = getColumn(headerMap0, header0);
        if (col0 <= 0)
        {
            return;
        }

        String[][] columnData0 = SheetsApp.readRangeMatrix(
            spreadsheetId0,
            crmTabName0,
            startRow0,
            col0,
            endRow0,
            col0
        );

        int rowCount0 = endRow0 - startRow0 + 1;
        columnData0 = normalizeSingleColumnMatrix(columnData0, rowCount0);

        for (int i = 0; i < candidates0.size(); i++)
        {
            CandidateInvestor candidate0 = candidates0.get(i);
            candidate0.syncLegacyAliases();
            columnData0[i][0] = limitForGoogleSheetsCell(getCandidateColumnValue(candidate0, fieldName0, timestamp0));
        }

        SheetsApp.updateRangeMatrix(
            spreadsheetId0,
            crmTabName0,
            startRow0,
            col0,
            columnData0
        );
    }

    private static String[][] normalizeSingleColumnMatrix(String[][] matrix0, int rowCount0)
    {
        String[][] fixed0 = new String[rowCount0][1];

        for (int i = 0; i < rowCount0; i++)
        {
            fixed0[i][0] = "";
        }

        if (matrix0 == null)
        {
            return fixed0;
        }

        for (int i = 0; i < rowCount0 && i < matrix0.length; i++)
        {
            if (matrix0[i] != null && matrix0[i].length > 0 && matrix0[i][0] != null)
            {
                fixed0[i][0] = matrix0[i][0];
            }
        }

        return fixed0;
    }

    private static String getCandidateColumnValue(
        CandidateInvestor candidate0,
        String fieldName0,
        String timestamp0)
    {
        if (candidate0 == null)
        {
            return "";
        }

        if (fieldName0.equals("fundName")) return candidate0.fundName;
        if (fieldName0.equals("contact1FirstName")) return candidate0.contact1FirstName;
        if (fieldName0.equals("contact1LastName")) return candidate0.contact1LastName;
        if (fieldName0.equals("contact1Position")) return candidate0.contact1Position;
        if (fieldName0.equals("contact1LinkedInUrl")) return candidate0.contact1LinkedInUrl;
        if (fieldName0.equals("contact2FirstName")) return candidate0.contact2FirstName;
        if (fieldName0.equals("contact2LastName")) return candidate0.contact2LastName;
        if (fieldName0.equals("contact2Position")) return candidate0.contact2Position;
        if (fieldName0.equals("contact2LinkedInUrl")) return candidate0.contact2LinkedInUrl;
        if (fieldName0.equals("conversationStatus")) return "Cold";
        if (fieldName0.equals("website")) return candidate0.website;
        if (fieldName0.equals("fundLinkedInUrl")) return candidate0.fundLinkedInUrl;
        if (fieldName0.equals("country")) return candidate0.country;
        if (fieldName0.equals("region")) return candidate0.region;
        if (fieldName0.equals("discoveryReason")) return candidate0.discoveryReason;
        if (fieldName0.equals("finalScore")) return formatScore(candidate0.finalScore);
        if (fieldName0.equals("scoreExplanation")) return candidate0.scoreExplanation;
        if (fieldName0.equals("lastEnrichedAt")) return timestamp0;
        if (fieldName0.equals("enrichmentStatus")) return candidate0.hasInvestorProfile() ? "COMPLETED" : "DISCOVERED";

        if (candidate0.ip == null)
        {
            if (fieldName0.equals("intelligenceJson")) return buildMinimalJson(candidate0).toString();
            return "";
        }

        if (fieldName0.equals("allocatorType")) return candidate0.ip.allocatorType;
        if (fieldName0.equals("sectorTags")) return InvestorProfile.joinWithPipe(candidate0.ip.sectors);
        if (fieldName0.equals("microsectorTags")) return InvestorProfile.joinWithPipe(candidate0.ip.microsectors);
        if (fieldName0.equals("geography")) return InvestorProfile.joinWithPipe(candidate0.ip.geographies);
        if (fieldName0.equals("priorBackedFunds")) return InvestorProfile.joinWithPipe(candidate0.ip.priorBackedFunds);
        if (fieldName0.equals("investmentThesis")) return candidate0.ip.investmentThesis;
        if (fieldName0.equals("intelligenceJson")) return buildMergedIntelligenceJson(candidate0).toString();

        return "";
    }

    private static void fillCrmRow(
        String[] row0,
        java.util.HashMap<String, Integer> headerMap0,
        CRMSchemaConfig config0,
        CandidateInvestor candidate0)
    {
        candidate0.syncLegacyAliases();

        put(row0, headerMap0, config0.getCol("mainTabFundNameCol"), candidate0.fundName);
        put(row0, headerMap0, config0.getCol("mainTabContact1FirstNameCol"), candidate0.contact1FirstName);
        put(row0, headerMap0, config0.getCol("mainTabContact1LastNameCol"), candidate0.contact1LastName);
        put(row0, headerMap0, config0.getCol("mainTabContact1PositionCol"), candidate0.contact1Position);
        put(row0, headerMap0, config0.getCol("mainTabContactLinkedInCol"), candidate0.contact1LinkedInUrl);

        put(row0, headerMap0, config0.getCol("mainTabContact2FirstNameCol"), candidate0.contact2FirstName);
        put(row0, headerMap0, config0.getCol("mainTabContact2LastNameCol"), candidate0.contact2LastName);
        put(row0, headerMap0, config0.getCol("mainTabContact2PositionCol"), candidate0.contact2Position);
        put(row0, headerMap0, config0.getCol("mainTabLinkedIn2Col"), candidate0.contact2LinkedInUrl);

        put(row0, headerMap0, config0.getCol("mainTabStatusCol"), "Cold");
        put(row0, headerMap0, config0.getCol("mainTabWebsiteCol"), candidate0.website);
        put(row0, headerMap0, config0.getCol("mainTabCompanyLinkedInCol"), candidate0.fundLinkedInUrl);
        put(row0, headerMap0, config0.getCol("mainTabCountryCol"), candidate0.country);
        put(row0, headerMap0, config0.getCol("mainTabCityCol"), candidate0.region);
        put(row0, headerMap0, config0.getCol("mainTabNotesCol"), candidate0.discoveryReason);
        put(row0, headerMap0, config0.getCol("mainTabInvestorProfileSimilarityCol"), formatScore(candidate0.finalScore));
        put(row0, headerMap0, config0.getCol("mainTabCommentsCol"), candidate0.scoreExplanation);
        put(row0, headerMap0, config0.getCol("mainTabLastEnrichedAtCol"), java.time.Instant.now().toString());
        put(row0, headerMap0, config0.getCol("mainTabEnrichmentStatusCol"), candidate0.hasInvestorProfile() ? "COMPLETED" : "DISCOVERED");

        if (candidate0.ip != null)
        {
            JSONObject mergedJson0 = buildMergedIntelligenceJson(candidate0);

            put(row0, headerMap0, config0.getCol("mainTabTypeOfInvestorCol"), candidate0.ip.allocatorType);
            put(row0, headerMap0, config0.getCol("mainTabSectorTagsCol"), InvestorProfile.joinWithPipe(candidate0.ip.sectors));
            put(row0, headerMap0, config0.getCol("mainTabMicrosectorTagsCol"), InvestorProfile.joinWithPipe(candidate0.ip.microsectors));
            put(row0, headerMap0, config0.getCol("mainTabGeographyCol"), InvestorProfile.joinWithPipe(candidate0.ip.geographies));
            put(row0, headerMap0, config0.getCol("mainTabPriorBackedFundsCol"), InvestorProfile.joinWithPipe(candidate0.ip.priorBackedFunds));
            put(row0, headerMap0, config0.getCol("mainTabInvestmentThesisCol"), candidate0.ip.investmentThesis);
            put(row0, headerMap0, config0.getCol("mainTabIntelligenceJsonCol"), mergedJson0.toString());
        }
        else
        {
            put(row0, headerMap0, config0.getCol("mainTabIntelligenceJsonCol"), buildMinimalJson(candidate0).toString());
        }
    }

    private static JSONObject buildMergedIntelligenceJson(CandidateInvestor candidate0)
    {
        JSONObject root0;
        try
        {
            root0 = new JSONObject(candidate0.ip.intelligenceJson);
        }
        catch (Exception exception0)
        {
            root0 = new JSONObject();
        }

        root0.put("candidate_crm_fields", buildMinimalJson(candidate0));
        return root0;
    }

    private static JSONObject buildMinimalJson(CandidateInvestor candidate0)
    {
        JSONObject object0 = new JSONObject();
        object0.put("discovery_query", candidate0.discoveryQuery);
        object0.put("discovery_reason", candidate0.discoveryReason);
        object0.put("linkedin_entity_type", candidate0.linkedinEntityType);
        object0.put("discovery_path", candidate0.discoveryPath);
        object0.put("contact1_first_name", candidate0.contact1FirstName);
        object0.put("contact1_last_name", candidate0.contact1LastName);
        object0.put("contact1_position", candidate0.contact1Position);
        object0.put("contact1_linkedin_url", candidate0.contact1LinkedInUrl);
        object0.put("fund_name", candidate0.fundName);
        object0.put("fund_website", candidate0.website);
        object0.put("fund_linkedin_url", candidate0.fundLinkedInUrl);
        object0.put("country", candidate0.country);
        object0.put("region", candidate0.region);
        object0.put("contact2_first_name", candidate0.contact2FirstName);
        object0.put("contact2_last_name", candidate0.contact2LastName);
        object0.put("contact2_position", candidate0.contact2Position);
        object0.put("contact2_linkedin_url", candidate0.contact2LinkedInUrl);
        object0.put("evidence_json", limitForJsonField(candidate0.evidenceJson, 8000));
        object0.put("status", candidate0.hasInvestorProfile() ? "profile_extracted" : "discovered_not_profile_extracted");
        return object0;
    }

    private static boolean candidateExistsInCrm(
        CandidateInvestor candidate0,
        String[][] crmData0,
        java.util.HashMap<String, Integer> headerMap0,
        CRMSchemaConfig config0)
    {
        int fundCol0 = getColumn(headerMap0, config0.getCol("mainTabFundNameCol"));
        int contactLinkedinCol0 = getColumn(headerMap0, config0.getCol("mainTabContactLinkedInCol"));
        int contact2LinkedinCol0 = getColumn(headerMap0, config0.getCol("mainTabLinkedIn2Col"));
        int companyLinkedinCol0 = getColumn(headerMap0, config0.getCol("mainTabCompanyLinkedInCol"));
        int websiteCol0 = getColumn(headerMap0, config0.getCol("mainTabWebsiteCol"));
        int firstNameCol0 = getColumn(headerMap0, config0.getCol("mainTabContact1FirstNameCol"));
        int lastNameCol0 = getColumn(headerMap0, config0.getCol("mainTabContact1LastNameCol"));

        for (int row = config0.mainTabDataStartRow; row <= crmData0.length; row++)
        {
            String existingContactLinkedIn0 = getCell(crmData0, row, contactLinkedinCol0);
            String existingContact2LinkedIn0 = getCell(crmData0, row, contact2LinkedinCol0);
            String existingCompanyLinkedIn0 = getCell(crmData0, row, companyLinkedinCol0);
            String existingWebsite0 = getCell(crmData0, row, websiteCol0);
            String existingFund0 = getCell(crmData0, row, fundCol0);
            String existingFirst0 = getCell(crmData0, row, firstNameCol0);
            String existingLast0 = getCell(crmData0, row, lastNameCol0);

            if (!RawCandidateData.isBlank(candidate0.contact1LinkedInUrl) &&
                (normalizeUrl(candidate0.contact1LinkedInUrl).equals(normalizeUrl(existingContactLinkedIn0)) ||
                 normalizeUrl(candidate0.contact1LinkedInUrl).equals(normalizeUrl(existingContact2LinkedIn0))))
            {
                return true;
            }

            if (!RawCandidateData.isBlank(candidate0.fundLinkedInUrl) &&
                normalizeUrl(candidate0.fundLinkedInUrl).equals(normalizeUrl(existingCompanyLinkedIn0)))
            {
                return true;
            }

            if (!RawCandidateData.isBlank(candidate0.website) &&
                normalizeUrl(candidate0.website).equals(normalizeUrl(existingWebsite0)))
            {
                return true;
            }

            if (!RawCandidateData.isBlank(candidate0.fundName) &&
                !RawCandidateData.isBlank(candidate0.contact1FirstName) &&
                !RawCandidateData.isBlank(candidate0.contact1LastName) &&
                normalizeText(candidate0.fundName).equals(normalizeText(existingFund0)) &&
                normalizeText(candidate0.contact1FirstName).equals(normalizeText(existingFirst0)) &&
                normalizeText(candidate0.contact1LastName).equals(normalizeText(existingLast0)))
            {
                return true;
            }
        }

        return false;
    }

    private static PreEnrichmentCrmIndex buildPreEnrichmentCrmIndex(SessionContext context0) throws Exception
    {
        PreEnrichmentCrmIndex index0 = new PreEnrichmentCrmIndex();

        if (context0 == null || context0.config == null)
        {
            return index0;
        }

        String spreadsheetId0 = context0.config.spreadsheetId;
        String crmTabName0 = context0.config.mainTabName;

        java.util.HashMap<String, Integer> crmHeaderMap0 = SheetsApp.buildHeaderMap(
            spreadsheetId0,
            crmTabName0,
            context0.config.mainTabHeaderRow,
            MAX_COLUMNS0
        );

        int fundCol0 = getColumn(crmHeaderMap0, context0.config.getCol("mainTabFundNameCol"));
        int contactLinkedInCol0 = getColumn(crmHeaderMap0, context0.config.getCol("mainTabContactLinkedInCol"));
        int contact2LinkedInCol0 = getColumn(crmHeaderMap0, context0.config.getCol("mainTabLinkedIn2Col"));
        int companyLinkedInCol0 = getColumn(crmHeaderMap0, context0.config.getCol("mainTabCompanyLinkedInCol"));
        int websiteCol0 = getColumn(crmHeaderMap0, context0.config.getCol("mainTabWebsiteCol"));
        int firstNameCol0 = getColumn(crmHeaderMap0, context0.config.getCol("mainTabContact1FirstNameCol"));
        int lastNameCol0 = getColumn(crmHeaderMap0, context0.config.getCol("mainTabContact1LastNameCol"));
        int contact2FirstNameCol0 = getColumn(crmHeaderMap0, context0.config.getCol("mainTabContact2FirstNameCol"));
        int contact2LastNameCol0 = getColumn(crmHeaderMap0, context0.config.getCol("mainTabContact2LastNameCol"));

        int startRow0 = context0.config.mainTabDataStartRow;
        int endRow0 = MAX_CRM_ROWS0;

        String[][] crmIndexData0 = readSelectedCrmIndexColumns(
            spreadsheetId0,
            crmTabName0,
            startRow0,
            endRow0,
            fundCol0,
            contactLinkedInCol0,
            contact2LinkedInCol0,
            companyLinkedInCol0,
            websiteCol0,
            firstNameCol0,
            lastNameCol0,
            contact2FirstNameCol0,
            contact2LastNameCol0
        );

        for (int i = 0; i < crmIndexData0.length; i++)
        {
            String fundName0 = crmIndexData0[i][0];
            String contactLinkedIn0 = crmIndexData0[i][1];
            String contact2LinkedIn0 = crmIndexData0[i][2];
            String companyLinkedIn0 = crmIndexData0[i][3];
            String website0 = crmIndexData0[i][4];
            String firstName0 = crmIndexData0[i][5];
            String lastName0 = crmIndexData0[i][6];
            String contact2FirstName0 = crmIndexData0[i][7];
            String contact2LastName0 = crmIndexData0[i][8];

            addIfNotBlank(index0.fundNames, normalizeText(fundName0));
            addIfNotBlank(index0.contactLinkedInUrls, normalizeUrl(contactLinkedIn0));
            addIfNotBlank(index0.contactLinkedInUrls, normalizeUrl(contact2LinkedIn0));
            addIfNotBlank(index0.companyLinkedInUrls, normalizeUrl(companyLinkedIn0));
            addIfNotBlank(index0.websites, normalizeUrl(website0));
            addIfNotBlank(index0.contactNameKeys, buildNameKey(firstName0, lastName0));
            addIfNotBlank(index0.contactNameKeys, buildNameKey(contact2FirstName0, contact2LastName0));
            addIfNotBlank(index0.fundContactNameKeys, buildFundContactKey(fundName0, firstName0, lastName0));
            addIfNotBlank(index0.fundContactNameKeys, buildFundContactKey(fundName0, contact2FirstName0, contact2LastName0));

            String companySlug0 = extractCompanySlugFromLinkedInUrl(companyLinkedIn0);
            addIfNotBlank(index0.companyLinkedInSlugs, companySlug0);
        }

        System.out.println(
            "Built pre-enrichment CRM duplicate index | contacts="
            + index0.contactNameKeys.size()
            + " | contact LinkedIns="
            + index0.contactLinkedInUrls.size()
            + " | funds="
            + index0.fundNames.size()
            + " | company LinkedIns="
            + index0.companyLinkedInUrls.size()
        );

        return index0;
    }

    private static String[][] readSelectedCrmIndexColumns(
        String spreadsheetId0,
        String crmTabName0,
        int startRow0,
        int endRow0,
        int fundCol0,
        int contactLinkedInCol0,
        int contact2LinkedInCol0,
        int companyLinkedInCol0,
        int websiteCol0,
        int firstNameCol0,
        int lastNameCol0,
        int contact2FirstNameCol0,
        int contact2LastNameCol0) throws Exception
    {
        int[] cols0 = new int[]
        {
            fundCol0,
            contactLinkedInCol0,
            contact2LinkedInCol0,
            companyLinkedInCol0,
            websiteCol0,
            firstNameCol0,
            lastNameCol0,
            contact2FirstNameCol0,
            contact2LastNameCol0
        };

        String[][] result0 = new String[endRow0 - startRow0 + 1][cols0.length];

        for (int i = 0; i < result0.length; i++)
        {
            for (int j = 0; j < result0[i].length; j++)
            {
                result0[i][j] = "";
            }
        }

        for (int colIndex0 = 0; colIndex0 < cols0.length; colIndex0++)
        {
            int sheetCol0 = cols0[colIndex0];

            if (sheetCol0 <= 0)
            {
                continue;
            }

            String[][] columnData0 = SheetsApp.readRangeMatrix(
                spreadsheetId0,
                crmTabName0,
                startRow0,
                sheetCol0,
                endRow0,
                sheetCol0
            );

            if (columnData0 == null)
            {
                continue;
            }

            for (int rowIndex0 = 0; rowIndex0 < result0.length && rowIndex0 < columnData0.length; rowIndex0++)
            {
                if (columnData0[rowIndex0] != null &&
                    columnData0[rowIndex0].length > 0 &&
                    columnData0[rowIndex0][0] != null)
                {
                    result0[rowIndex0][colIndex0] = columnData0[rowIndex0][0].trim();
                }
            }
        }

        return result0;
    }

    private static boolean candidateExistsInCrmIndex(
        CandidateInvestor candidate0,
        PreEnrichmentCrmIndex index0)
    {
        if (candidate0 == null || index0 == null)
        {
            return false;
        }

        if (!RawCandidateData.isBlank(candidate0.contact1LinkedInUrl) &&
            index0.contactLinkedInUrls.contains(normalizeUrl(candidate0.contact1LinkedInUrl)))
        {
            return true;
        }

        if (!RawCandidateData.isBlank(candidate0.contact2LinkedInUrl) &&
            index0.contactLinkedInUrls.contains(normalizeUrl(candidate0.contact2LinkedInUrl)))
        {
            return true;
        }

        if (!RawCandidateData.isBlank(candidate0.fundLinkedInUrl) &&
            index0.companyLinkedInUrls.contains(normalizeUrl(candidate0.fundLinkedInUrl)))
        {
            return true;
        }

        if (!RawCandidateData.isBlank(candidate0.website) &&
            index0.websites.contains(normalizeUrl(candidate0.website)))
        {
            return true;
        }

        String fundContactKey0 = buildFundContactKey(
            candidate0.fundName,
            candidate0.contact1FirstName,
            candidate0.contact1LastName
        );

        return !RawCandidateData.isBlank(fundContactKey0) &&
            index0.fundContactNameKeys.contains(fundContactKey0);
    }

    private static void addCandidateToCrmIndex(
        PreEnrichmentCrmIndex index0,
        CandidateInvestor candidate0)
    {
        if (index0 == null || candidate0 == null)
        {
            return;
        }

        addIfNotBlank(index0.fundNames, normalizeText(candidate0.fundName));
        addIfNotBlank(index0.contactLinkedInUrls, normalizeUrl(candidate0.contact1LinkedInUrl));
        addIfNotBlank(index0.contactLinkedInUrls, normalizeUrl(candidate0.contact2LinkedInUrl));
        addIfNotBlank(index0.companyLinkedInUrls, normalizeUrl(candidate0.fundLinkedInUrl));
        addIfNotBlank(index0.websites, normalizeUrl(candidate0.website));
        addIfNotBlank(index0.contactNameKeys, buildNameKey(candidate0.contact1FirstName, candidate0.contact1LastName));
        addIfNotBlank(index0.contactNameKeys, buildNameKey(candidate0.contact2FirstName, candidate0.contact2LastName));
        addIfNotBlank(index0.fundContactNameKeys, buildFundContactKey(candidate0.fundName, candidate0.contact1FirstName, candidate0.contact1LastName));
        addIfNotBlank(index0.fundContactNameKeys, buildFundContactKey(candidate0.fundName, candidate0.contact2FirstName, candidate0.contact2LastName));
        addIfNotBlank(index0.companyLinkedInSlugs, extractCompanySlugFromLinkedInUrl(candidate0.fundLinkedInUrl));
    }

    private static void addIfNotBlank(HashSet<String> set0, String value0)
    {
        if (set0 == null || RawCandidateData.isBlank(value0))
        {
            return;
        }

        set0.add(value0);
    }

    private static String buildNameKey(String firstName0, String lastName0)
    {
        String first0 = normalizeText(firstName0);
        String last0 = normalizeText(lastName0);

        if (RawCandidateData.isBlank(first0) || RawCandidateData.isBlank(last0))
        {
            return "";
        }

        return first0 + "|" + last0;
    }

    private static String buildFundContactKey(
        String fundName0,
        String firstName0,
        String lastName0)
    {
        String fund0 = normalizeText(fundName0);
        String name0 = buildNameKey(firstName0, lastName0);

        if (RawCandidateData.isBlank(fund0) || RawCandidateData.isBlank(name0))
        {
            return "";
        }

        return fund0 + "|" + name0;
    }

    private static String extractNameKeyFromLinkedInTitle(String title0)
    {
        if (RawCandidateData.isBlank(title0))
        {
            return "";
        }

        String cleaned0 = cleanLinkedInTitleStatic(title0);
        String[] titlePieces0 = cleaned0.split(" - ");
        String namePart0 = titlePieces0.length > 0 ? titlePieces0[0].trim() : cleaned0;
        String[] pieces0 = namePart0.split("\\s+");

        if (pieces0.length < 2)
        {
            return "";
        }

        return buildNameKey(pieces0[0], pieces0[pieces0.length - 1]);
    }

    private static String extractCompanySlugFromLinkedInUrl(String url0)
    {
        if (RawCandidateData.isBlank(url0))
        {
            return "";
        }

        String normalized0 = normalizeUrl(url0);
        String marker0 = "linkedin.com/company/";
        int markerIndex0 = normalized0.indexOf(marker0);

        if (markerIndex0 == -1)
        {
            marker0 = "linkedin.com/organization-guest/company/";
            markerIndex0 = normalized0.indexOf(marker0);
        }

        if (markerIndex0 == -1)
        {
            return "";
        }

        String slug0 = normalized0.substring(markerIndex0 + marker0.length());
        int slashIndex0 = slug0.indexOf("/");
        if (slashIndex0 != -1)
        {
            slug0 = slug0.substring(0, slashIndex0);
        }

        return normalizeText(slug0);
    }

    private static String cleanLinkedInTitleStatic(String title0)
    {
        if (title0 == null)
        {
            return "";
        }

        return title0
            .replace("| LinkedIn", "")
            .replace("LinkedIn", "")
            .trim();
    }

    private static class PreEnrichmentCrmIndex
    {
        public HashSet<String> contactLinkedInUrls = new HashSet<String>();
        public HashSet<String> companyLinkedInUrls = new HashSet<String>();
        public HashSet<String> companyLinkedInSlugs = new HashSet<String>();
        public HashSet<String> websites = new HashSet<String>();
        public HashSet<String> fundNames = new HashSet<String>();
        public HashSet<String> contactNameKeys = new HashSet<String>();
        public HashSet<String> fundContactNameKeys = new HashSet<String>();
    }

    public static ArrayList<InvestorProfile> buildSeedProfilesFromClientInput(
        String sectors0,
        String microsectors0,
        String geographies0,
        String thesis0)
    {
        ArrayList<InvestorProfile> profiles0 = new ArrayList<InvestorProfile>();
        InvestorProfile profile0 = new InvestorProfile();
        profile0.sectors = splitPipeOrComma(sectors0);
        profile0.microsectors = splitPipeOrComma(microsectors0);
        profile0.geographies = splitPipeOrComma(geographies0);
        profile0.investmentThesis = thesis0 == null ? "" : thesis0;
        profiles0.add(profile0);
        return profiles0;
    }

    private static String[] splitPipeOrComma(String value0)
    {
        if (RawCandidateData.isBlank(value0))
        {
            return new String[0];
        }

        String[] pieces0 = value0.split("[|,]");
        ArrayList<String> cleaned0 = new ArrayList<String>();

        for (String piece0 : pieces0)
        {
            if (!RawCandidateData.isBlank(piece0))
            {
                cleaned0.add(piece0.trim());
            }
        }

        return cleaned0.toArray(new String[0]);
    }

    private static String formatScore(double score0)
    {
        if (Double.isNaN(score0) || Double.isInfinite(score0))
        {
            return "0.00";
        }

        if (score0 < 0.0)
        {
            score0 = 0.0;
        }

        if (score0 > 1.0)
        {
            score0 = 1.0;
        }

        return String.format("%.2f", score0);
    }

    private static String limitForGoogleSheetsCell(String value0)
    {
        if (value0 == null)
        {
            return "";
        }

        int maxChars0 = 49000;

        if (value0.length() <= maxChars0)
        {
            return value0;
        }

        return value0.substring(0, maxChars0)
            + "\n\n[TRUNCATED: exceeded Google Sheets 50000 character cell limit]";
    }

    private static String limitForJsonField(String value0, int maxChars0)
    {
        if (value0 == null)
        {
            return "";
        }

        if (value0.length() <= maxChars0)
        {
            return value0;
        }

        return value0.substring(0, maxChars0) + "\n[TRUNCATED]";
    }

    private static void put(String[] row0, java.util.HashMap<String, Integer> headerMap0, String header0, String value0)
    {
        int col0 = getColumn(headerMap0, header0);
        if (col0 == -1)
        {
            return;
        }

        int index0 = col0 - 1;
        if (index0 >= 0 && index0 < row0.length)
        {
            row0[index0] = limitForGoogleSheetsCell(value0);
        }
    }

    private static int getColumn(java.util.HashMap<String, Integer> headerMap0, String header0)
    {
        if (headerMap0 == null || RawCandidateData.isBlank(header0))
        {
            return -1;
        }

        Integer col0 = headerMap0.get(header0.trim());
        return col0 == null ? -1 : col0;
    }

    private static String getCell(String[][] data0, int rowNumber0, int oneBasedColumn0)
    {
        int rowIndex0 = rowNumber0 - 1;
        int colIndex0 = oneBasedColumn0 - 1;

        if (rowIndex0 < 0 || rowIndex0 >= data0.length || colIndex0 < 0 || colIndex0 >= data0[rowIndex0].length)
        {
            return "";
        }

        return data0[rowIndex0][colIndex0] == null ? "" : data0[rowIndex0][colIndex0].trim();
    }

    private static int getMaxHeaderColumn(java.util.HashMap<String, Integer> headerMap0)
    {
        int max0 = 0;
        for (Integer value0 : headerMap0.values())
        {
            if (value0 != null && value0 > max0)
            {
                max0 = value0;
            }
        }
        return max0;
    }

    private static String normalizeText(String value0)
    {
        return value0 == null ? "" : value0.toLowerCase().replaceAll("[^a-z0-9]", "").trim();
    }

    private static String normalizeUrl(String value0)
    {
        if (value0 == null)
        {
            return "";
        }

        return value0.toLowerCase()
            .replace("https://", "")
            .replace("http://", "")
            .replace("www.", "")
            .replaceAll("/$", "")
            .trim();
    }

    private static String firstNonBlank(String a0, String b0, String c0)
    {
        if (!RawCandidateData.isBlank(a0)) return a0;
        if (!RawCandidateData.isBlank(b0)) return b0;
        if (!RawCandidateData.isBlank(c0)) return c0;
        return "";
    }
}
