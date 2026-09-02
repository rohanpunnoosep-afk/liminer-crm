package com.liminer.enrich;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebsiteCrawlerService {

    private static final String BRIGHT_DATA_API_TOKEN = System.getenv("BRIGHT_DATA_API_TOKEN");
    private static final String BRIGHT_DATA_ZONE = "web_unlocker1";

    // Max bio/website pages crawled per site. Bio info is usually on the home
    // page plus 1-3 team/about pages, and pages are crawled SEQUENTIALLY at up
    // to 120s each, so this is the single biggest lever on worst-case crawl
    // time. Default 4; tunable via env (BD_MAX_CRAWL_PAGES) without a recompile.
    private static final int MAX_PAGES_TO_SCRAPE = getEnvInt("BD_MAX_CRAWL_PAGES", 4);

    private static int getEnvInt(String name0, int default0) {
        String raw0 = System.getenv(name0);
        if (raw0 == null || raw0.trim().isEmpty()) { return default0; }
        try { return Integer.parseInt(raw0.trim()); }
        catch (NumberFormatException e0) { return default0; }
    }

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java GenericWebsiteCrawler <websiteUrl>");
            return;
        }

        String rootUrl = normalizeRootUrl(args[0]);

        System.out.println("Starting crawl: " + rootUrl);

        LinkedHashMap<String, String> scrapedPages = crawlWebsite(rootUrl);

        System.out.println("\n===== SCRAPED PAGES =====");
        for (String url : scrapedPages.keySet()) {
            System.out.println(url + " | chars: " + scrapedPages.get(url).length());
        }

        String outputJson = buildOutputJson(rootUrl, scrapedPages);
        System.out.println("\n===== OUTPUT JSON =====");
        System.out.println(outputJson);
    }

    public static LinkedHashMap<String, String> crawlWebsite(String rootUrl) throws Exception {
        LinkedHashMap<String, String> scrapedPages = new LinkedHashMap<>();

        String homeHtml = scrapeUrl(rootUrl);
        scrapedPages.put(rootUrl, homeHtml);

        List<String> allLinks = extractLinks(homeHtml, rootUrl);
        List<String> usefulLinks = filterUsefulLinks(allLinks, rootUrl);

        System.out.println("Found links: " + allLinks.size());
        System.out.println("Useful links selected: " + usefulLinks.size());

        for (String link : usefulLinks) {
            if (scrapedPages.size() >= MAX_PAGES_TO_SCRAPE) {
                break;
            }

            if (scrapedPages.containsKey(link)) {
                continue;
            }

            try {
                System.out.println("Scraping: " + link);
                String html = scrapeUrl(link);
                scrapedPages.put(link, html);
                Thread.sleep(500);
            } catch (Exception e) {
                System.out.println("Failed to scrape: " + link);
                System.out.println("Reason: " + e.getMessage());
            }
        }

        return scrapedPages;
    }

    public static String scrapeUrl(String targetUrl) throws Exception {
        if (BRIGHT_DATA_API_TOKEN == null || BRIGHT_DATA_API_TOKEN.isBlank()) {
            throw new RuntimeException("Missing BRIGHT_DATA_API_TOKEN environment variable.");
        }

        String body = "{"
                + "\"zone\":\"" + escapeJson(BRIGHT_DATA_ZONE) + "\","
                + "\"url\":\"" + escapeJson(targetUrl) + "\","
                + "\"format\":\"raw\""
                + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.brightdata.com/request"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + BRIGHT_DATA_API_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        BrightDataThrottle.acquire();
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int gateStatus0 = response.statusCode();
            if (gateStatus0 == 429 || gateStatus0 == 502 || gateStatus0 == 503) {
                BrightDataThrottle.noteThrottle();
            }
        } catch (java.net.http.HttpTimeoutException timeout0) {
            BrightDataThrottle.noteThrottle();
            throw timeout0;
        } finally {
            BrightDataThrottle.release();
        }

        String responseBody0 = response.body();

        if (responseBody0.startsWith("Request Failed"))
        {
            throw new RuntimeException("Bright Data returned failure body: " + responseBody0);
        }

        if (responseBody0.toLowerCase().contains("requested site is not available for immediate access mode"))
        {
            throw new RuntimeException("Bright Data robots/immediate-access failure: " + responseBody0);
        }

        int statusCode = response.statusCode();

        if (statusCode < 200 || statusCode >= 300) {
            throw new RuntimeException("Bright Data request failed. Status: "
                    + statusCode + ". Body: " + response.body());
        }

        return responseBody0;
    }

    public static List<String> extractLinks(String html, String rootUrl) throws Exception {
        LinkedHashSet<String> links = new LinkedHashSet<>();

        Pattern pattern = Pattern.compile("href\\s*=\\s*[\"']([^\"'#]+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);

        URI rootUri = URI.create(rootUrl);

        while (matcher.find()) {
            String rawHref = matcher.group(1).trim();

            if (rawHref.isBlank()) {
                continue;
            }

            if (rawHref.startsWith("mailto:")
                    || rawHref.startsWith("tel:")
                    || rawHref.startsWith("javascript:")
                    || rawHref.startsWith("#")) {
                continue;
            }

            try {
                URI resolved = rootUri.resolve(rawHref);
                String normalized = normalizeUrl(resolved.toString());

                if (!normalized.isBlank()) {
                    links.add(normalized);
                }

            } catch (Exception ignored) {
            }
        }

        return new ArrayList<>(links);
    }

    public static List<String> filterUsefulLinks(List<String> links, String rootUrl) throws Exception {
        String rootHost = URI.create(rootUrl).getHost();
        if (rootHost == null) {
            return new ArrayList<>();
        }

        rootHost = stripWww(rootHost);

        List<String> blockedPatterns0 = Arrays.asList(
                "/feed",
                "/wp-json",
                "/xmlrpc.php",
                "/wp-content/",
                "/wp-includes/",
                "/wp-admin/",
                "/plugins/",
                "/themes/",
                "/fonts/",
                "/uploads/",
                "oembed",
                "rsd",
                ".woff",
                ".woff2",
                ".ttf",
                ".otf"
        );

        List<String> usefulKeywords = Arrays.asList(
                "about",
                "mission",
                "vision",
                "strategy",
                "strategies",
                "investment",
                "investments",
                "investing",
                "portfolio",
                "companies",
                "team",
                "people",
                "leadership",
                "grants",
                "grant",
                "programs",
                "focus",
                "impact",
                "thesis",
                "approach",
                "what-we-do",
                "work",
                "sectors",
                "industries"
        );

        List<String> badExtensions = Arrays.asList(
                ".jpg", ".jpeg", ".png", ".gif", ".svg", ".webp",
                ".pdf", ".zip", ".mp4", ".mp3", ".css", ".js",
                ".woff", ".woff2", ".ttf", ".otf", ".ico"
        );

        LinkedHashMap<String, Integer> scoredLinks = new LinkedHashMap<>();

        for (String link : links) {
            URI uri;

            try {
                uri = URI.create(link);
            } catch (Exception e) {
                continue;
            }

            String host = uri.getHost();
            if (host == null) {
                continue;
            }

            if (!stripWww(host).equals(rootHost)) {
                continue;
            }

            String lower = link.toLowerCase();

            boolean blocked0 = false;

            for (String blockedPattern0 : blockedPatterns0)
            {
                if (lower.contains(blockedPattern0))
                {
                    blocked0 = true;
                    break;
                }
            }

            if (blocked0)
            {
                continue;
            }

            boolean badExtension = false;
            for (String ext : badExtensions) {
                if (lower.contains(ext)) {
                    badExtension = true;
                    break;
                }
            }

            if (badExtension) {
                continue;
            }

            if (lower.contains("/tag/")
                    || lower.contains("/category/")
                    || lower.contains("/author/")
                    || lower.contains("/feed/")
                    || lower.contains("?")
                    || lower.contains("privacy")
                    || lower.contains("terms")
                    || lower.contains("login")
                    || lower.contains("signup")) {
                continue;
            }

            int score = 0;

            for (String keyword : usefulKeywords) {
                if (lower.contains(keyword)) {
                    score += 10;
                }
            }

            int slashCount = countChar(uri.getPath(), '/');
            score -= slashCount;

            if (score > 0) {
                scoredLinks.put(link, score);
            }
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(scoredLinks.entrySet());

        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        List<String> result = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : entries) {
            result.add(entry.getKey());
        }

        return result;
    }

    public static String normalizeRootUrl(String input) {
        String url = input.trim();

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        return normalizeUrl(url);
    }

    public static String normalizeUrl(String input) {
        try {
            URI uri = URI.create(input.trim());

            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase();
            String host = uri.getHost();

            if (host == null) {
                return "";
            }

            host = host.toLowerCase();

            String path = uri.getPath();

            if (path == null || path.isBlank()) {
                path = "/";
            }

            path = URLDecoder.decode(path, StandardCharsets.UTF_8);

            while (path.contains("//")) {
                path = path.replace("//", "/");
            }

            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            return scheme + "://" + host + path;

        } catch (Exception e) {
            return "";
        }
    }

    public static String buildOutputJson(String rootUrl, LinkedHashMap<String, String> scrapedPages) {
        StringBuilder sb = new StringBuilder();

        sb.append("{\n");
        sb.append("  \"root_url\": \"").append(escapeJson(rootUrl)).append("\",\n");
        sb.append("  \"scraped_at\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"page_count\": ").append(scrapedPages.size()).append(",\n");
        sb.append("  \"pages\": [\n");

        int index = 0;

        for (Map.Entry<String, String> entry : scrapedPages.entrySet()) {
            String url = entry.getKey();
            String html = entry.getValue();

            sb.append("    {\n");
            sb.append("      \"url\": \"").append(escapeJson(url)).append("\",\n");
            sb.append("      \"html_char_count\": ").append(html.length()).append(",\n");
            sb.append("      \"html\": \"").append(escapeJson(html)).append("\"\n");
            sb.append("    }");

            index++;

            if (index < scrapedPages.size()) {
                sb.append(",");
            }

            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}");

        return sb.toString();
    }

    public static String stripWww(String host) {
        if (host.startsWith("www.")) {
            return host.substring(4);
        }
        return host;
    }

    public static int countChar(String text, char target) {
        if (text == null) {
            return 0;
        }

        int count = 0;

        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target) {
                count++;
            }
        }

        return count;
    }

    public static String escapeJson(String input) {
        if (input == null) {
            return "";
        }

        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public static String getDomain(String url0)
    {
        if (url0 == null || url0.trim().isEmpty())
        {
            return "";
        }
        try
        {
            String normalized0 = url0.trim();
            if (!normalized0.startsWith("http"))
            {
                normalized0 = "https://" + normalized0;
            }
            URI uri0 = URI.create(normalized0);
            String host0 = uri0.getHost();
            if (host0 == null)
            {
                return "";
            }
            return stripWww(host0.toLowerCase());
        }
        catch (Exception e0)
        {
            return "";
        }
    }

    public static boolean isSameDomain(String url0, String rootUrl0)
    {
        String d1 = getDomain(url0);
        String d2 = getDomain(rootUrl0);
        return !d1.isEmpty() && d1.equals(d2);
    }

    /**
     * Follows HTTP redirects for url0 and returns the effective domain after all redirects.
     * Used to detect cases like openphilanthropy.org → coefficientgiving.org.
     * Returns the original domain on any error or timeout.
     */
    public static String resolveEffectiveDomain(String url0)
    {
        String originalDomain0 = getDomain(url0);
        if (url0 == null || url0.trim().isEmpty())
        {
            return originalDomain0;
        }
        try
        {
            String normalized0 = url0.trim();
            if (!normalized0.startsWith("http"))
            {
                normalized0 = "https://" + normalized0;
            }
            HttpClient redirectClient0 = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
            HttpRequest req0 = HttpRequest.newBuilder()
                .uri(URI.create(normalized0))
                .timeout(Duration.ofSeconds(10))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<Void> resp0 = redirectClient0.send(req0, HttpResponse.BodyHandlers.discarding());
            String effectiveDomain0 = getDomain(resp0.uri().toString());
            return effectiveDomain0.isEmpty() ? originalDomain0 : effectiveDomain0;
        }
        catch (Exception e0)
        {
            return originalDomain0;
        }
    }

    public static String scrapeVisibleText(String url0) throws Exception
    {
        String html0 = scrapeUrl(url0);
        return extractVisibleText(html0);
    }

    public static String buildReadableTextFromCrawl(LinkedHashMap<String, String> pages0)
    {
        return buildReadableTextFromCrawl(pages0, 60000);
    }

    public static String buildReadableTextFromCrawl(LinkedHashMap<String, String> pages0, int maxChars0)
    {
        if (pages0 == null || pages0.isEmpty())
        {
            return "";
        }

        StringBuilder builder0 = new StringBuilder();
        int pageCount0 = 0;

        for (String pageUrl0 : pages0.keySet())
        {
            String html0 = pages0.get(pageUrl0);
            String cleanText0 = extractVisibleText(html0);

            if (cleanText0 == null || cleanText0.trim().isEmpty())
            {
                continue;
            }

            builder0.append("\n\n===== SOURCE PAGE ")
                .append(pageCount0 + 1)
                .append(" =====\n");
            builder0.append("URL: ").append(pageUrl0).append("\n\n");
            builder0.append(cleanText0);

            pageCount0++;

            if (builder0.length() >= maxChars0)
            {
                break;
            }
        }

        String result0 = builder0.toString();
        if (result0.length() > maxChars0)
        {
            result0 = result0.substring(0, maxChars0);
        }
        return result0;
    }

    public static ArrayList<String> findLikelyContactBioPages(
        String rootUrl0,
        String firstName0,
        String lastName0,
        BrightDataSerpClient serpClient0)
    {
        ArrayList<String> pages0 = new ArrayList<>();

        if (rootUrl0 == null || rootUrl0.trim().isEmpty() || serpClient0 == null)
        {
            return pages0;
        }

        String domain0 = getDomain(rootUrl0);
        if (domain0.isEmpty())
        {
            return pages0;
        }

        StringBuilder query0 = new StringBuilder("site:" + domain0);
        if (firstName0 != null && !firstName0.trim().isEmpty())
        {
            query0.append(" \"").append(firstName0.trim().replace("\"", "")).append("\"");
        }
        if (lastName0 != null && !lastName0.trim().isEmpty())
        {
            query0.append(" \"").append(lastName0.trim().replace("\"", "")).append("\"");
        }

        try
        {
            ArrayList<SerpResult> results0 = serpClient0.search(query0.toString(), 5);
            for (SerpResult sr0 : results0)
            {
                if (sr0 != null && sr0.url != null && isSameDomain(sr0.url, rootUrl0))
                {
                    pages0.add(sr0.url);
                }
            }
        }
        catch (Exception e0)
        {
            // ignore
        }

        return pages0;
    }

    public static String extractVisibleText(String html0)
    {
        if (html0 == null)
        {
            return "";
        }

        String text0 = html0;

        text0 = text0.replaceAll("(?is)<script.*?>.*?</script>", " ");
        text0 = text0.replaceAll("(?is)<style.*?>.*?</style>", " ");
        text0 = text0.replaceAll("(?is)<noscript.*?>.*?</noscript>", " ");
        text0 = text0.replaceAll("(?is)<!--.*?-->", " ");

        text0 = text0.replaceAll("(?is)<br\\s*/?>", "\n");
        text0 = text0.replaceAll("(?is)</p>", "\n");
        text0 = text0.replaceAll("(?is)</div>", "\n");
        text0 = text0.replaceAll("(?is)</h[1-6]>", "\n");

        text0 = text0.replaceAll("(?is)<[^>]+>", " ");

        text0 = text0.replace("&amp;", "&");
        text0 = text0.replace("&nbsp;", " ");
        text0 = text0.replace("&quot;", "\"");
        text0 = text0.replace("&#039;", "'");
        text0 = text0.replace("&apos;", "'");
        text0 = text0.replace("&lt;", "<");
        text0 = text0.replace("&gt;", ">");

        text0 = text0.replaceAll("[ \\t]+", " ");
        text0 = text0.replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n");

        return text0.trim();
    }
}