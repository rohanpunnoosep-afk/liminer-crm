package com.liminer.scout;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * ScoutUniverseRecord — one SEC-registered investment adviser parsed from the
 * Form ADV monthly bulk data, plus its Schedule D Section 7.B.1 private funds.
 * This is the stable contract consumed by the later Investor Scout pipeline
 * stages (prefilter, scoring, fit, email, orchestrator) — keep field names and
 * JSON shape stable once other tasks depend on it.
 */
public class ScoutUniverseRecord
{
    public int crd;
    public String firmName;
    public String website;
    public String city;
    public String state;
    public String country;
    public double raumTotal;
    public double raumDiscretionary;
    public int employees;
    public List<String> clientTypes;
    public String contactEmail;
    public String phone;
    public List<ScoutFundRecord> funds;
    public String snapshotMonth; // format YYYY-MM

    // Optional, nullable resources-axis inputs pre-resolved by an earlier stage
    // (ScoutSignalScorer never fetches these itself): IRS 990 total assets for
    // the nonprofit/endowment class, and a GLEIF "has a larger parent" flag.
    public Double nonprofitTotalAssets990;
    public Boolean gleifParentFlag;

    // Optional fit-axis inputs, blank when unknown/unresolved. nteeCode is the
    // IRS National Taxonomy of Exempt Entities code for nonprofit records (used
    // by ScoutFitTierA as a keyword-overlap signal). linkedinCompanyUrl is
    // resolved by a later pipeline stage (the email-finder task) and is the
    // last-resort source for ScoutFitTierB's profile-extraction waterfall.
    public String nteeCode;
    public String linkedinCompanyUrl;

    // Optional identity fields for non-US register sources (FcaRegisterClient,
    // EsmaRegisterClient, CompaniesHouseClient), which key on FRN/LEI/company
    // number rather than a numeric CRD. crd stays 0 for these records; never
    // repurpose crd to hold a non-numeric identifier. sourceRegister is a
    // provenance tag (e.g. "FCA", "ESMA", "COMPANIES_HOUSE"), blank for the
    // original SEC ADV universe.
    public String externalRegisterId;
    public String sourceRegister;

    // Optional, nullable fields captured from the ADV bulk CSV but not yet consumed by
    // any scoring stage (see ExtraDocuments/adv-column-audit.md for the full audit and
    // follow-up tasks). All UNVERIFIED against the real bulk file layout; resolved via
    // optional (non-throwing) header lookup, so they stay null/blank when the source
    // column is absent from a given report vintage.
    //
    // linkedinUrl (Item 1.I social media/website addresses): the firm's own LinkedIn
    // company page, sourced directly from the filing -- distinct from
    // linkedinCompanyUrl above, which is resolved by a *later* pipeline stage
    // (SERP/email-finder) and used as a fallback when this is blank.
    public String linkedinUrl;
    // secFileNumber (Item 1.P, "801-" format): direct EDGAR/Form D join key.
    public String secFileNumber;
    // lei: Legal Entity Identifier, joins GLEIF parent lookups for the resources score.
    public String lei;
    // umbrellaRegistration (Item 1.T-style umbrella/relying-adviser flag): true when this
    // filing covers multiple relying advisers under one CRD, for CRD-dedupe.
    public Boolean umbrellaRegistration;
    // Item 5.D client-type percentages (of AUM), sharper than clientTypes' plain list.
    public Double pctAssetsPooledVehicles;
    public Double pctAssetsPensionPlans;
    public Double pctAssetsCharitableOrgs;

    public ScoutUniverseRecord()
    {
        crd = 0;
        firmName = "";
        website = "";
        city = "";
        state = "";
        country = "";
        raumTotal = 0.0;
        raumDiscretionary = 0.0;
        employees = 0;
        clientTypes = new ArrayList<String>();
        contactEmail = "";
        phone = "";
        funds = new ArrayList<ScoutFundRecord>();
        snapshotMonth = "";
        nonprofitTotalAssets990 = null;
        gleifParentFlag = null;
        nteeCode = "";
        linkedinCompanyUrl = "";
        externalRegisterId = "";
        sourceRegister = "";
        linkedinUrl = "";
        secFileNumber = "";
        lei = "";
        umbrellaRegistration = null;
        pctAssetsPooledVehicles = null;
        pctAssetsPensionPlans = null;
        pctAssetsCharitableOrgs = null;
    }

    public JSONObject toJson()
    {
        JSONObject o0 = new JSONObject();
        o0.put("crd", crd);
        o0.put("firmName", safe(firmName));
        o0.put("website", safe(website));
        o0.put("city", safe(city));
        o0.put("state", safe(state));
        o0.put("country", safe(country));
        o0.put("raumTotal", raumTotal);
        o0.put("raumDiscretionary", raumDiscretionary);
        o0.put("employees", employees);
        o0.put("clientTypes", new JSONArray(clientTypes == null ? new ArrayList<String>() : clientTypes));
        o0.put("contactEmail", safe(contactEmail));
        o0.put("phone", safe(phone));
        o0.put("snapshotMonth", safe(snapshotMonth));
        o0.put("nonprofitTotalAssets990", nonprofitTotalAssets990 == null ? JSONObject.NULL : nonprofitTotalAssets990);
        o0.put("gleifParentFlag", gleifParentFlag == null ? JSONObject.NULL : gleifParentFlag);
        o0.put("nteeCode", safe(nteeCode));
        o0.put("linkedinCompanyUrl", safe(linkedinCompanyUrl));
        o0.put("externalRegisterId", safe(externalRegisterId));
        o0.put("sourceRegister", safe(sourceRegister));
        o0.put("linkedinUrl", safe(linkedinUrl));
        o0.put("secFileNumber", safe(secFileNumber));
        o0.put("lei", safe(lei));
        o0.put("umbrellaRegistration", umbrellaRegistration == null ? JSONObject.NULL : umbrellaRegistration);
        o0.put("pctAssetsPooledVehicles", pctAssetsPooledVehicles == null ? JSONObject.NULL : pctAssetsPooledVehicles);
        o0.put("pctAssetsPensionPlans", pctAssetsPensionPlans == null ? JSONObject.NULL : pctAssetsPensionPlans);
        o0.put("pctAssetsCharitableOrgs", pctAssetsCharitableOrgs == null ? JSONObject.NULL : pctAssetsCharitableOrgs);

        JSONArray fundsJson0 = new JSONArray();
        if (funds != null)
        {
            for (ScoutFundRecord f0 : funds) fundsJson0.put(f0.toJson());
        }
        o0.put("funds", fundsJson0);

        return o0;
    }

    public static ScoutUniverseRecord fromJson(JSONObject o0)
    {
        ScoutUniverseRecord r0 = new ScoutUniverseRecord();
        if (o0 == null) return r0;

        r0.crd = o0.optInt("crd", 0);
        r0.firmName = o0.optString("firmName", "");
        r0.website = o0.optString("website", "");
        r0.city = o0.optString("city", "");
        r0.state = o0.optString("state", "");
        r0.country = o0.optString("country", "");
        r0.raumTotal = o0.optDouble("raumTotal", 0.0);
        r0.raumDiscretionary = o0.optDouble("raumDiscretionary", 0.0);
        r0.employees = o0.optInt("employees", 0);
        r0.contactEmail = o0.optString("contactEmail", "");
        r0.phone = o0.optString("phone", "");
        r0.snapshotMonth = o0.optString("snapshotMonth", "");
        r0.nonprofitTotalAssets990 = o0.isNull("nonprofitTotalAssets990") || !o0.has("nonprofitTotalAssets990")
            ? null : o0.optDouble("nonprofitTotalAssets990");
        r0.gleifParentFlag = o0.isNull("gleifParentFlag") || !o0.has("gleifParentFlag")
            ? null : o0.optBoolean("gleifParentFlag");
        r0.nteeCode = o0.optString("nteeCode", "");
        r0.linkedinCompanyUrl = o0.optString("linkedinCompanyUrl", "");
        r0.externalRegisterId = o0.optString("externalRegisterId", "");
        r0.sourceRegister = o0.optString("sourceRegister", "");
        r0.linkedinUrl = o0.optString("linkedinUrl", "");
        r0.secFileNumber = o0.optString("secFileNumber", "");
        r0.lei = o0.optString("lei", "");
        r0.umbrellaRegistration = o0.isNull("umbrellaRegistration") || !o0.has("umbrellaRegistration")
            ? null : o0.optBoolean("umbrellaRegistration");
        r0.pctAssetsPooledVehicles = o0.isNull("pctAssetsPooledVehicles") || !o0.has("pctAssetsPooledVehicles")
            ? null : o0.optDouble("pctAssetsPooledVehicles");
        r0.pctAssetsPensionPlans = o0.isNull("pctAssetsPensionPlans") || !o0.has("pctAssetsPensionPlans")
            ? null : o0.optDouble("pctAssetsPensionPlans");
        r0.pctAssetsCharitableOrgs = o0.isNull("pctAssetsCharitableOrgs") || !o0.has("pctAssetsCharitableOrgs")
            ? null : o0.optDouble("pctAssetsCharitableOrgs");

        r0.clientTypes = new ArrayList<String>();
        JSONArray clientTypesJson0 = o0.optJSONArray("clientTypes");
        if (clientTypesJson0 != null)
        {
            for (int i0 = 0; i0 < clientTypesJson0.length(); i0++)
                r0.clientTypes.add(clientTypesJson0.optString(i0, ""));
        }

        r0.funds = new ArrayList<ScoutFundRecord>();
        JSONArray fundsJson0 = o0.optJSONArray("funds");
        if (fundsJson0 != null)
        {
            for (int i0 = 0; i0 < fundsJson0.length(); i0++)
                r0.funds.add(ScoutFundRecord.fromJson(fundsJson0.optJSONObject(i0)));
        }

        return r0;
    }

    private static String safe(String s0) { return s0 == null ? "" : s0; }
}
