package com.liminer.scout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;

/*
 * ScoutUniverseIndexer — refresh-time step that turns a raw monthly
 * ScoutUniverseStore snapshot into a pre-scored, pre-tagged, pre-sorted index
 * so per-client Investor Scout runs are instant reads instead of re-deriving
 * scores/exclusions every time (task 0088). Core design rule (user decision):
 * TAG, DON'T DROP — no record is ever removed by this client-independent
 * pass; every record from the input universe is present in the full scored
 * file, carrying tags and named exclusion reasons instead of being deleted,
 * so recall is never silently lost and a gate can be loosened later without
 * re-parsing the ADV bulk file.
 *
 * The exclusion gates mirror ONLY ScoutPrefilter's client-independent checks
 * (allocator test, absolute RAUM floor/cap) — the per-client check-size band,
 * geography preference, and CRM dedupe stay in ScoutPrefilter's per-client
 * run and are intentionally NOT reproduced here.
 *
 * Persistence follows FileScoutUniverseStore's idiom: one JSON file per
 * month at <baseDir>/<YYYY-MM>.json (full scored universe) plus a second
 * <baseDir>/<YYYY-MM>-hot.json hot-tier file, both flat JSON arrays.
 */
public class ScoutUniverseIndexer
{
    // ---- Hot-tier thresholds (named constants per task spec) -----------------
    public static final int HOT_TIER_MIN_RESOURCES = 70;
    public static final int HOT_TIER_MIN_PROBABILITY_NOW = 50;

    // ---- Named client-independent exclusion gates -----------------------------
    public static final String EXCLUSION_NOT_ALLOCATOR_CLIENT_TYPE = "NOT_ALLOCATOR_CLIENT_TYPE";
    public static final String EXCLUSION_RAUM_BELOW_FLOOR = "RAUM_BELOW_FLOOR";
    public static final String EXCLUSION_RAUM_ABOVE_CAP = "RAUM_ABOVE_CAP";

    private static final String[] EXCLUSION_GATES_IN_ORDER =
        { EXCLUSION_NOT_ALLOCATOR_CLIENT_TYPE, EXCLUSION_RAUM_BELOW_FLOOR, EXCLUSION_RAUM_ABOVE_CAP };

    private static final Path DEFAULT_BASE_DIR = Paths.get("data", "scout", "scored-universe");

    private final Path baseDir0;

    public ScoutUniverseIndexer() { this(DEFAULT_BASE_DIR); }

    public ScoutUniverseIndexer(Path baseDir0) { this.baseDir0 = baseDir0; }

    // -----------------------------------------------------------------------
    // Pure core
    // -----------------------------------------------------------------------

    public List<ScoutScoredRecord> index(List<ScoutUniverseRecord> universe0, Map<Integer, ScoutSignalScore> scores0)
    {
        List<ScoutScoredRecord> out0 = new ArrayList<ScoutScoredRecord>();
        if (universe0 == null) return out0;

        for (ScoutUniverseRecord record0 : universe0)
        {
            if (record0 == null) continue;

            ScoutScoredRecord scored0 = new ScoutScoredRecord();
            scored0.record = record0;
            scored0.tags = buildTags(record0);
            scored0.exclusions = buildExclusions(record0);

            ScoutSignalScore score0 = scores0 == null ? null : scores0.get(record0.crd);
            if (score0 != null)
            {
                scored0.resources = score0.resources;
                scored0.probabilityNow = score0.probabilityNow;
            }

            out0.add(scored0);
        }

        sortByResourcesThenProbabilityNow(out0);
        return out0;
    }

    // ---- Tagging ---------------------------------------------------------------

    private List<String> buildTags(ScoutUniverseRecord record0)
    {
        List<String> tags0 = new ArrayList<String>();

        if (record0.clientTypes != null)
        {
            for (String clientType0 : record0.clientTypes)
            {
                if (clientType0 != null && clientType0.trim().length() > 0)
                {
                    tags0.add("ALLOCATOR_TYPE:" + clientType0.trim());
                }
            }
        }

        if (hasFofFund(record0))
        {
            tags0.add("HAS_FOF_FUND");
        }

        String country0 = record0.country == null ? "" : record0.country.trim();
        if (country0.length() > 0) tags0.add("COUNTRY:" + country0);

        String state0 = record0.state == null ? "" : record0.state.trim();
        if (state0.length() > 0) tags0.add("STATE:" + state0);

        tags0.add("RAUM_BAND:" + ScoutSignalScorer.raumBandLabel(record0.raumTotal));

        return tags0;
    }

    private static boolean hasFofFund(ScoutUniverseRecord record0)
    {
        if (record0.funds == null) return false;
        for (ScoutFundRecord fund0 : record0.funds)
        {
            if (fund0 != null && fund0.type != null && fund0.type.toLowerCase().contains("fund of funds"))
            {
                return true;
            }
        }
        return false;
    }

    // ---- Exclusions (mirrors ScoutPrefilter's client-independent checks only) --

    private List<String> buildExclusions(ScoutUniverseRecord record0)
    {
        List<String> exclusions0 = new ArrayList<String>();

        if (!ScoutPrefilter.passesAllocatorTest(record0))
        {
            exclusions0.add(EXCLUSION_NOT_ALLOCATOR_CLIENT_TYPE);
        }

        if (record0.raumTotal < ScoutPrefilter.ABSOLUTE_FLOOR_RAUM)
        {
            exclusions0.add(EXCLUSION_RAUM_BELOW_FLOOR);
        }
        else if (record0.raumTotal > ScoutPrefilter.ABSOLUTE_CAP_RAUM)
        {
            exclusions0.add(EXCLUSION_RAUM_ABOVE_CAP);
        }

        return exclusions0;
    }

    // ---- Sort: resources desc, then probabilityNow desc (nulls sort last) ------

    private static void sortByResourcesThenProbabilityNow(List<ScoutScoredRecord> records0)
    {
        Collections.sort(records0, new Comparator<ScoutScoredRecord>()
        {
            @Override
            public int compare(ScoutScoredRecord a0, ScoutScoredRecord b0)
            {
                int cmp0 = Integer.compare(nullSafe(b0.resources), nullSafe(a0.resources));
                if (cmp0 != 0) return cmp0;
                return Integer.compare(nullSafe(b0.probabilityNow), nullSafe(a0.probabilityNow));
            }
        });
    }

    private static int nullSafe(Integer v0) { return v0 == null ? -1 : v0; }

    // ---- Hot tier ----------------------------------------------------------------

    public List<ScoutScoredRecord> hotTier(List<ScoutScoredRecord> allRecords0)
    {
        List<ScoutScoredRecord> hot0 = new ArrayList<ScoutScoredRecord>();
        if (allRecords0 == null) return hot0;
        for (ScoutScoredRecord scored0 : allRecords0)
        {
            if (scored0.isHotEligible(HOT_TIER_MIN_RESOURCES, HOT_TIER_MIN_PROBABILITY_NOW))
            {
                hot0.add(scored0);
            }
        }
        return hot0;
    }

    // ---- Funnel report -------------------------------------------------------------

    public String funnelReport(List<ScoutScoredRecord> allRecords0, List<ScoutScoredRecord> hotRecords0)
    {
        int total0 = allRecords0 == null ? 0 : allRecords0.size();

        Map<String, Integer> gateCounts0 = new LinkedHashMap<String, Integer>();
        for (String gate0 : EXCLUSION_GATES_IN_ORDER) gateCounts0.put(gate0, 0);

        if (allRecords0 != null)
        {
            for (ScoutScoredRecord scored0 : allRecords0)
            {
                if (scored0.exclusions == null) continue;
                for (String exclusion0 : scored0.exclusions)
                {
                    Integer current0 = gateCounts0.get(exclusion0);
                    if (current0 != null) gateCounts0.put(exclusion0, current0 + 1);
                }
            }
        }

        int hotCount0 = hotRecords0 == null ? 0 : hotRecords0.size();

        StringBuilder sb0 = new StringBuilder();
        sb0.append("Scout universe funnel: ").append(total0).append(" total records.\n");
        for (Map.Entry<String, Integer> entry0 : gateCounts0.entrySet())
        {
            sb0.append("  ").append(entry0.getKey()).append(": ").append(entry0.getValue()).append(" excluded\n");
        }
        sb0.append("  HOT_TIER (zero exclusions, resources>=").append(HOT_TIER_MIN_RESOURCES)
            .append(", probabilityNow>=").append(HOT_TIER_MIN_PROBABILITY_NOW).append("): ")
            .append(hotCount0).append(" records\n");

        String report0 = sb0.toString();
        System.out.print(report0);
        return report0;
    }

    // -----------------------------------------------------------------------
    // File writes (persistence style of FileScoutUniverseStore)
    // -----------------------------------------------------------------------

    public String indexAndWrite(String yyyyMm0, List<ScoutUniverseRecord> universe0, Map<Integer, ScoutSignalScore> scores0)
        throws IOException
    {
        List<ScoutScoredRecord> all0 = index(universe0, scores0);
        List<ScoutScoredRecord> hot0 = hotTier(all0);

        Files.createDirectories(baseDir0);
        writeFile(fullFile(yyyyMm0), all0);
        writeFile(hotFile(yyyyMm0), hot0);

        return funnelReport(all0, hot0);
    }

    private static void writeFile(Path file0, List<ScoutScoredRecord> records0) throws IOException
    {
        JSONArray arr0 = new JSONArray();
        if (records0 != null)
        {
            for (ScoutScoredRecord scored0 : records0) arr0.put(scored0.toJson());
        }
        Files.writeString(file0, arr0.toString(), StandardCharsets.UTF_8);
    }

    public List<ScoutScoredRecord> loadFull(String yyyyMm0) throws IOException { return loadFile(fullFile(yyyyMm0)); }

    public List<ScoutScoredRecord> loadHot(String yyyyMm0) throws IOException { return loadFile(hotFile(yyyyMm0)); }

    // Directly writes a hot-tier file (test-fixture support for ScoutProfilePrefetch,
    // task 0089) without re-deriving it from a raw universe + scores; production
    // writes still go through indexAndWrite above.
    public void writeHotFile(String yyyyMm0, List<ScoutScoredRecord> hotRecords0) throws IOException
    {
        Files.createDirectories(baseDir0);
        writeFile(hotFile(yyyyMm0), hotRecords0);
    }

    // Lists YYYY-MM months with a hot-tier file present, sorted ascending (task 0089:
    // profile-prefetch always runs against the newest, i.e. the last element).
    public List<String> availableHotMonths() throws IOException
    {
        List<String> months0 = new ArrayList<String>();
        if (!Files.isDirectory(baseDir0)) return months0;

        String suffix0 = "-hot.json";
        try (DirectoryStream<Path> stream0 = Files.newDirectoryStream(baseDir0, "*" + suffix0))
        {
            for (Path p0 : stream0)
            {
                String name0 = p0.getFileName().toString();
                months0.add(name0.substring(0, name0.length() - suffix0.length()));
            }
        }
        Collections.sort(months0);
        return months0;
    }

    private static List<ScoutScoredRecord> loadFile(Path file0) throws IOException
    {
        List<ScoutScoredRecord> out0 = new ArrayList<ScoutScoredRecord>();
        if (!Files.exists(file0)) return out0;

        String json0 = Files.readString(file0, StandardCharsets.UTF_8);
        JSONArray arr0 = new JSONArray(json0);
        for (int i0 = 0; i0 < arr0.length(); i0++)
        {
            out0.add(ScoutScoredRecord.fromJson(arr0.optJSONObject(i0)));
        }
        return out0;
    }

    private Path fullFile(String yyyyMm0) { return baseDir0.resolve(yyyyMm0 + ".json"); }

    private Path hotFile(String yyyyMm0) { return baseDir0.resolve(yyyyMm0 + "-hot.json"); }
}
