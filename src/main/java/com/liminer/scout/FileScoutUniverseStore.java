package com.liminer.scout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;

/*
 * FileScoutUniverseStore — V1 ScoutUniverseStore implementation: one JSON
 * snapshot file per month at <baseDir>/<YYYY-MM>.json (default baseDir is
 * data/scout/adv-snapshots, gitignored). The filtered universe is a few
 * thousand records / single-digit MB, so flat JSON + in-memory load is
 * intentional here — do not "upgrade" to SQLite or a Sheets tab without a
 * new task; the interface exists so that swap can happen later.
 */
public class FileScoutUniverseStore implements ScoutUniverseStore
{
    private static final double RAUM_CHANGE_THRESHOLD0 = 0.20;

    private final Path baseDir0;

    public FileScoutUniverseStore()
    {
        this(Paths.get("data", "scout", "adv-snapshots"));
    }

    public FileScoutUniverseStore(Path baseDir0)
    {
        this.baseDir0 = baseDir0;
    }

    @Override
    public void save(String yyyyMm0, List<ScoutUniverseRecord> records0) throws Exception
    {
        Files.createDirectories(baseDir0);
        JSONArray arr0 = new JSONArray();
        if (records0 != null)
        {
            for (ScoutUniverseRecord r0 : records0) arr0.put(r0.toJson());
        }
        Files.writeString(monthFile(yyyyMm0), arr0.toString(), StandardCharsets.UTF_8);
    }

    @Override
    public List<ScoutUniverseRecord> load(String yyyyMm0) throws Exception
    {
        List<ScoutUniverseRecord> out0 = new ArrayList<ScoutUniverseRecord>();
        Path file0 = monthFile(yyyyMm0);
        if (!Files.exists(file0)) return out0;

        String json0 = Files.readString(file0, StandardCharsets.UTF_8);
        JSONArray arr0 = new JSONArray(json0);
        for (int i0 = 0; i0 < arr0.length(); i0++)
        {
            out0.add(ScoutUniverseRecord.fromJson(arr0.optJSONObject(i0)));
        }
        return out0;
    }

    @Override
    public List<String> availableMonths() throws Exception
    {
        List<String> months0 = new ArrayList<String>();
        if (!Files.isDirectory(baseDir0)) return months0;

        try (DirectoryStream<Path> stream0 = Files.newDirectoryStream(baseDir0, "*.json"))
        {
            for (Path p0 : stream0)
            {
                String name0 = p0.getFileName().toString();
                months0.add(name0.substring(0, name0.length() - ".json".length()));
            }
        }
        Collections.sort(months0);
        return months0;
    }

    @Override
    public ScoutUniverseDiff diff(String olderMonth0, String newerMonth0) throws Exception
    {
        ScoutUniverseDiff diff0 = new ScoutUniverseDiff();

        Map<Integer, ScoutUniverseRecord> older0 = byCrd(load(olderMonth0));
        Map<Integer, ScoutUniverseRecord> newer0 = byCrd(load(newerMonth0));

        for (Map.Entry<Integer, ScoutUniverseRecord> entry0 : newer0.entrySet())
        {
            int crd0 = entry0.getKey();
            ScoutUniverseRecord newRec0 = entry0.getValue();
            ScoutUniverseRecord oldRec0 = older0.get(crd0);

            if (oldRec0 == null)
            {
                diff0.newCrds.add(crd0);
                continue;
            }

            if (oldRec0.raumTotal > 0)
            {
                double pctChange0 = Math.abs(newRec0.raumTotal - oldRec0.raumTotal) / oldRec0.raumTotal;
                if (pctChange0 > RAUM_CHANGE_THRESHOLD0) diff0.raumChangeCrds.add(crd0);
            }
            else if (newRec0.raumTotal > 0)
            {
                diff0.raumChangeCrds.add(crd0);
            }

            Set<String> oldFundNames0 = new HashSet<String>();
            for (ScoutFundRecord f0 : oldRec0.funds) oldFundNames0.add(f0.name);

            for (ScoutFundRecord f0 : newRec0.funds)
            {
                boolean isNewFund0 = !oldFundNames0.contains(f0.name);
                boolean isFundOfFunds0 = f0.type != null && f0.type.toLowerCase().contains("fund of funds");
                if (isNewFund0 && isFundOfFunds0)
                {
                    diff0.newFundOfFundsCrds.add(crd0);
                    break;
                }
            }
        }

        return diff0;
    }

    private static Map<Integer, ScoutUniverseRecord> byCrd(List<ScoutUniverseRecord> records0)
    {
        Map<Integer, ScoutUniverseRecord> map0 = new HashMap<Integer, ScoutUniverseRecord>();
        for (ScoutUniverseRecord r0 : records0) map0.put(r0.crd, r0);
        return map0;
    }

    private Path monthFile(String yyyyMm0)
    {
        return baseDir0.resolve(yyyyMm0 + ".json");
    }
}
