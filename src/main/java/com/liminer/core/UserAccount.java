package com.liminer.core;

import java.util.ArrayList;

public class UserAccount
{
    public String userId;
    public String email;
    public String fundName;
    public String crmConfigId;

    // Store FULL internal names:
    // Example: "Ada Lovelace | John Smith"
    public ArrayList<String> internalNames;
    public ArrayList<String> internalEmails;

    // Used so AI does not confuse your own fund/company with an investor.
    public String internalFundName;
    public String internalWebsite;

    public String extraData;

    public String clientSectorTags;
    public String clientMicrosectorTags;
    public String clientGeography;
    public String clientStages;
    public String clientInvestmentThesis;
    public String clientProfileJson;

    public UserAccount(
        String userId,
        String email,
        String fundName,
        String crmConfigId,
        ArrayList<String> internalNames,
        ArrayList<String> internalEmails,
        String internalFundName,
        String internalWebsite,
        String extraData,
        String clientSectorTags,
        String clientMicrosectorTags,
        String clientGeography,
        String clientStages,
        String clientInvestmentThesis,
        String clientProfileJson
    )
    {
        this.userId = userId;
        this.email = email;
        this.fundName = fundName;
        this.crmConfigId = crmConfigId;
        this.internalNames = internalNames;
        this.internalEmails = internalEmails;
        this.internalFundName = internalFundName;
        this.internalWebsite = internalWebsite;
        this.extraData = extraData;
        this.clientSectorTags = clientSectorTags == null ? "" : clientSectorTags;
        this.clientMicrosectorTags = clientMicrosectorTags == null ? "" : clientMicrosectorTags;
        this.clientGeography = clientGeography == null ? "" : clientGeography;
        this.clientStages = clientStages == null ? "" : clientStages;
        this.clientInvestmentThesis = clientInvestmentThesis == null ? "" : clientInvestmentThesis;
        this.clientProfileJson = clientProfileJson == null ? "" : clientProfileJson;
    }

    public void printSummary()
    {
        System.out.println("===== USER ACCOUNT =====");
        System.out.println("User ID: " + userId);
        System.out.println("Email: " + email);
        System.out.println("Fund Name: " + fundName);
        System.out.println("CRM Config ID: " + crmConfigId);
        System.out.println("Internal Names: " + internalNames);
        System.out.println("Internal Emails: " + internalEmails);
        System.out.println("Internal Fund Name: " + internalFundName);
        System.out.println("Internal Website: " + internalWebsite);
        System.out.println("Extra Data: " + extraData);
        System.out.println("Client Sector Tags: " + clientSectorTags);
        System.out.println("Client Microsector Tags: " + clientMicrosectorTags);
        System.out.println("Client Geography: " + clientGeography);
        System.out.println("Client Stages: " + clientStages);
        System.out.println("Client Investment Thesis: " + clientInvestmentThesis);
        System.out.println("Client Profile JSON: " + clientProfileJson);
        System.out.println("========================");
    }
}