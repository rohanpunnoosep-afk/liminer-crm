package com.liminer.enrich;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

/*
 * LinkedInScrapeResult is the normalized output from BrightDataLinkedInClient.
 *
 * Handles variable Bright Data JSON shapes for people and companies.
 * Missing fields return empty strings/lists — never crashes on unexpected JSON.
 */
public class LinkedInScrapeResult
{
    public String url;
    public String targetType;
    public String name;
    public String firstName;
    public String lastName;
    public String headline;
    public String position;
    public String currentCompanyName;
    public String currentCompanyLinkedInUrl;
    public String companyWebsite;
    public String location;
    public String country;
    public String region;
    public String about;
    public String rawJson;
    public ArrayList<String> pastWorkExperiences;
    public String pastWorkExperienceJson;
    public ArrayList<LinkedInAffiliation> affiliations;

    /**
     * True when Bright Data returned no experience rows for this profile.
     *
     * Measured against the live API on 2026-09-11: the people dataset
     * (gd_l1viktl72bvl7bjuj0) returns "experience": null for a large share of ordinary
     * profiles - confirmed on three of five sampled, including both investors this was
     * traced from - while still returning the topcard current_company. On those profiles
     * there is nothing to rank, so an employer picked from the affiliation list is really
     * just the topcard, and the caller must treat it as a lead rather than an answer.
     */
    public boolean experienceSectionMissing;
    public String followerCount;
    public ArrayList<String> recentPosts;
    public String recentPostsJson;

    public LinkedInScrapeResult()
    {
        url = "";
        targetType = "";
        name = "";
        firstName = "";
        lastName = "";
        headline = "";
        position = "";
        currentCompanyName = "";
        currentCompanyLinkedInUrl = "";
        companyWebsite = "";
        location = "";
        country = "";
        region = "";
        about = "";
        rawJson = "";
        pastWorkExperiences = new ArrayList<>();
        pastWorkExperienceJson = "[]";
        affiliations = new ArrayList<>();
        experienceSectionMissing = false;
        followerCount = "";
        recentPosts = new ArrayList<>();
        recentPostsJson = "[]";
    }

    public static LinkedInScrapeResult fromJson(
        String url0,
        String targetType0,
        JSONObject json0)
    {
        LinkedInScrapeResult result0 = new LinkedInScrapeResult();

        result0.url = safeString(url0);
        result0.targetType = safeString(targetType0);

        if (json0 == null)
        {
            return result0;
        }

        result0.rawJson = json0.toString();

        result0.name = firstNonBlank(
            json0.optString("name", ""),
            json0.optString("full_name", ""),
            json0.optString("company_name", ""),
            json0.optString("title", "")
        );
        result0.firstName = json0.optString("first_name", "");
        result0.lastName = json0.optString("last_name", "");
        // Strip professional credentials like ", CFA", ", MBA", ", PhD" that Bright Data appends
        int commaIdx0 = result0.lastName.indexOf(',');
        if (commaIdx0 > 0)
        {
            result0.lastName = result0.lastName.substring(0, commaIdx0).trim();
        }
        result0.headline = firstNonBlank(
            json0.optString("headline", ""),
            json0.optString("position", ""),
            json0.optString("subtitle", "")
        );
        result0.position = json0.optString("position", result0.headline);
        result0.location = firstNonBlank(
            json0.optString("location", ""),
            json0.optString("city", ""),
            json0.optString("country", "")
        );
        result0.country = firstNonBlank(
            json0.optString("country", ""),
            json0.optString("country_code", ""),
            ""
        );
        result0.region = firstNonBlank(
            json0.optString("region", ""),
            json0.optString("city", ""),
            json0.optString("location", "")
        );
        result0.about = firstNonBlank(
            json0.optString("about", ""),
            json0.optString("description", ""),
            json0.optString("overview", "")
        );
        result0.companyWebsite = firstNonBlank(
            json0.optString("website", ""),
            json0.optString("company_website", ""),
            json0.optString("url_website", "")
        );

        JSONObject currentCompany0 = json0.optJSONObject("current_company");
        if (currentCompany0 != null)
        {
            result0.currentCompanyName = firstNonBlank(
                currentCompany0.optString("name", ""),
                currentCompany0.optString("company_name", "")
            );
            result0.currentCompanyLinkedInUrl = firstNonBlank(
                currentCompany0.optString("url", ""),
                currentCompany0.optString("link", ""),
                currentCompany0.optString("linkedin_url", ""),
                currentCompany0.optString("company_linkedin_url", "")
            );
        }

        result0.currentCompanyName = firstNonBlank(
            result0.currentCompanyName,
            json0.optString("current_company_name", ""),
            json0.optString("company", ""),
            json0.optString("company_name", "")
        );

        result0.currentCompanyLinkedInUrl = firstNonBlank(
            result0.currentCompanyLinkedInUrl,
            json0.optString("company_linkedin_url", ""),
            json0.optString("current_company_url", ""),
            json0.optString("linkedin_company_url", ""),
            ""
        );

        if (DiscoveredLinkedInTarget.TYPE_COMPANY.equals(targetType0))
        {
            result0.currentCompanyName = firstNonBlank(result0.currentCompanyName, result0.name, "");
            result0.currentCompanyLinkedInUrl = firstNonBlank(result0.currentCompanyLinkedInUrl, url0, "");
        }

        if (isBlank(result0.companyWebsite))
        {
            JSONArray websites0 = json0.optJSONArray("websites");
            if (websites0 != null && websites0.length() > 0)
            {
                result0.companyWebsite = websites0.optString(0, "");
            }
        }

        result0.affiliations = parseAffiliations(json0);
        result0.experienceSectionMissing = result0.affiliations.isEmpty();
        addCurrentCompanyAffiliation(result0);
        result0.pastWorkExperiences = parseExperiences(json0);
        result0.pastWorkExperienceJson = buildExperienceJson(result0.pastWorkExperiences);

        result0.followerCount = firstNonBlank(
            json0.optString("followers", ""),
            json0.optString("follower_count", ""),
            json0.optString("followers_count", "")
        );
        result0.recentPosts = parsePosts(json0);
        result0.recentPostsJson = buildExperienceJson(result0.recentPosts); // reuse existing helper

        return result0;
    }

    public boolean hasWorkedAt(String companyName0)
    {
        if (isBlank(companyName0))
        {
            return false;
        }
        String target0 = companyName0.trim().toLowerCase();
        for (String exp0 : pastWorkExperiences)
        {
            if (exp0.toLowerCase().contains(target0))
            {
                return true;
            }
        }
        return !isBlank(currentCompanyName) && currentCompanyName.toLowerCase().contains(target0);
    }

    public boolean currentlyWorksAt(String companyName0)
    {
        if (isBlank(companyName0))
        {
            return false;
        }
        String target0 = companyName0.trim().toLowerCase();
        return !isBlank(currentCompanyName) && currentCompanyName.toLowerCase().contains(target0);
    }

    public void printSummary()
    {
        System.out.println("===== LINKEDIN SCRAPE RESULT =====");
        System.out.println("Type: " + targetType);
        System.out.println("URL: " + url);
        System.out.println("Name: " + name);
        System.out.println("Position: " + position);
        System.out.println("Current Company: " + currentCompanyName);
        System.out.println("Current Company LinkedIn: " + currentCompanyLinkedInUrl);
        System.out.println("Website: " + companyWebsite);
        System.out.println("Location: " + location);
        System.out.println("Country: " + country);
        System.out.println("Region: " + region);
        System.out.println("Past Work Experiences: " + pastWorkExperiences.size());
        System.out.println("Affiliations: " + affiliations.size());
    }

    /*
     * Builds the structured affiliation list from whichever experience array Bright Data
     * returned. Unlike parseExperiences, which flattens each row to a display string, this
     * keeps the company LinkedIn URL and the "is this current" flag so the caller can pick
     * the right employer out of several concurrent ones.
     */
    private static ArrayList<LinkedInAffiliation> parseAffiliations(JSONObject json0)
    {
        ArrayList<LinkedInAffiliation> affiliations0 = new ArrayList<>();

        JSONArray expArray0 = null;
        for (String key0 : new String[]{"experience", "experiences", "positions"})
        {
            JSONArray candidate0 = json0.optJSONArray(key0);
            if (candidate0 != null && candidate0.length() > 0)
            {
                expArray0 = candidate0;
                break;
            }
        }

        if (expArray0 == null)
        {
            return affiliations0;
        }

        for (int i = 0; i < expArray0.length(); i++)
        {
            Object entry0 = expArray0.opt(i);

            if (!(entry0 instanceof JSONObject))
            {
                continue;
            }

            JSONObject exp0 = (JSONObject) entry0;

            String company0 = firstNonBlank(
                exp0.optString("company", ""),
                exp0.optString("company_name", ""),
                exp0.optString("name", "")
            );

            String companyUrl0 = firstNonBlank(
                exp0.optString("company_linkedin_url", ""),
                exp0.optString("company_url", ""),
                exp0.optString("url", ""),
                exp0.optString("link", "")
            );

            JSONObject compObj0 = exp0.optJSONObject("company");
            if (compObj0 != null)
            {
                company0 = firstNonBlank(
                    company0,
                    compObj0.optString("name", ""),
                    compObj0.optString("company_name", "")
                );
                companyUrl0 = firstNonBlank(
                    companyUrl0,
                    compObj0.optString("url", ""),
                    compObj0.optString("link", ""),
                    compObj0.optString("linkedin_url", "")
                );
            }

            if (isBlank(company0))
            {
                continue;
            }

            String title0 = firstNonBlank(
                exp0.optString("title", ""),
                exp0.optString("subtitle", ""),
                exp0.optString("position", "")
            );

            // Bright Data's people dataset reports tenure as start_date / end_date, where a
            // live role carries the literal "Present", plus a human duration ("3 yrs 5 mos").
            String startDate0 = exp0.optString("start_date", "");
            String endDate0 = exp0.optString("end_date", "");
            String duration0 = firstNonBlank(
                exp0.optString("duration", ""),
                exp0.optString("duration_short", "")
            );
            String dateRange0 = firstNonBlank(
                exp0.optString("date_range", ""),
                duration0,
                startDate0
            );

            LinkedInAffiliation affiliation0 = new LinkedInAffiliation(
                company0,
                companyUrl0,
                title0,
                dateRange0,
                isCurrentRole(dateRange0, endDate0, exp0),
                affiliations0.size()
            );

            affiliation0.startDate = startDate0;
            affiliation0.endDate = endDate0;
            affiliation0.duration = duration0;

            // Nested positions: Bright Data reports several titles held at one company as
            // a positions array inside the company entry, so the company row itself can
            // carry no title at all.
            JSONArray nested0 = exp0.optJSONArray("positions");
            if (isBlank(affiliation0.title) && nested0 != null && nested0.length() > 0)
            {
                JSONObject first0 = nested0.optJSONObject(0);
                if (first0 != null)
                {
                    affiliation0.title = firstNonBlank(
                        first0.optString("title", ""),
                        first0.optString("subtitle", "")
                    );
                    if (isBlank(affiliation0.startDate))
                    {
                        affiliation0.startDate = first0.optString("start_date", "");
                    }

                    if (isBlank(affiliation0.endDate))
                    {
                        affiliation0.endDate = first0.optString("end_date", "");
                    }

                    if (!affiliation0.current)
                    {
                        affiliation0.current = isCurrentRole(
                            firstNonBlank(first0.optString("date_range", ""), first0.optString("duration", "")),
                            first0.optString("end_date", ""),
                            first0
                        );
                    }
                }
            }

            affiliations0.add(affiliation0);
        }

        return affiliations0;
    }

    /*
     * Whether a role is ongoing. Bright Data writes the literal "Present" into end_date for
     * a live role, which is the reliable signal; the rest are fallbacks for the other JSON
     * shapes the dataset has returned over time.
     */
    private static boolean isCurrentRole(String dateRange0, String endDate0, JSONObject exp0)
    {
        if (exp0 != null && exp0.has("is_current"))
        {
            return exp0.optBoolean("is_current", false);
        }

        String end0 = safeString(endDate0).toLowerCase().trim();

        if (end0.contains("present") || end0.equals("current") || end0.equals("now"))
        {
            return true;
        }

        // An end date naming a real year is a role that has ended, whatever else says.
        if (end0.matches(".*(19|20)\\d{2}.*"))
        {
            return false;
        }

        String range0 = safeString(dateRange0).toLowerCase();

        if (range0.contains("present") || range0.contains(" - now"))
        {
            return true;
        }

        // A closed year range ("2011 - 2015") is past even when end_date itself is absent.
        if (range0.matches(".*(19|20)\\d{2}\\s*[-\u2013]\\s*(19|20)\\d{2}.*"))
        {
            return false;
        }

        // What is left is a row with a start and no end, i.e. open-ended.
        return isBlank(end0) && range0.length() > 0;
    }

    /*
     * The company LinkedIn reports as "current_company" is whatever sits at the top of the
     * profile. It still belongs in the affiliation list, but only as one candidate among
     * the experience rows, not as the answer.
     */
    private static void addCurrentCompanyAffiliation(LinkedInScrapeResult result0)
    {
        if (isBlank(result0.currentCompanyName))
        {
            return;
        }

        for (LinkedInAffiliation existing0 : result0.affiliations)
        {
            if (existing0.companyName.equalsIgnoreCase(result0.currentCompanyName.trim()))
            {
                existing0.current = true;
                if (isBlank(existing0.companyLinkedInUrl))
                {
                    existing0.companyLinkedInUrl = result0.currentCompanyLinkedInUrl;
                }
                if (isBlank(existing0.title))
                {
                    existing0.title = firstNonBlank(result0.position, result0.headline, "");
                }
                return;
            }
        }

        LinkedInAffiliation affiliation0 = new LinkedInAffiliation(
            result0.currentCompanyName,
            result0.currentCompanyLinkedInUrl,
            firstNonBlank(result0.position, result0.headline, ""),
            "",
            true,
            0
        );

        affiliation0.source = LinkedInAffiliation.SOURCE_TOPCARD;

        result0.affiliations.add(0, affiliation0);
    }

    private static ArrayList<String> parseExperiences(JSONObject json0)
    {
        ArrayList<String> experiences0 = new ArrayList<>();

        JSONArray expArray0 = null;
        for (String key0 : new String[]{"experience", "experiences", "positions"})
        {
            JSONArray candidate0 = json0.optJSONArray(key0);
            if (candidate0 != null && candidate0.length() > 0)
            {
                expArray0 = candidate0;
                break;
            }
        }

        if (expArray0 == null)
        {
            return experiences0;
        }

        for (int i = 0; i < expArray0.length(); i++)
        {
            Object entry0 = expArray0.opt(i);

            if (entry0 instanceof JSONObject)
            {
                JSONObject exp0 = (JSONObject) entry0;
                String title0 = firstNonBlank(
                    exp0.optString("title", ""),
                    exp0.optString("subtitle", "")
                );

                String company0 = firstNonBlank(
                    exp0.optString("company", ""),
                    exp0.optString("company_name", ""),
                    exp0.optString("name", "")
                );

                if (isBlank(company0))
                {
                    JSONObject compObj0 = exp0.optJSONObject("company");
                    if (compObj0 != null)
                    {
                        company0 = firstNonBlank(
                            compObj0.optString("name", ""),
                            compObj0.optString("company_name", "")
                        );
                    }
                }

                String dateRange0 = firstNonBlank(
                    exp0.optString("date_range", ""),
                    exp0.optString("duration", "")
                );

                String formatted0;
                if (!isBlank(title0) && !isBlank(company0))
                {
                    formatted0 = title0 + " at " + company0;
                }
                else if (!isBlank(title0))
                {
                    formatted0 = title0;
                }
                else if (!isBlank(company0))
                {
                    formatted0 = company0;
                }
                else
                {
                    continue;
                }

                if (!isBlank(dateRange0))
                {
                    formatted0 = formatted0 + " (" + dateRange0 + ")";
                }

                experiences0.add(formatted0);
            }
            else if (entry0 instanceof String)
            {
                String str0 = ((String) entry0).trim();
                if (!isBlank(str0))
                {
                    experiences0.add(str0);
                }
            }
        }

        return experiences0;
    }

    private static ArrayList<String> parsePosts(JSONObject json0)
    {
        ArrayList<String> posts0 = new ArrayList<>();

        JSONArray postsArray0 = null;
        for (String key0 : new String[]{"posts", "activity", "recent_posts", "updates"})
        {
            JSONArray candidate0 = json0.optJSONArray(key0);
            if (candidate0 != null && candidate0.length() > 0)
            {
                postsArray0 = candidate0;
                break;
            }
        }

        if (postsArray0 == null)
        {
            return posts0;
        }

        for (int i = 0; i < postsArray0.length(); i++)
        {
            if (posts0.size() >= 10)
            {
                break;
            }

            Object entry0 = postsArray0.opt(i);

            if (entry0 instanceof JSONObject)
            {
                JSONObject post0 = (JSONObject) entry0;
                String text0 = firstNonBlank(
                    post0.optString("text", ""),
                    post0.optString("title", ""),
                    post0.optString("post_text", ""),
                    post0.optString("description", ""),
                    post0.optString("caption", "")
                );
                if (!isBlank(text0))
                {
                    posts0.add(text0.trim());
                }
            }
            else if (entry0 instanceof String)
            {
                String str0 = ((String) entry0).trim();
                if (!isBlank(str0))
                {
                    posts0.add(str0);
                }
            }
        }

        return posts0;
    }

    private static String buildExperienceJson(ArrayList<String> experiences0)
    {
        if (experiences0 == null || experiences0.isEmpty())
        {
            return "[]";
        }

        StringBuilder sb0 = new StringBuilder("[");
        for (int i = 0; i < experiences0.size(); i++)
        {
            sb0.append("\"").append(escapeJsonString(experiences0.get(i))).append("\"");
            if (i < experiences0.size() - 1)
            {
                sb0.append(",");
            }
        }
        sb0.append("]");
        return sb0.toString();
    }

    private static String escapeJsonString(String value0)
    {
        if (value0 == null)
        {
            return "";
        }
        return value0
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static String firstNonBlank(String a0, String b0, String c0, String d0, String e0)
    {
        if (!isBlank(a0)) return a0;
        if (!isBlank(b0)) return b0;
        if (!isBlank(c0)) return c0;
        if (!isBlank(d0)) return d0;
        if (!isBlank(e0)) return e0;
        return "";
    }

    private static String firstNonBlank(String a0, String b0, String c0, String d0)
    {
        if (!isBlank(a0)) return a0;
        if (!isBlank(b0)) return b0;
        if (!isBlank(c0)) return c0;
        if (!isBlank(d0)) return d0;
        return "";
    }

    private static String firstNonBlank(String a0, String b0)
    {
        if (!isBlank(a0)) return a0;
        if (!isBlank(b0)) return b0;
        return "";
    }

    private static String firstNonBlank(String a0, String b0, String c0)
    {
        if (!isBlank(a0)) return a0;
        if (!isBlank(b0)) return b0;
        if (!isBlank(c0)) return c0;
        return "";
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }

    private static String safeString(String value0)
    {
        return value0 == null ? "" : value0;
    }
}
