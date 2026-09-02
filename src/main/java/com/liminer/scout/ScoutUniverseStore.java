package com.liminer.scout;

import java.util.List;

/*
 * ScoutUniverseStore — persistence for monthly SEC ADV bulk-universe snapshots,
 * behind an interface so a database (e.g. SQLite) can replace the V1 flat-file
 * implementation later without changing callers. See FileScoutUniverseStore for
 * the current implementation (one JSON file per month).
 */
public interface ScoutUniverseStore
{
    void save(String yyyyMm, List<ScoutUniverseRecord> records) throws Exception;

    List<ScoutUniverseRecord> load(String yyyyMm) throws Exception;

    List<String> availableMonths() throws Exception;

    ScoutUniverseDiff diff(String olderMonth, String newerMonth) throws Exception;
}
