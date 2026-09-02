package com.liminer.enrich;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/*
 * LinkedInUrlExtractor filters generic SERP results down to LinkedIn profile/company URLs.
 *
 * This is deliberately strict. It ignores jobs, posts, pulse, school, feed, and login URLs
 * because those are usually bad candidate records for CRM discovery.
 */
public class LinkedInUrlExtractor
{
    public LinkedInUrlExtractor()
    {
    }

    public ArrayList<DiscoveredLinkedInTarget> extract(ArrayList<SerpResult> serpResults0)
    {
        LinkedHashMap<String, DiscoveredLinkedInTarget> unique0 = new LinkedHashMap<String, DiscoveredLinkedInTarget>();

        if (serpResults0 == null)
        {
            return new ArrayList<DiscoveredLinkedInTarget>();
        }

        for (SerpResult result0 : serpResults0)
        {
            if (result0 == null || isBlank(result0.url))
            {
                continue;
            }

            String normalized0 = normalizeLinkedInUrl(result0.url);
            String type0 = classify(normalized0);

            if (isBlank(type0))
            {
                continue;
            }

            if (!unique0.containsKey(normalized0))
            {
                unique0.put(
                    normalized0,
                    new DiscoveredLinkedInTarget(
                        normalized0,
                        type0,
                        result0.queryUsed,
                        result0.title,
                        result0.snippet,
                        result0.rank
                    )
                );
            }
        }

        return new ArrayList<DiscoveredLinkedInTarget>(unique0.values());
    }

    public static String classify(String url0)
    {
        if (isBlank(url0))
        {
            return "";
        }

        String lower0 = url0.toLowerCase();

        if (!lower0.contains("linkedin.com/"))
        {
            return "";
        }

        if (lower0.contains("/jobs/") || lower0.contains("/posts/") ||
            lower0.contains("/pulse/") || lower0.contains("/school/") ||
            lower0.contains("/feed/") || lower0.contains("/login") ||
            lower0.contains("/learning/"))
        {
            return "";
        }

        if (lower0.contains("linkedin.com/in/"))
        {
            return DiscoveredLinkedInTarget.TYPE_PERSON;
        }

        if (lower0.contains("linkedin.com/company/") || lower0.contains("linkedin.com/organization-guest/company/"))
        {
            return DiscoveredLinkedInTarget.TYPE_COMPANY;
        }

        return "";
    }

    public static String normalizeLinkedInUrl(String url0)
    {
        if (url0 == null)
        {
            return "";
        }

        String cleaned0 = BrightDataSerpClient.cleanGoogleRedirectUrl(url0).trim();

        try
        {
            URI uri0 = URI.create(cleaned0);
            String scheme0 = uri0.getScheme() == null ? "https" : uri0.getScheme().toLowerCase();
            String host0 = uri0.getHost();

            if (host0 == null)
            {
                return cleaned0;
            }

            host0 = host0.toLowerCase();
            if (host0.equals("linkedin.com"))
            {
                host0 = "www.linkedin.com";
            }

            String path0 = uri0.getPath() == null ? "" : uri0.getPath();
            if (path0.endsWith("/") && path0.length() > 1)
            {
                path0 = path0.substring(0, path0.length() - 1);
            }

            return scheme0 + "://" + host0 + path0;
        }
        catch (Exception exception0)
        {
            int queryIndex0 = cleaned0.indexOf("?");
            if (queryIndex0 != -1)
            {
                cleaned0 = cleaned0.substring(0, queryIndex0);
            }
            return cleaned0;
        }
    }

    public ArrayList<DiscoveredLinkedInTarget> extractPeople(ArrayList<SerpResult> serpResults0)
    {
        ArrayList<DiscoveredLinkedInTarget> all0 = extract(serpResults0);
        ArrayList<DiscoveredLinkedInTarget> people0 = new ArrayList<>();
        for (DiscoveredLinkedInTarget t0 : all0)
        {
            if (t0.isPerson())
            {
                people0.add(t0);
            }
        }
        return people0;
    }

    public ArrayList<DiscoveredLinkedInTarget> extractCompanies(ArrayList<SerpResult> serpResults0)
    {
        ArrayList<DiscoveredLinkedInTarget> all0 = extract(serpResults0);
        ArrayList<DiscoveredLinkedInTarget> companies0 = new ArrayList<>();
        for (DiscoveredLinkedInTarget t0 : all0)
        {
            if (t0.isCompany())
            {
                companies0.add(t0);
            }
        }
        return companies0;
    }

    public DiscoveredLinkedInTarget extractBestPerson(ArrayList<SerpResult> serpResults0)
    {
        ArrayList<DiscoveredLinkedInTarget> people0 = extractPeople(serpResults0);
        return people0.isEmpty() ? null : people0.get(0);
    }

    public DiscoveredLinkedInTarget extractBestCompany(ArrayList<SerpResult> serpResults0)
    {
        ArrayList<DiscoveredLinkedInTarget> companies0 = extractCompanies(serpResults0);
        return companies0.isEmpty() ? null : companies0.get(0);
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }
}
