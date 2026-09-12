package com.liminer.enrich;

/*
 * LinkedInAffiliation is one company a person is attached to on LinkedIn.
 *
 * A LinkedIn profile usually lists several: the fund someone actually runs, plus the
 * professional bodies, advisory seats and board memberships they also hold. Bright Data
 * returns them as an experience array and reports whichever one sits at the top of the
 * profile as "current_company", which is often the membership rather than the fund.
 * Keeping every affiliation with its title, company URL and date range lets
 * EmployerSelector choose the investing employer instead of the first row.
 */
public class LinkedInAffiliation
{
    public String companyName;
    public String companyLinkedInUrl;
    public String companyWebsite;
    public String title;
    public String dateRange;
    public String startDate;
    public String endDate;
    public String duration;
    public boolean current;
    public int listOrder;

    /**
     * Where this affiliation came from: SOURCE_EXPERIENCE when Bright Data listed it in the
     * experience array, SOURCE_TOPCARD when it was synthesised from the profile topcard's
     * current_company, SOURCE_HEADLINE when it was parsed out of the person's headline text.
     *
     * Bright Data returns experience: null for a large share of profiles, and on those the
     * only affiliation available is the topcard - a single unranked guess. Recording the
     * source lets the caller tell "the fund won on its merits" from "the fund is the one
     * thing we were handed", which is the difference between a trusted pick and one that
     * still needs cross-checking.
     */
    public static final String SOURCE_EXPERIENCE = "experience";
    public static final String SOURCE_TOPCARD = "topcard";
    public static final String SOURCE_HEADLINE = "headline";

    public String source;

    public LinkedInAffiliation()
    {
        companyName = "";
        companyLinkedInUrl = "";
        companyWebsite = "";
        title = "";
        dateRange = "";
        startDate = "";
        endDate = "";
        duration = "";
        current = false;
        listOrder = 0;
        source = SOURCE_EXPERIENCE;
    }

    public LinkedInAffiliation(
        String companyName0,
        String companyLinkedInUrl0,
        String title0,
        String dateRange0,
        boolean current0,
        int listOrder0)
    {
        companyName = safeString(companyName0);
        companyLinkedInUrl = safeString(companyLinkedInUrl0);
        companyWebsite = "";
        title = safeString(title0);
        dateRange = safeString(dateRange0);
        startDate = "";
        endDate = "";
        duration = "";
        current = current0;
        listOrder = listOrder0;
        source = SOURCE_EXPERIENCE;
    }

    public boolean hasCompanyName()
    {
        return !isBlank(companyName);
    }

    /**
     * Rough tenure in years, read from Bright Data's "duration" string ("3 yrs 5 mos") or
     * from the gap between start and end year. Returns 0 when neither can be read.
     *
     * Used only as a tiebreak: a decade at a firm is more likely to be a job than a
     * nominal seat, but a fund founded last year is still a fund, so this never outweighs
     * the name and title signals.
     */
    public double tenureYears()
    {
        double fromDuration0 = parseDurationYears(duration);

        if (fromDuration0 > 0.0)
        {
            return fromDuration0;
        }

        int startYear0 = parseYear(startDate);

        if (startYear0 <= 0)
        {
            startYear0 = parseYear(dateRange);
        }

        if (startYear0 <= 0)
        {
            return 0.0;
        }

        int endYear0 = current ? java.time.Year.now().getValue() : parseYear(endDate);

        if (endYear0 < startYear0)
        {
            return 0.0;
        }

        return endYear0 - startYear0;
    }

    private static double parseDurationYears(String duration0)
    {
        if (isBlank(duration0))
        {
            return 0.0;
        }

        double years0 = 0.0;
        java.util.regex.Matcher yearMatch0 = java.util.regex.Pattern
            .compile("(\\d+)\\s*(?:yr|yrs|year|years)")
            .matcher(duration0.toLowerCase());

        if (yearMatch0.find())
        {
            years0 += Double.parseDouble(yearMatch0.group(1));
        }

        java.util.regex.Matcher monthMatch0 = java.util.regex.Pattern
            .compile("(\\d+)\\s*(?:mo|mos|month|months)")
            .matcher(duration0.toLowerCase());

        if (monthMatch0.find())
        {
            years0 += Double.parseDouble(monthMatch0.group(1)) / 12.0;
        }

        return years0;
    }

    private static int parseYear(String value0)
    {
        if (isBlank(value0))
        {
            return 0;
        }

        java.util.regex.Matcher match0 = java.util.regex.Pattern
            .compile("(19|20)\\d{2}")
            .matcher(value0);

        if (match0.find())
        {
            return Integer.parseInt(match0.group());
        }

        return 0;
    }

    public String describe()
    {
        String text0 = companyName;

        if (!isBlank(title))
        {
            text0 = title + " at " + companyName;
        }

        String period0 = dateRange;

        if (isBlank(period0) && !isBlank(startDate))
        {
            period0 = startDate + " - " + (isBlank(endDate) ? "Present" : endDate);
        }

        if (!isBlank(period0))
        {
            text0 = text0 + " (" + period0 + ")";
        }

        return text0;
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }

    private static String safeString(String value0)
    {
        return value0 == null ? "" : value0.trim();
    }
}
