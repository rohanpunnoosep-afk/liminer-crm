package com.liminer.core;

public class SyncResult
{
    public int fetched = 0;
    public int accepted = 0;
    public int denied = 0;
    public int duplicates = 0;
    public int appended = 0;
    public long newWatermarkEpochMillis = 0L;

    public String summary()
    {
        return "Gmail sync complete. Fetched: " + fetched
            + ", Accepted: " + accepted
            + ", Denied: " + denied
            + ", Duplicates: " + duplicates
            + ", Appended: " + appended
            + ".";
    }
}
