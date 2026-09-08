package com.liminer.embed;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * CanonicalProfile is the closed-vocabulary, module-structured fact representation of
 * an LP that ProfileVectorLayout's eight independent blocks are built from. It holds no
 * contact-derived data (interaction history, status, sentiment) -- only facts about the
 * LP itself, which is what lets the profile be embedded once and shared across GPs.
 *
 * canonicalize() is the mechanism that makes the same LP hash identically no matter
 * which scrape or LLM call produced the input: every atom list is trimmed, lowercased,
 * deduped and lexicographically sorted, and any allocatorType outside the closed
 * ProfileVectorLayout.ALLOCATOR_TYPE_ORDER vocabulary snaps to "" (missing) rather than
 * passing through free text.
 */
public class CanonicalProfile
{
    public List<String> thesis;
    public List<String> pastInvestments;
    public List<String> newInvestmentAreas;
    public double aumUsd;
    public double capitalAllocatableUsd;
    public double pastInvestmentAmountUsd;
    public String allocatorType;
    public double timingMonthsSinceLastClose;

    public CanonicalProfile()
    {
        thesis = new ArrayList<String>();
        pastInvestments = new ArrayList<String>();
        newInvestmentAreas = new ArrayList<String>();
        aumUsd = Double.NaN;
        capitalAllocatableUsd = Double.NaN;
        pastInvestmentAmountUsd = Double.NaN;
        allocatorType = "";
        timingMonthsSinceLastClose = Double.NaN;
    }

    // Idempotent: trims/lowercases/dedupes/sorts every atom list and snaps allocatorType
    // to the closed vocabulary. Safe to call more than once.
    public void canonicalize()
    {
        thesis = canonicalizeAtoms(thesis);
        pastInvestments = canonicalizeAtoms(pastInvestments);
        newInvestmentAreas = canonicalizeAtoms(newInvestmentAreas);

        if (allocatorType == null)
        {
            allocatorType = "";
        }
        else
        {
            allocatorType = allocatorType.trim();
        }

        if (!allocatorType.isEmpty() && !Arrays.asList(ProfileVectorLayout.ALLOCATOR_TYPE_ORDER).contains(allocatorType))
        {
            allocatorType = "";
        }
    }

    private static List<String> canonicalizeAtoms(List<String> atoms0)
    {
        LinkedHashSet<String> deduped0 = new LinkedHashSet<String>();

        if (atoms0 != null)
        {
            for (String atom0 : atoms0)
            {
                if (atom0 == null)
                {
                    continue;
                }

                String normalized0 = atom0.trim().toLowerCase().replaceAll("\\s+", " ");

                if (!normalized0.isEmpty())
                {
                    deduped0.add(normalized0);
                }
            }
        }

        List<String> sorted0 = new ArrayList<String>(deduped0);
        java.util.Collections.sort(sorted0);
        return sorted0;
    }

    public JSONObject toJson()
    {
        canonicalize();

        JSONObject o0 = new JSONObject();

        if (!allocatorType.isEmpty())
        {
            o0.put("allocatorType", allocatorType);
        }

        if (!Double.isNaN(aumUsd))
        {
            o0.put("aumUsd", aumUsd);
        }

        if (!Double.isNaN(capitalAllocatableUsd))
        {
            o0.put("capitalAllocatableUsd", capitalAllocatableUsd);
        }

        if (!newInvestmentAreas.isEmpty())
        {
            o0.put("newInvestmentAreas", new JSONArray(newInvestmentAreas));
        }

        if (!Double.isNaN(pastInvestmentAmountUsd))
        {
            o0.put("pastInvestmentAmountUsd", pastInvestmentAmountUsd);
        }

        if (!pastInvestments.isEmpty())
        {
            o0.put("pastInvestments", new JSONArray(pastInvestments));
        }

        if (!thesis.isEmpty())
        {
            o0.put("thesis", new JSONArray(thesis));
        }

        if (!Double.isNaN(timingMonthsSinceLastClose))
        {
            o0.put("timingMonthsSinceLastClose", timingMonthsSinceLastClose);
        }

        return o0;
    }

    public static CanonicalProfile fromJson(JSONObject o0)
    {
        CanonicalProfile p0 = new CanonicalProfile();

        if (o0 == null)
        {
            return p0;
        }

        p0.allocatorType = o0.optString("allocatorType", "");
        p0.aumUsd = o0.has("aumUsd") ? o0.optDouble("aumUsd", Double.NaN) : Double.NaN;
        p0.capitalAllocatableUsd = o0.has("capitalAllocatableUsd") ? o0.optDouble("capitalAllocatableUsd", Double.NaN) : Double.NaN;
        p0.pastInvestmentAmountUsd = o0.has("pastInvestmentAmountUsd") ? o0.optDouble("pastInvestmentAmountUsd", Double.NaN) : Double.NaN;
        p0.timingMonthsSinceLastClose = o0.has("timingMonthsSinceLastClose") ? o0.optDouble("timingMonthsSinceLastClose", Double.NaN) : Double.NaN;

        p0.newInvestmentAreas = readAtoms(o0.optJSONArray("newInvestmentAreas"));
        p0.pastInvestments = readAtoms(o0.optJSONArray("pastInvestments"));
        p0.thesis = readAtoms(o0.optJSONArray("thesis"));

        p0.canonicalize();
        return p0;
    }

    private static List<String> readAtoms(JSONArray arr0)
    {
        List<String> atoms0 = new ArrayList<String>();

        if (arr0 != null)
        {
            for (int i0 = 0; i0 < arr0.length(); i0++)
            {
                atoms0.add(arr0.optString(i0, ""));
            }
        }

        return atoms0;
    }

    public String canonicalHash()
    {
        try
        {
            MessageDigest digest0 = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes0 = digest0.digest(toJson().toString().getBytes("UTF-8"));

            StringBuilder hex0 = new StringBuilder();
            for (byte b0 : hashBytes0)
            {
                hex0.append(String.format("%02x", b0));
            }

            return hex0.toString();
        }
        catch (Exception exception0)
        {
            throw new RuntimeException(exception0);
        }
    }
}
