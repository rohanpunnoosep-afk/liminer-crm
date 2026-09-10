package com.liminer.enrich;

/*
 * SerpUrls holds the URL-validation rules shared by every SearchProvider.
 *
 * Moved out of BrightDataSerpClient so later providers (DataForSEO, Brave,
 * DuckDuckGo, ...) can reuse the same "is this actually a usable destination"
 * logic instead of each re-implementing it slightly differently.
 */
public final class SerpUrls
{
    private SerpUrls()
    {
    }

    /*
     * True only for absolute http(s) URLs. Bright Data rejects anything else, so this
     * is the gate that keeps relative Google artefacts (/goto?..., /search?..., #frag)
     * out of the result set instead of discovering they are bad one wasted call later.
     */
    public static boolean isAbsoluteHttpUrl(String url0)
    {
        if (isBlank(url0))
        {
            return false;
        }

        String low0 = url0.trim().toLowerCase();
        if (!low0.startsWith("http://") && !low0.startsWith("https://"))
        {
            return false;
        }

        try
        {
            return !isBlank(java.net.URI.create(url0.trim()).getHost());
        }
        catch (Exception ignored0)
        {
            return false;
        }
    }

    public static boolean isUsefulUrl(String url0)
    {
        return isUsefulUrl(url0, new String[0]);
    }

    /*
     * A provider's own search-engine domain (e.g. duckduckgo.com) is never a usable
     * destination, but only that provider knows what its own domain is -- hence the
     * extraBlockedHosts0 overload.
     */
    public static boolean isUsefulUrl(String url0, String... extraBlockedHosts0)
    {
        if (isBlank(url0))
        {
            return false;
        }

        String lower0 = url0.toLowerCase();

        if (!lower0.startsWith("http"))
        {
            return false;
        }

        if (lower0.contains("google.com") || lower0.contains("gstatic.com"))
        {
            return false;
        }

        if (extraBlockedHosts0 != null)
        {
            for (String blocked0 : extraBlockedHosts0)
            {
                if (!isBlank(blocked0) && lower0.contains(blocked0.toLowerCase()))
                {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }
}
