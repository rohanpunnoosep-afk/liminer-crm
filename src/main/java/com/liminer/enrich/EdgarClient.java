package com.liminer.enrich;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/*
 * EdgarClient — thin client for SEC EDGAR full-text search (efts.sec.gov) and
 * filing submissions (data.sec.gov). Mirrors BrightDataSerpClient style: static
 * shared HttpClient, stateless methods, 20s timeout, one retry on transient errors.
 *
 * Required User-Agent per SEC fair-access policy. No API key required.
 * ADV Part 1 data is NOT in EDGAR; only Form D and 13F are implemented here.
 */
public class EdgarClient
{
    private static final HttpClient CLIENT0    = HttpClient.newHttpClient();
    private static final String USER_AGENT0 = HttpContact.USER_AGENT0;
    private static final int    TIMEOUT_SECS0  = 20;
    private static final String EFTS0          = "https://efts.sec.gov/LATEST/search-index";
    private static final String DATA0          = "https://data.sec.gov/submissions";
    private static final String ARCHIVES0      = "https://www.sec.gov/Archives/edgar/data";

    public EdgarClient() {}

    // One full-text search hit.
    public static class SearchHit
    {
        public String title      = "";
        public String cik        = "";
        public String formType   = "";
        public String filingDate = "";
        public String url        = "";
    }

    // Latest Form D (private-offering) summary for an issuer.
    public static class FormDResult
    {
        public String filingDate          = "";   // 15-day filing → timely asOfDate
        public String firstSaleDate       = "";
        public String totalAmountSold     = "";
        public String totalOfferingAmount = "";
        public String url                 = "";
    }

    // Latest 13F (public-equity holdings) summary for a manager.
    public static class ThirteenFResult
    {
        public String filingDate     = "";
        public String periodOfReport = "";
        public String totalValue     = "";
        public String numHoldings    = "";
        public String url            = "";
    }

    // Submissions index for a CIK (filing history metadata).
    public static class SubmissionsResult
    {
        public String cik          = "";
        public String entityName   = "";
        public ArrayList<SearchHit> recentFilings = new ArrayList<SearchHit>();
    }

    // One row of an EDGAR daily form index (master.idx), filtered to form D/D-A.
    public static class DailyIndexEntry
    {
        public String cik         = "";
        public String companyName = "";
        public String formType    = "";
        public String dateFiled   = "";
        public String filename    = "";
    }

    // One Form D Related-Person entry: {name, relationship(s)}.
    public static class RelatedPerson
    {
        public String name         = "";
        public String relationship = "";
    }

    // -----------------------------------------------------------------------
    // Public methods
    // -----------------------------------------------------------------------

    // EDGAR full-text search. Returns up to 20 hits; never null.
    public ArrayList<SearchHit> fullTextSearch(String query0)
    {
        ArrayList<SearchHit> results0 = new ArrayList<SearchHit>();
        if (isBlank(query0)) return results0;
        try
        {
            String enc0  = URLEncoder.encode(query0.trim(), StandardCharsets.UTF_8);
            String url0  = EFTS0 + "?q=" + enc0
                + "&dateRange=custom&startdt=2015-01-01&enddt=2030-12-31";
            String body0 = get(url0);
            if (isBlank(body0)) return results0;

            JSONObject root0  = new JSONObject(body0);
            JSONObject outer0 = root0.optJSONObject("hits");
            if (outer0 == null) return results0;
            JSONArray hits0 = outer0.optJSONArray("hits");
            if (hits0 == null) return results0;

            for (int i0 = 0; i0 < hits0.length() && results0.size() < 20; i0++)
            {
                JSONObject h0 = hits0.optJSONObject(i0);
                if (h0 == null) continue;
                JSONObject src0 = h0.optJSONObject("_source");
                if (src0 == null) continue;

                SearchHit sh0 = new SearchHit();

                // display_names[0] format: "ENTITY NAME  (CIK 0001234567)"
                JSONArray dn0 = src0.optJSONArray("display_names");
                if (dn0 != null && dn0.length() > 0)
                {
                    sh0.title = nameFromDisplayName(dn0.optString(0, ""));
                    sh0.cik   = cikFromDisplayName(dn0.optString(0, ""));
                }
                if (isBlank(sh0.cik))
                {
                    JSONArray ciks0 = src0.optJSONArray("ciks");
                    if (ciks0 != null && ciks0.length() > 0)
                        sh0.cik = stripZeros(ciks0.optString(0, ""));
                }

                sh0.formType   = src0.optString("form", "");
                sh0.filingDate = src0.optString("file_date", "");

                String adsh0 = src0.optString("adsh", "");
                if (!isBlank(sh0.cik) && !isBlank(adsh0))
                    sh0.url = filingUrl(sh0.cik, adsh0, "");

                results0.add(sh0);
            }
        }
        catch (Exception e0)
        {
            System.err.println("[EDGAR] fullTextSearch \"" + query0 + "\": " + e0.getMessage());
        }
        return results0;
    }

    // Submissions index for a CIK. Empty result when cik is blank or HTTP fails.
    public SubmissionsResult fetchSubmissions(String cik0)
    {
        SubmissionsResult r0 = new SubmissionsResult();
        if (isBlank(cik0)) return r0;
        try
        {
            String padded0 = padCik(cik0);
            String body0   = get(DATA0 + "/CIK" + padded0 + ".json");
            if (isBlank(body0)) return r0;

            JSONObject root0 = new JSONObject(body0);
            r0.cik        = stripZeros(root0.optString("cik", cik0));
            r0.entityName = root0.optString("entityName", "");

            JSONObject recent0 = root0.optJSONObject("recent");
            if (recent0 == null) return r0;

            JSONArray forms0 = recent0.optJSONArray("form");
            JSONArray dates0 = recent0.optJSONArray("filingDate");
            JSONArray accs0  = recent0.optJSONArray("accessionNumber");
            JSONArray docs0  = recent0.optJSONArray("primaryDocument");
            if (forms0 == null) return r0;

            for (int i0 = 0; i0 < forms0.length(); i0++)
            {
                SearchHit sh0    = new SearchHit();
                sh0.formType     = forms0.optString(i0, "");
                sh0.filingDate   = dates0 != null ? dates0.optString(i0, "") : "";
                sh0.cik          = r0.cik;
                sh0.title        = r0.entityName;
                String acc0      = accs0 != null ? accs0.optString(i0, "") : "";
                String doc0      = docs0 != null ? docs0.optString(i0, "") : "";
                if (!isBlank(r0.cik) && !isBlank(acc0))
                    sh0.url = filingUrl(r0.cik, acc0, doc0);
                r0.recentFilings.add(sh0);
            }
        }
        catch (Exception e0)
        {
            System.err.println("[EDGAR] fetchSubmissions cik=" + cik0 + ": " + e0.getMessage());
        }
        return r0;
    }

    // Latest Form D for an issuer CIK. Empty result (never null) on failure.
    public FormDResult latestFormD(String cik0)
    {
        FormDResult r0 = new FormDResult();
        if (isBlank(cik0)) return r0;
        try
        {
            SubmissionsResult subs0 = fetchSubmissions(cik0);
            for (SearchHit sh0 : subs0.recentFilings)
            {
                if ("D".equals(sh0.formType) || "D/A".equals(sh0.formType))
                {
                    r0.filingDate = sh0.filingDate;
                    r0.url        = sh0.url;
                    if (!isBlank(sh0.url))
                    {
                        String xml0 = get(sh0.url);
                        if (!isBlank(xml0)) parseFormDXml(xml0, r0);
                    }
                    break; // submissions are newest-first
                }
            }
        }
        catch (Exception e0)
        {
            System.err.println("[EDGAR] latestFormD cik=" + cik0 + ": " + e0.getMessage());
        }
        return r0;
    }

    // Latest 13F-HR for a manager CIK. Empty result (never null) on failure.
    public ThirteenFResult latest13F(String cik0)
    {
        ThirteenFResult r0 = new ThirteenFResult();
        if (isBlank(cik0)) return r0;
        try
        {
            SubmissionsResult subs0 = fetchSubmissions(cik0);
            for (SearchHit sh0 : subs0.recentFilings)
            {
                if ("13F-HR".equals(sh0.formType))
                {
                    r0.filingDate = sh0.filingDate;
                    r0.url        = sh0.url;
                    // period of report = the quarter being reported on (typically
                    // one quarter before the filing date)
                    r0.periodOfReport = quarterBefore(sh0.filingDate);
                    break;
                }
            }
        }
        catch (Exception e0)
        {
            System.err.println("[EDGAR] latest13F cik=" + cik0 + ": " + e0.getMessage());
        }
        return r0;
    }

    // Fetch one EDGAR daily form index (master.idx for the date) and return
    // entries filtered to form types D and D/A. Empty list (never null) on
    // failure. Pure parsing lives in parseDailyIndex so it's testable offline.
    public List<DailyIndexEntry> fetchDailyIndex(LocalDate date0)
    {
        if (date0 == null) return new ArrayList<DailyIndexEntry>();
        try
        {
            int year0     = date0.getYear();
            int quarter0  = ((date0.getMonthValue() - 1) / 3) + 1;
            String yyyymmdd0 = date0.format(DateTimeFormatter.BASIC_ISO_DATE);
            String url0   = "https://www.sec.gov/Archives/edgar/daily-index/" + year0
                + "/QTR" + quarter0 + "/master." + yyyymmdd0 + ".idx";
            return parseDailyIndex(get(url0));
        }
        catch (Exception e0)
        {
            System.err.println("[EDGAR] fetchDailyIndex " + date0 + ": " + e0.getMessage());
            return new ArrayList<DailyIndexEntry>();
        }
    }

    // Pure parser for an EDGAR daily master.idx: pipe-delimited rows
    // "CIK|Company Name|Form Type|Date Filed|Filename" after a "----" header
    // separator line. Returns only Form D / D-A rows; never null.
    public List<DailyIndexEntry> parseDailyIndex(String content0)
    {
        List<DailyIndexEntry> out0 = new ArrayList<DailyIndexEntry>();
        if (isBlank(content0)) return out0;

        String[] lines0 = content0.split("\r?\n");
        boolean started0 = false;
        for (String line0 : lines0)
        {
            if (!started0)
            {
                if (line0.trim().startsWith("---")) started0 = true;
                continue;
            }
            if (isBlank(line0)) continue;

            String[] parts0 = line0.split("\\|");
            if (parts0.length < 5) continue;

            String formType0 = parts0[2].trim();
            if (!"D".equals(formType0) && !"D/A".equals(formType0)) continue;

            DailyIndexEntry e0 = new DailyIndexEntry();
            e0.cik         = parts0[0].trim();
            e0.companyName = parts0[1].trim();
            e0.formType    = formType0;
            e0.dateFiled   = parts0[3].trim();
            e0.filename    = parts0[4].trim();
            out0.add(e0);
        }
        return out0;
    }

    // Pure parser for the Related Persons section of a Form D XML submission.
    // Form D is filed by the FUND entity, not the adviser; a caller joins the
    // returned {name, relationship} pairs to the ADV universe by normalized
    // name (+ state when available). Unmatched issuers are the caller's
    // problem. Never null.
    public List<RelatedPerson> parseFormDRelatedPersons(String xml0)
    {
        List<RelatedPerson> out0 = new ArrayList<RelatedPerson>();
        if (isBlank(xml0)) return out0;
        try
        {
            DocumentBuilderFactory fac0 = DocumentBuilderFactory.newInstance();
            fac0.setFeature("http://xml.org/sax/features/external-general-entities",   false);
            fac0.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            fac0.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            fac0.setExpandEntityReferences(false);

            DocumentBuilder db0 = fac0.newDocumentBuilder();
            db0.setErrorHandler(null);
            Document doc0 = db0.parse(
                new ByteArrayInputStream(xml0.getBytes(StandardCharsets.UTF_8)));

            NodeList personNodes0 = doc0.getElementsByTagName("relatedPersonInfo");
            for (int i0 = 0; i0 < personNodes0.getLength(); i0++)
            {
                org.w3c.dom.Node node0 = personNodes0.item(i0);
                if (!(node0 instanceof Element)) continue;
                Element personEl0 = (Element) node0;

                String first0 = firstChildText(personEl0, "firstName");
                String last0  = firstChildText(personEl0, "lastName");
                String name0  = (first0 + " " + last0).trim();
                if (name0.isEmpty()) continue;

                NodeList relEls0 = personEl0.getElementsByTagName("relationship");
                StringBuilder rel0 = new StringBuilder();
                for (int j0 = 0; j0 < relEls0.getLength(); j0++)
                {
                    String r0 = relEls0.item(j0).getTextContent().trim();
                    if (r0.isEmpty()) continue;
                    if (rel0.length() > 0) rel0.append(", ");
                    rel0.append(r0);
                }

                RelatedPerson rp0 = new RelatedPerson();
                rp0.name = name0;
                rp0.relationship = rel0.toString();
                out0.add(rp0);
            }
        }
        catch (Exception e0)
        {
            System.err.println("[EDGAR] parseFormDRelatedPersons: " + e0.getMessage());
        }
        return out0;
    }

    private static String firstChildText(Element parent0, String tag0)
    {
        NodeList nl0 = parent0.getElementsByTagName(tag0);
        if (nl0.getLength() == 0) return "";
        return nl0.item(0).getTextContent().trim();
    }

    // -----------------------------------------------------------------------
    // Form D XML parsing
    // -----------------------------------------------------------------------

    private void parseFormDXml(String xml0, FormDResult r0)
    {
        // Primary parser: DocumentBuilder.
        try
        {
            DocumentBuilderFactory fac0 = DocumentBuilderFactory.newInstance();
            // Disable external entity loading (XXE safety).
            fac0.setFeature("http://xml.org/sax/features/external-general-entities",   false);
            fac0.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            fac0.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            fac0.setExpandEntityReferences(false);

            DocumentBuilder db0  = fac0.newDocumentBuilder();
            db0.setErrorHandler(null); // suppress SAX logging
            Document doc0 = db0.parse(
                new ByteArrayInputStream(xml0.getBytes(StandardCharsets.UTF_8)));

            // dateOfFirstSale may be <dateOfFirstSale><value>DATE</value></dateOfFirstSale>
            // or <dateOfFirstSale>DATE</dateOfFirstSale>.
            r0.firstSaleDate       = xmlText(doc0, "dateOfFirstSale");
            r0.totalAmountSold     = xmlText(doc0, "totalAmountSold");
            r0.totalOfferingAmount = xmlText(doc0, "totalOfferingAmount");
        }
        catch (Exception e0)
        {
            // Fallback: regex extraction.
            r0.firstSaleDate       = regexTag(xml0, "dateOfFirstSale");
            r0.totalAmountSold     = regexTag(xml0, "totalAmountSold");
            r0.totalOfferingAmount = regexTag(xml0, "totalOfferingAmount");
        }

        // "Indefinite" is a valid string value in Form D but meaningless as a number.
        if ("Indefinite".equalsIgnoreCase(r0.totalAmountSold))     r0.totalAmountSold = "";
        if ("Indefinite".equalsIgnoreCase(r0.totalOfferingAmount)) r0.totalOfferingAmount = "";
    }

    // Gets text content of a named element, looking for a <value> child first.
    private static String xmlText(Document doc0, String tag0)
    {
        NodeList nl0 = doc0.getElementsByTagName(tag0);
        if (nl0.getLength() == 0) return "";
        org.w3c.dom.Node node0 = nl0.item(0);
        // Look for a <value> child element.
        if (node0 instanceof Element)
        {
            NodeList val0 = ((Element) node0).getElementsByTagName("value");
            if (val0.getLength() > 0)
            {
                String t0 = val0.item(0).getTextContent().trim();
                if (!t0.isEmpty()) return t0;
            }
        }
        return node0.getTextContent().trim();
    }

    // Regex fallback for when XML parsing fails (e.g., malformed encoding).
    private static String regexTag(String xml0, String tag0)
    {
        Pattern p0 = Pattern.compile(
            "<" + tag0 + "[^>]*>(?:<value>)?([^<]{1,200})(?:</value>)?</" + tag0 + ">",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m0 = p0.matcher(xml0);
        return m0.find() ? m0.group(1).trim() : "";
    }

    // -----------------------------------------------------------------------
    // URL and CIK helpers
    // -----------------------------------------------------------------------

    private String filingUrl(String cik0, String accNum0, String primaryDoc0)
    {
        String cikInt0   = stripZeros(cik0);
        String accNoDash = accNum0.replace("-", "");
        if (isBlank(primaryDoc0))
            return ARCHIVES0 + "/" + cikInt0 + "/" + accNoDash + "/";
        return ARCHIVES0 + "/" + cikInt0 + "/" + accNoDash + "/" + primaryDoc0;
    }

    private static String padCik(String cik0)
    {
        String s0 = cik0.trim().replaceAll("[^0-9]", "");
        while (s0.length() < 10) s0 = "0" + s0;
        return s0;
    }

    private static String stripZeros(String s0)
    {
        if (isBlank(s0)) return s0;
        String r0 = s0.trim().replaceFirst("^0+", "");
        return r0.isEmpty() ? "0" : r0;
    }

    // Parses "ENTITY NAME  (CIK 0001234567)" → entity name.
    private static String nameFromDisplayName(String dn0)
    {
        if (isBlank(dn0)) return "";
        int idx0 = dn0.lastIndexOf("  (CIK");
        if (idx0 > 0) return dn0.substring(0, idx0).trim();
        idx0 = dn0.lastIndexOf("(CIK");
        if (idx0 > 0) return dn0.substring(0, idx0).trim();
        return dn0.trim();
    }

    // Parses "ENTITY NAME  (CIK 0001234567)" → CIK without leading zeros.
    private static String cikFromDisplayName(String dn0)
    {
        if (isBlank(dn0)) return "";
        Matcher m0 = Pattern.compile("\\(CIK\\s+(\\d+)\\)", Pattern.CASE_INSENSITIVE).matcher(dn0);
        return m0.find() ? stripZeros(m0.group(1)) : "";
    }

    // Rough period-of-report: one quarter before the filing date string (YYYY-MM-DD).
    private static String quarterBefore(String date0)
    {
        if (isBlank(date0) || date0.length() < 10) return "";
        try
        {
            java.time.LocalDate d0 = java.time.LocalDate.parse(date0.substring(0, 10));
            return d0.minusMonths(3).toString();
        }
        catch (Exception ignored0) { return ""; }
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

    private String get(String url0) throws Exception
    {
        HttpRequest req0 = HttpRequest.newBuilder()
            .uri(URI.create(url0))
            .timeout(Duration.ofSeconds(TIMEOUT_SECS0))
            .header("User-Agent", USER_AGENT0)
            .GET()
            .build();

        Exception last0 = null;
        for (int attempt0 = 1; attempt0 <= 2; attempt0++)
        {
            try
            {
                HttpResponse<String> resp0 = CLIENT0.send(req0, HttpResponse.BodyHandlers.ofString());
                int status0 = resp0.statusCode();
                if (status0 == 502 || status0 == 503 || status0 == 429)
                {
                    last0 = new RuntimeException("transient HTTP " + status0);
                    Thread.sleep(400L * attempt0);
                    continue;
                }
                if (status0 == 404) return "";
                if (status0 < 200 || status0 >= 300)
                    throw new RuntimeException("HTTP " + status0 + " for " + url0);
                return resp0.body();
            }
            catch (java.net.http.HttpTimeoutException te0) { last0 = te0; }
        }
        if (last0 != null) throw last0;
        return "";
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
