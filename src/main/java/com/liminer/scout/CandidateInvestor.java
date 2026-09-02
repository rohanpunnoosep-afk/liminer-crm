package com.liminer.scout;

import com.liminer.core.InvestorProfile;
import com.liminer.enrich.DiscoveredLinkedInTarget;

import java.util.HashMap;

/*
 * CandidateInvestor represents one possible CRM-ready investor/contact row discovered by Pipeline 3.
 *
 * Important distinction:
 * - linkedinEntityType / candidateType means the original LinkedIn target was PERSON or COMPANY.
 * - allocatorType lives inside InvestorProfile and means VC, PE, Family Office, Foundation, etc.
 *
 * The explicit contact/fund fields prevent company names from being written into contact
 * name columns and prevent company LinkedIn URLs from being written into contact LinkedIn columns.
 */
public class CandidateInvestor
{
    public String candidateType;       // Legacy alias: PERSON or COMPANY
    public String linkedinEntityType;  // Preferred name: PERSON or COMPANY
    public String discoveryPath;       // PERSON_FIRST or COMPANY_FIRST

    public String fundName;
    public String website;
    public String fundLinkedInUrl;

    public String linkedInUrl;         // Legacy alias for contact1LinkedInUrl when present, otherwise fundLinkedInUrl
    public String linkedInProfileUrl;  // Legacy alias for contact1LinkedInUrl
    public String linkedInCompanyUrl;  // Legacy alias for fundLinkedInUrl

    public String name;
    public String firstName;           // Legacy alias for contact1FirstName
    public String lastName;            // Legacy alias for contact1LastName
    public String position;            // Legacy alias for contact1Position

    public String contact1FirstName;
    public String contact1LastName;
    public String contact1Position;
    public String contact1LinkedInUrl;

    public String contact2FirstName;
    public String contact2LastName;
    public String contact2Position;
    public String contact2LinkedInUrl;

    public String country;
    public String region;

    public String discoveryQuery;
    public String discoveryReason;
    public String conversationStatus;
    public String evidenceJson;

    public InvestorProfile ip;

    public double finalScore;
    public HashMap<String, Double> subscores;
    public String scoreExplanation;

    public CandidateInvestor(String fundName0, String website0, String linkedInUrl0)
    {
        candidateType = "";
        linkedinEntityType = "";
        discoveryPath = "";

        fundName = safeString(fundName0);
        website = safeString(website0);
        fundLinkedInUrl = "";

        linkedInUrl = safeString(linkedInUrl0);
        linkedInProfileUrl = "";
        linkedInCompanyUrl = "";

        name = "";
        firstName = "";
        lastName = "";
        position = "";

        contact1FirstName = "";
        contact1LastName = "";
        contact1Position = "";
        contact1LinkedInUrl = "";

        contact2FirstName = "";
        contact2LastName = "";
        contact2Position = "";
        contact2LinkedInUrl = "";

        country = "";
        region = "";

        discoveryQuery = "";
        discoveryReason = "";
        conversationStatus = "Cold";
        evidenceJson = "";

        ip = null;
        finalScore = 0.0;
        subscores = new HashMap<String, Double>();
        scoreExplanation = "";
    }

    public CandidateInvestor(String fundName0, String website0)
    {
        this(fundName0, website0, "");
    }

    public static CandidateInvestor fromRawCandidateData(RawCandidateData raw0)
    {
        if (raw0 == null)
        {
            return new CandidateInvestor("", "", "");
        }

        CandidateInvestor candidate0 = new CandidateInvestor(
            raw0.fundName,
            raw0.websiteUrl,
            ""
        );

        candidate0.linkedinEntityType = safeString(raw0.candidateType);
        candidate0.candidateType = candidate0.linkedinEntityType;
        candidate0.discoveryPath = DiscoveredLinkedInTarget.TYPE_PERSON.equals(candidate0.linkedinEntityType)
            ? "PERSON_FIRST"
            : "COMPANY_FIRST";

        candidate0.name = safeString(raw0.name);
        candidate0.discoveryQuery = safeString(raw0.discoveryQuery);
        candidate0.discoveryReason = buildDiscoveryReason(raw0);
        candidate0.conversationStatus = "Cold";
        candidate0.country = safeString(raw0.country);
        candidate0.region = safeString(raw0.region);
        candidate0.contact2FirstName = safeString(raw0.contact2FirstName);
        candidate0.contact2LastName = safeString(raw0.contact2LastName);
        candidate0.contact2Position = safeString(raw0.contact2Position);
        candidate0.contact2LinkedInUrl = safeString(raw0.contact2LinkedInUrl);
        candidate0.evidenceJson = raw0.buildEvidenceJson().toString();

        if (DiscoveredLinkedInTarget.TYPE_PERSON.equals(candidate0.linkedinEntityType))
        {
            candidate0.contact1FirstName = safeString(raw0.firstName);
            candidate0.contact1LastName = safeString(raw0.lastName);
            candidate0.contact1Position = safeString(raw0.position);
            candidate0.contact1LinkedInUrl = safeString(raw0.linkedinProfileUrl);
            candidate0.fundLinkedInUrl = safeString(raw0.linkedinCompanyUrl);
        }
        else if (DiscoveredLinkedInTarget.TYPE_COMPANY.equals(candidate0.linkedinEntityType))
        {
            candidate0.fundLinkedInUrl = safeString(raw0.linkedinCompanyUrl);
            candidate0.contact1FirstName = safeString(raw0.firstName);
            candidate0.contact1LastName = safeString(raw0.lastName);
            candidate0.contact1Position = safeString(raw0.position);
            candidate0.contact1LinkedInUrl = safeString(raw0.linkedinProfileUrl);
        }

        if (isBlank(candidate0.fundName) && DiscoveredLinkedInTarget.TYPE_COMPANY.equals(candidate0.linkedinEntityType))
        {
            candidate0.fundName = candidate0.name;
        }

        if (isBlank(candidate0.name))
        {
            candidate0.name = (candidate0.contact1FirstName + " " + candidate0.contact1LastName).trim();
        }

        splitPersonNameIfNeeded(candidate0);
        candidate0.syncLegacyAliases();

        return candidate0;
    }

    public void syncLegacyAliases()
    {
        candidateType = safeString(linkedinEntityType);
        linkedInProfileUrl = safeString(contact1LinkedInUrl);
        linkedInCompanyUrl = safeString(fundLinkedInUrl);
        linkedInUrl = !isBlank(contact1LinkedInUrl) ? contact1LinkedInUrl : fundLinkedInUrl;
        firstName = safeString(contact1FirstName);
        lastName = safeString(contact1LastName);
        position = safeString(contact1Position);
    }

    public boolean isPersonFirst()
    {
        return DiscoveredLinkedInTarget.TYPE_PERSON.equals(linkedinEntityType);
    }

    public boolean isCompanyFirst()
    {
        return DiscoveredLinkedInTarget.TYPE_COMPANY.equals(linkedinEntityType);
    }

    public boolean hasWebsite()
    {
        return !isBlank(website);
    }

    public boolean hasInvestorProfile()
    {
        return ip != null;
    }

    public boolean hasContactPerson1()
    {
        return !isBlank(contact1FirstName)
            && !isBlank(contact1LastName)
            //&& !isBlank(contact1Position)
            && !isBlank(contact1LinkedInUrl);
    }

    public boolean hasFundInfo()
    {
        return !isBlank(fundName)
            && !isBlank(website)
            && !isBlank(fundLinkedInUrl);
    }

    public boolean isCrmReady()
    {
        return hasContactPerson1() && hasFundInfo();
    }

    public String getMissingCrmReadyFields()
    {
        String result0 = "";
        result0 = addMissing(result0, "Contact 1 First Name", contact1FirstName);
        result0 = addMissing(result0, "Contact 1 Last Name", contact1LastName);
        result0 = addMissing(result0, "Contact 1 Position", contact1Position);
        result0 = addMissing(result0, "Contact 1 LinkedIn", contact1LinkedInUrl);
        result0 = addMissing(result0, "Fund Name", fundName);
        result0 = addMissing(result0, "Fund Website", website);
        result0 = addMissing(result0, "Fund LinkedIn", fundLinkedInUrl);
        return result0;
    }

    private static String addMissing(String existing0, String label0, String value0)
    {
        if (!isBlank(value0))
        {
            return existing0;
        }
        if (!isBlank(existing0))
        {
            existing0 += ", ";
        }
        return existing0 + label0;
    }

    public String getDeduplicationKey()
    {
        String source0 = contact1LinkedInUrl;

        if (isBlank(source0))
        {
            source0 = fundLinkedInUrl;
        }

        if (isBlank(source0))
        {
            source0 = website;
        }

        if (isBlank(source0))
        {
            source0 = fundName + contact1FirstName + contact1LastName;
        }

        return normalize(source0);
    }

    public void printSummary()
    {
        syncLegacyAliases();
        System.out.println("===== CANDIDATE INVESTOR =====");
        System.out.println("LinkedIn Entity Type: " + linkedinEntityType);
        System.out.println("Discovery Path: " + discoveryPath);
        System.out.println("Contact 1 First Name: " + contact1FirstName);
        System.out.println("Contact 1 Last Name: " + contact1LastName);
        System.out.println("Contact 1 Position: " + contact1Position);
        System.out.println("Contact 1 LinkedIn: " + contact1LinkedInUrl);
        System.out.println("Fund Name: " + fundName);
        System.out.println("Fund Website: " + website);
        System.out.println("Fund LinkedIn: " + fundLinkedInUrl);
        System.out.println("Country: " + country);
        System.out.println("Region: " + region);
        System.out.println("Contact 2 First Name: " + contact2FirstName);
        System.out.println("Contact 2 Last Name: " + contact2LastName);
        System.out.println("Contact 2 Position: " + contact2Position);
        System.out.println("Contact 2 LinkedIn: " + contact2LinkedInUrl);
        System.out.println("CRM Ready: " + isCrmReady());
        if (!isCrmReady())
        {
            System.out.println("Missing CRM Fields: " + getMissingCrmReadyFields());
        }
        System.out.println("Conversation Status: " + conversationStatus);
        System.out.println("Discovery Query: " + discoveryQuery);
        System.out.println("Discovery Reason: " + discoveryReason);
        System.out.println("Final Score: " + finalScore);
        System.out.println("Subscores: " + subscores);
        System.out.println("Score Explanation: " + scoreExplanation);

        if (ip == null)
        {
            System.out.println("Investor Profile: null");
        }
        else
        {
            ip.printSummary();
        }
    }

    private static String buildDiscoveryReason(RawCandidateData raw0)
    {
        String reason0 = "Found through SERP query";

        if (!isBlank(raw0.discoveryQuery))
        {
            reason0 += ": " + raw0.discoveryQuery;
        }

        if (!isBlank(raw0.serpSnippet))
        {
            reason0 += " | SERP snippet: " + raw0.serpSnippet;
        }
        else if (!isBlank(raw0.serpTitle))
        {
            reason0 += " | SERP title: " + raw0.serpTitle;
        }

        return reason0;
    }

    private static void splitPersonNameIfNeeded(CandidateInvestor candidate0)
    {
        if (!isBlank(candidate0.contact1FirstName) || isBlank(candidate0.name))
        {
            return;
        }

        if (DiscoveredLinkedInTarget.TYPE_COMPANY.equals(candidate0.linkedinEntityType))
        {
            return;
        }

        String cleaned0 = candidate0.name.replace("| LinkedIn", "").trim();
        String[] pieces0 = cleaned0.split("\\s+");

        if (pieces0.length >= 1)
        {
            candidate0.contact1FirstName = pieces0[0];
        }

        if (pieces0.length >= 2)
        {
            candidate0.contact1LastName = pieces0[pieces0.length - 1];
        }
    }

    private static String normalize(String value0)
    {
        if (value0 == null)
        {
            return "";
        }

        return value0
            .toLowerCase()
            .replace("https://", "")
            .replace("http://", "")
            .replace("www.", "")
            .replaceAll("[^a-z0-9]", "")
            .trim();
    }

    public static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }

    private static String safeString(String value0)
    {
        return value0 == null ? "" : value0;
    }
}
