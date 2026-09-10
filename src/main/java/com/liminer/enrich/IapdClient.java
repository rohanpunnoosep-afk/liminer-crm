package com.liminer.enrich;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * IapdClient — client for the SEC IAPD firm data at adviserinfo.sec.gov.
 *
 * Two live capabilities:
 *
 *   lookupFirm   firm search API (api.adviserinfo.sec.gov/search/firm). Returns a
 *                FirmMatch carrying the CRD *and* the corroborating detail
 *                (firm name, other names, city/state/country, whether the LP's own
 *                domain appeared in the record) so IdentityResolver can verify the
 *                match instead of trusting a bare name hit. The CRD is in the field
 *                firm_source_id (not firm_id).
 *
 *   fetchPart1   real Form ADV Part 1 Item 5 figures, parsed from the filed ADV PDF
 *                at reports.adviserinfo.sec.gov/reports/ADV/{crd}/PDF/{crd}.pdf.
 *                This replaces the long-standing empty stub, which silently zeroed
 *                the RESOURCES axis for EVERY registered adviser in the CRM: the
 *                RaumIndicator asked for RAUM, got a blank Part1Result, returned
 *                empty, and the rollup scored 0.
 *
 * Why the PDF and not an API: the IAPD Part 1 structured endpoints are either
 * Forbidden (api.adviserinfo.sec.gov/firm/{crd}) or behind the SPA, and the one
 * JSON blob that IS reachable (search/firm/{crd}?json=true) carries registration
 * metadata only — no Item 5 figures. The filed PDF is public, unauthenticated,
 * ~1 MB, and is the authoritative document itself. Text is extracted with the
 * OpenPDF already on the classpath (used by InvestorBriefPdfRenderer).
 *
 * Anti-garbage discipline in the parser: the extractor emits each Item 5.F line
 * more than once (form layers), so every figure is collected across ALL matches
 * and accepted only when every occurrence agrees. A disagreeing set means the
 * layout was misread and the field is dropped rather than guessed. The CRD
 * printed in the PDF header is also checked against the CRD we asked for, so a
 * redirect or a stale cache can never attribute one firm's balance sheet to
 * another.
 *
 * Mirrors BrightDataSerpClient style: static shared HttpClient, stateless
 * methods, 20s timeout, one retry on transient errors.
 */
public class IapdClient
{
    // Jaccard floor for accepting two firm names as the same entity without a
    // domain anchor. Raising it makes identity resolution stricter; lowering it
    // reopens the subset-name collisions this threshold exists to stop.
    private static final double NAME_MATCH_THRESHOLD = 0.75;

    private static final HttpClient CLIENT0    = HttpClient.newHttpClient();
    private static final String USER_AGENT0 = HttpContact.USER_AGENT0;
    private static final int    TIMEOUT_SECS0  = 20;
    // The filed ADV PDF is ~1 MB, so it gets a longer budget than the search API.
    private static final int    PDF_TIMEOUT_SECS0 = 45;
    private static final String ADV_PDF_URL0   =
        "https://reports.adviserinfo.sec.gov/reports/ADV/";
    private static final String SEARCH_URL0    =
        "https://api.adviserinfo.sec.gov/search/firm?query=%s" +
        "&hl=true&nrows=12&start=0&r=25&noDataBoost=false" +
        "&reqnorecompile=true&ef=true&sortby=score&sortorder=desc";

    public IapdClient() {}

    // Structured Form ADV Part 1 fields (Item 5), parsed from the filed ADV PDF.
    // Blank string means "not found in this filing", never "zero".
    public static class Part1Result
    {
        public String raum                 = "";   // Item 5.F(2)(c) total regulatory AUM
        public String discretionaryRaum    = "";   // Item 5.F(2)(a)
        public String nonDiscretionaryRaum = "";   // Item 5.F(2)(b)
        public String numAccounts          = "";   // Item 5.F(2)(f)
        public String numEmployees         = "";
        public String fiscalYearEnd        = "";
        public String filingDate           = "";   // ISO-8601, from the PDF header
        public String firmName             = "";   // header "Primary Business Name"
    }

    // A CRD candidate plus the evidence needed to corroborate it. Returned by
    // lookupFirm so identity resolution can require a real anchor.
    public static class FirmMatch
    {
        public String crd          = "";
        public String firmName     = "";
        public String city         = "";
        public String state        = "";
        public String country      = "";
        public boolean domainMatch = false;   // LP's own domain appeared in the record
        public boolean nameMatch   = false;   // strong normalized name overlap
    }

    /**
     * Resolve an adviser name (+website hint) to a CRD, WITH the evidence that
     * justifies it. Returns null when no active registration corroborates the name.
     *
     * The previous version ended with a bare `else if (bestCrd is blank) bestCrd = crd`
     * fallthrough, so it returned the FIRST active firm the search engine happened to
     * rank first even when nothing about it matched — that is how a UK advisory firm
     * acquired a Maine RIA's CRD. There is deliberately no such fallthrough now: an
     * uncorroborated hit is not a match, and this method returns null instead.
     */
    public FirmMatch lookupFirm(String name0, String website0)
    {
        if (isBlank(name0)) return null;
        try
        {
            String enc0  = URLEncoder.encode(name0.trim(), StandardCharsets.UTF_8);
            String url0  = String.format(SEARCH_URL0, enc0);
            String body0 = get(url0);
            if (isBlank(body0)) return null;

            JSONObject root0 = new JSONObject(body0);
            JSONObject hits0 = root0.optJSONObject("hits");
            if (hits0 == null) return null;
            JSONArray list0 = hits0.optJSONArray("hits");
            if (list0 == null || list0.length() == 0) return null;

            String normName0 = normName(name0);
            String normDom0  = isBlank(website0) ? "" : domain(website0);

            FirmMatch best0 = null;

            for (int i0 = 0; i0 < list0.length(); i0++)
            {
                JSONObject hit0 = list0.optJSONObject(i0);
                if (hit0 == null) continue;
                JSONObject src0 = hit0.optJSONObject("_source");
                if (src0 == null) continue;

                // Only consider active IA registrations.
                String scope0 = src0.optString("firm_ia_scope", "");
                if (!"ACTIVE".equalsIgnoreCase(scope0)) continue;

                String crd0   = src0.optString("firm_source_id", "").trim();
                if (isBlank(crd0)) continue;
                String fName0 = src0.optString("firm_name", "");
                String addrRaw0 = src0.optString("firm_ia_address_details", "");

                boolean hasDomMatch0 = !isBlank(normDom0)
                    && !isBlank(addrRaw0) && addrRaw0.toLowerCase().contains(normDom0);

                // Match against the legal name AND every "other name" the firm files
                // under — a firm often appears in a CRM under its d/b/a.
                boolean hasNameMatch0 = nameStrong(normName0, normName(fName0));
                JSONArray others0 = src0.optJSONArray("firm_other_names");
                if (!hasNameMatch0 && others0 != null)
                {
                    for (int j0 = 0; j0 < others0.length(); j0++)
                    {
                        if (nameStrong(normName0, normName(others0.optString(j0, ""))))
                        {
                            hasNameMatch0 = true;
                            break;
                        }
                    }
                }

                // Nothing linked this record to the LP at all — skip it entirely.
                if (!hasDomMatch0 && !hasNameMatch0) continue;

                FirmMatch match0 = new FirmMatch();
                match0.crd = crd0;
                match0.firmName = fName0;
                match0.domainMatch = hasDomMatch0;
                match0.nameMatch = hasNameMatch0;
                applyAddress(match0, addrRaw0);

                // Domain + name is the strongest possible evidence — take it and stop.
                if (hasDomMatch0 && hasNameMatch0) return match0;
                if (best0 == null || (hasDomMatch0 && !best0.domainMatch)) best0 = match0;
            }

            return best0;
        }
        catch (Exception e0)
        {
            System.err.println("[IAPD] lookupFirm \"" + name0 + "\": " + e0.getMessage());
        }
        return null;
    }

    /**
     * Back-compatible thin wrapper over lookupFirm for callers that only want the
     * number. Prefer lookupFirm — a bare CRD carries none of the evidence a caller
     * needs to decide whether the match is real.
     */
    public String lookupCrdByName(String name0, String website0)
    {
        FirmMatch match0 = lookupFirm(name0, website0);
        return match0 == null ? "" : match0.crd;
    }

    // firm_ia_address_details is a JSON *string* holding {"officeAddress": {...}}.
    private static void applyAddress(FirmMatch match0, String addrRaw0)
    {
        if (isBlank(addrRaw0)) return;
        try
        {
            JSONObject office0 = new JSONObject(addrRaw0).optJSONObject("officeAddress");
            if (office0 == null) return;
            match0.city    = office0.optString("city", "");
            match0.state   = office0.optString("state", "");
            match0.country = office0.optString("country", "");
        }
        catch (Exception e0) { /* address is corroboration only — never fatal */ }
    }

    // -----------------------------------------------------------------------
    // Form ADV Part 1 Item 5 (real, from the filed PDF)
    // -----------------------------------------------------------------------

    /**
     * Downloads the firm's filed Form ADV PDF and parses Item 5 figures out of it.
     * Returns an all-blank Part1Result (never null) when the filing is unavailable
     * or unreadable — RaumIndicator treats blank RAUM as "no result" and returns
     * IndicatorResult.empty(), which the rollup renders as an unscored axis rather
     * than a zero.
     */
    public Part1Result fetchPart1(String crd0)
    {
        Part1Result out0 = new Part1Result();
        if (isBlank(crd0)) return out0;

        String clean0 = crd0.trim().replaceAll("[^0-9]", "");
        if (clean0.isEmpty()) return out0;

        try
        {
            String text0 = fetchAdvPdfText(clean0);
            if (isBlank(text0)) return out0;

            // Guard: the PDF must be the firm we asked for. A redirect, a cached
            // response or a recycled CRD would otherwise hand one firm's balance
            // sheet to another row.
            String pdfCrd0 = uniqueMatch(text0, "CRD Number:\\s*(\\d{3,})", 1);
            if (!isBlank(pdfCrd0) && !pdfCrd0.equals(clean0))
            {
                System.err.println("[IAPD] ADV PDF for CRD " + clean0
                    + " reports CRD " + pdfCrd0 + " — discarding.");
                return out0;
            }

            out0.firmName = uniqueMatch(text0, "Primary Business Name:\\s*(.+?)CRD Number", 1);
            out0.filingDate = toIsoDate(
                uniqueMatch(text0, "(\\d{1,2}/\\d{1,2}/\\d{4})\\s+\\d{1,2}:\\d{2}:\\d{2}", 1));

            // Item 5.F(2): (a) discretionary / (b) non-discretionary / (c) total,
            // each followed by its account count in (d) / (e) / (f).
            out0.discretionaryRaum    = money(uniqueMatch(text0,
                "Discretionary:\\s*\\(a\\)\\s*\\$\\s*([0-9,]+)", 1));
            out0.nonDiscretionaryRaum = money(uniqueMatch(text0,
                "Non-Discretionary:\\s*\\(b\\)\\s*\\$\\s*([0-9,]+)", 1));
            out0.raum                 = money(uniqueMatch(text0,
                "Total:\\s*\\(c\\)\\s*\\$\\s*([0-9,]+)", 1));
            out0.numAccounts          = uniqueMatch(text0,
                "Total:\\s*\\(c\\)\\s*\\$\\s*[0-9,]+\\s*\\(f\\)\\s*([0-9,]+)", 1);

            // Some filings report only the split and leave the total line unread;
            // reconstruct it rather than losing the row's whole RESOURCES signal.
            if (isBlank(out0.raum))
            {
                double sum0 = parseAmount(out0.discretionaryRaum)
                            + parseAmount(out0.nonDiscretionaryRaum);
                if (sum0 > 0.0) out0.raum = "$" + String.format("%,.0f", sum0);
            }

            return out0;
        }
        catch (Exception e0)
        {
            System.err.println("[IAPD] fetchPart1 CRD " + clean0 + ": " + e0.getMessage());
            return out0;
        }
    }

    /** Downloads the filed ADV PDF to a temp file and extracts its text. */
    private String fetchAdvPdfText(String crd0) throws Exception
    {
        String url0 = ADV_PDF_URL0 + crd0 + "/PDF/" + crd0 + ".pdf";
        java.nio.file.Path tmp0 = java.nio.file.Files.createTempFile("adv_" + crd0 + "_", ".pdf");
        try
        {
            HttpRequest req0 = HttpRequest.newBuilder()
                .uri(URI.create(url0))
                .timeout(Duration.ofSeconds(PDF_TIMEOUT_SECS0))
                .header("User-Agent", USER_AGENT0)
                .GET()
                .build();

            HttpResponse<java.nio.file.Path> resp0 = CLIENT0.send(
                req0, HttpResponse.BodyHandlers.ofFile(
                    tmp0, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE));

            if (resp0.statusCode() != 200) return "";

            com.lowagie.text.pdf.PdfReader reader0 =
                new com.lowagie.text.pdf.PdfReader(tmp0.toString());
            try
            {
                com.lowagie.text.pdf.parser.PdfTextExtractor extractor0 =
                    new com.lowagie.text.pdf.parser.PdfTextExtractor(reader0);
                StringBuilder sb0 = new StringBuilder();
                for (int page0 = 1; page0 <= reader0.getNumberOfPages(); page0++)
                {
                    sb0.append(extractor0.getTextFromPage(page0)).append("\n");
                }
                return sb0.toString();
            }
            finally { reader0.close(); }
        }
        finally { java.nio.file.Files.deleteIfExists(tmp0); }
    }

    /**
     * Returns the captured group ONLY when every occurrence of the pattern in the
     * document agrees. The PDF text layer repeats each Item 5 line (form layers), so
     * agreement across all ~13 occurrences is free corroboration that the layout was
     * read correctly; disagreement means it was not, and returns "" so the caller
     * drops the field instead of picking one at random.
     */
    private static String uniqueMatch(String text0, String regex0, int group0)
    {
        java.util.LinkedHashSet<String> seen0 = new java.util.LinkedHashSet<String>();
        java.util.regex.Matcher m0 = java.util.regex.Pattern
            .compile(regex0, java.util.regex.Pattern.DOTALL).matcher(text0);
        while (m0.find())
        {
            String v0 = m0.group(group0);
            if (v0 != null) seen0.add(v0.trim());
        }
        return seen0.size() == 1 ? seen0.iterator().next() : "";
    }

    // "1358292666" -> "$1,358,292,666". Blank in, blank out.
    private static String money(String digits0)
    {
        if (isBlank(digits0)) return "";
        double v0 = parseAmount(digits0);
        return v0 > 0.0 ? "$" + String.format("%,.0f", v0) : "$0";
    }

    private static double parseAmount(String s0)
    {
        if (isBlank(s0)) return 0.0;
        try { return Double.parseDouble(s0.replaceAll("[^0-9.]", "")); }
        catch (Exception e0) { return 0.0; }
    }

    // "2/21/2026" -> "2026-02-21". Returns "" on anything unparseable, so a filing
    // with no readable date is reported as undated rather than stamped with today.
    private static String toIsoDate(String usDate0)
    {
        if (isBlank(usDate0)) return "";
        String[] parts0 = usDate0.trim().split("/");
        if (parts0.length != 3) return "";
        try
        {
            return java.time.LocalDate.of(
                Integer.parseInt(parts0[2]),
                Integer.parseInt(parts0[0]),
                Integer.parseInt(parts0[1])).toString();
        }
        catch (Exception e0) { return ""; }
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

    // -----------------------------------------------------------------------
    // Name matching
    // -----------------------------------------------------------------------

    /*
     * Normalize a firm name for comparison. Strips ONLY true legal-entity suffixes.
     *
     * It deliberately no longer strips business words (capital / advisors / partners /
     * management / group / fund / ventures). Stripping those reduced both
     * "Nelson Advisors" and "Nelson Capital Advisors" to the single token "nelson",
     * making two unrelated firms an EXACT match — the distinguishing word is exactly
     * the word that was being thrown away. Do not re-add them to this list.
     */
    private static String normName(String s0)
    {
        if (isBlank(s0)) return "";
        return s0.toLowerCase()
            .replaceAll("\\b(inc\\.?|llc\\.?|l\\.l\\.c\\.?|lp\\.?|llp\\.?|plc\\.?|"
                + "corp\\.?|corporation|ltd\\.?|limited|co\\.?|company|the)\\b", " ")
            .replaceAll("[^a-z0-9 ]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    /*
     * Strong name agreement, measured as Jaccard overlap (shared tokens over the
     * UNION) at NAME_MATCH_THRESHOLD.
     *
     * The previous measure divided by the SMALLER token set, so any name that was a
     * strict subset of another scored a perfect 1.0 — "Nelson Advisors" matched
     * "Nelson Capital Advisors" outright. Jaccard charges for the extra token
     * (2/3 = 0.67), which falls below the threshold, so a subset name now requires a
     * domain anchor rather than passing on its own.
     */
    private static boolean nameStrong(String a0, String b0)
    {
        if (isBlank(a0) || isBlank(b0)) return false;
        if (a0.equals(b0)) return true;

        java.util.HashSet<String> ta0 =
            new java.util.HashSet<String>(java.util.Arrays.asList(a0.split(" ")));
        java.util.HashSet<String> tb0 =
            new java.util.HashSet<String>(java.util.Arrays.asList(b0.split(" ")));
        ta0.remove("");
        tb0.remove("");
        if (ta0.isEmpty() || tb0.isEmpty()) return false;

        java.util.HashSet<String> shared0 = new java.util.HashSet<String>(ta0);
        shared0.retainAll(tb0);

        java.util.HashSet<String> union0 = new java.util.HashSet<String>(ta0);
        union0.addAll(tb0);

        return (double) shared0.size() / (double) union0.size() >= NAME_MATCH_THRESHOLD;
    }

    // Extracts the registrable host from a URL string (strips www., scheme, path).
    private static String domain(String url0)
    {
        if (isBlank(url0)) return "";
        String d0 = url0.trim().toLowerCase()
            .replaceFirst("^https?://", "")
            .replaceFirst("^www\\.", "")
            .replaceFirst("/.*", "");
        return d0;
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
