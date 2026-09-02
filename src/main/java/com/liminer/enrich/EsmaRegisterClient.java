package com.liminer.enrich;

import com.liminer.scout.ScoutUniverseRecord;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/*
 * EsmaRegisterClient — parses the ESMA (European Securities and Markets
 * Authority) public register of Alternative Investment Fund Managers (AIFMs),
 * published as a downloadable CSV by ESMA (no API key required; public data).
 * Mirrors AdvBulkClient's split between a network-fetch layer and a pure,
 * offline-testable CSV parser, resolving columns by case-insensitive
 * header-name matching (not by index) and throwing loudly (naming the
 * missing header) when a required column is absent — exact column headers
 * can vary by register vintage, same caveat as AdvBulkClient's ADV CSV.
 *
 * ESMA AIFMs have no US CRD number; identity is carried via the new
 * ScoutUniverseRecord.externalRegisterId field (LEI when present, else the
 * register's own AIFMD ID), never by repurposing crd (left 0).
 */
public class EsmaRegisterClient
{
    private static final HttpClient CLIENT0 = HttpClient.newHttpClient();
    private static final String USER_AGENT0 = HttpContact.USER_AGENT0;
    private static final int TIMEOUT_SECS0 = 60;

    public EsmaRegisterClient() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Live fetch + parse: downloads the ESMA AIFM register CSV from csvUrl0 and parses it. */
    public List<ScoutUniverseRecord> fetchAndParse(String csvUrl0) throws Exception
    {
        if (isBlank(csvUrl0)) throw new IllegalArgumentException("csvUrl0 is required");
        String body0 = get(csvUrl0);
        try (Reader r0 = new java.io.StringReader(body0))
        {
            return parseCsv(r0);
        }
    }

    /**
     * Pure parser: ESMA AIFM register CSV -> ScoutUniverseRecord list. Columns
     * resolved by case-insensitive header-name matching; throws
     * IllegalArgumentException naming the missing header when a required
     * column cannot be found.
     */
    public List<ScoutUniverseRecord> parseCsv(Reader csv0) throws IOException
    {
        List<String[]> rows0 = parseCsvRows(csv0);
        if (rows0.isEmpty()) return new ArrayList<ScoutUniverseRecord>();
        String[] headers0 = rows0.get(0);

        int colName0 = requireColumn(headers0, "entity name");
        int colLei0 = requireColumn(headers0, "lei");
        int colAifmId0 = requireColumn(headers0, "aifmd id");
        int colCountry0 = requireColumn(headers0, "country");
        int colNca0 = requireColumn(headers0, "national competent authority");

        List<ScoutUniverseRecord> out0 = new ArrayList<ScoutUniverseRecord>();
        for (int i0 = 1; i0 < rows0.size(); i0++)
        {
            String[] row0 = rows0.get(i0);
            if (row0.length <= colName0) continue;
            String name0 = cell(row0, colName0);
            if (isBlank(name0)) continue;

            String lei0 = cell(row0, colLei0);
            String aifmId0 = cell(row0, colAifmId0);

            ScoutUniverseRecord rec0 = new ScoutUniverseRecord();
            rec0.crd = 0;
            rec0.externalRegisterId = !isBlank(lei0) ? lei0 : aifmId0;
            rec0.sourceRegister = "ESMA";
            rec0.firmName = name0;
            rec0.country = cell(row0, colCountry0);
            rec0.clientTypes = new ArrayList<String>();
            rec0.clientTypes.add("AIFM");
            String nca0 = cell(row0, colNca0);
            if (!isBlank(nca0)) rec0.clientTypes.add(nca0);
            out0.add(rec0);
        }
        return out0;
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

        HttpResponse<String> resp0 = CLIENT0.send(req0, HttpResponse.BodyHandlers.ofString());
        int status0 = resp0.statusCode();
        if (status0 < 200 || status0 >= 300)
            throw new RuntimeException("HTTP " + status0 + " for " + url0);
        return resp0.body();
    }

    // -----------------------------------------------------------------------
    // CSV parsing (RFC4180-ish: quoted fields, embedded commas/newlines, "" escape)
    // -----------------------------------------------------------------------

    private static List<String[]> parseCsvRows(Reader reader0) throws IOException
    {
        List<String[]> rows0 = new ArrayList<String[]>();
        PushbackReader pr0 = new PushbackReader(reader0, 1);
        List<String> current0 = new ArrayList<String>();
        StringBuilder field0 = new StringBuilder();
        boolean inQuotes0 = false;
        boolean rowHasContent0 = false;
        int ic0;

        while ((ic0 = pr0.read()) != -1)
        {
            char c0 = (char) ic0;
            rowHasContent0 = true;
            if (inQuotes0)
            {
                if (c0 == '"')
                {
                    int next0 = pr0.read();
                    if (next0 == '"') field0.append('"');
                    else
                    {
                        inQuotes0 = false;
                        if (next0 != -1) pr0.unread(next0);
                    }
                }
                else field0.append(c0);
            }
            else
            {
                if (c0 == '"') inQuotes0 = true;
                else if (c0 == ',')
                {
                    current0.add(field0.toString());
                    field0.setLength(0);
                }
                else if (c0 == '\r')
                {
                    int next0 = pr0.read();
                    if (next0 != '\n' && next0 != -1) pr0.unread(next0);
                    current0.add(field0.toString());
                    field0.setLength(0);
                    rows0.add(current0.toArray(new String[0]));
                    current0 = new ArrayList<String>();
                    rowHasContent0 = false;
                }
                else if (c0 == '\n')
                {
                    current0.add(field0.toString());
                    field0.setLength(0);
                    rows0.add(current0.toArray(new String[0]));
                    current0 = new ArrayList<String>();
                    rowHasContent0 = false;
                }
                else field0.append(c0);
            }
        }

        if (rowHasContent0 || field0.length() > 0 || !current0.isEmpty())
        {
            current0.add(field0.toString());
            rows0.add(current0.toArray(new String[0]));
        }

        return rows0;
    }

    private static int findColumn(String[] headers0, String keyword0)
    {
        String kw0 = keyword0.toLowerCase();
        for (int i0 = 0; i0 < headers0.length; i0++)
        {
            if (headers0[i0] != null && headers0[i0].toLowerCase().contains(kw0)) return i0;
        }
        return -1;
    }

    private static int requireColumn(String[] headers0, String keyword0)
    {
        int idx0 = findColumn(headers0, keyword0);
        if (idx0 < 0)
            throw new IllegalArgumentException(
                "EsmaRegisterClient: missing required ESMA CSV column matching \"" + keyword0 + "\"");
        return idx0;
    }

    private static String cell(String[] row0, int idx0)
    {
        return (idx0 >= 0 && idx0 < row0.length && row0[idx0] != null) ? row0[idx0].trim() : "";
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
