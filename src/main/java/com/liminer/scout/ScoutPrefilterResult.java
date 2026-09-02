package com.liminer.scout;

import java.util.ArrayList;
import java.util.List;

/*
 * ScoutPrefilterResult — output of ScoutPrefilter.filter: the surviving
 * records plus a per-stage drop count for logging/debugging the funnel.
 */
public class ScoutPrefilterResult
{
    public List<ScoutUniverseRecord> kept;
    public int totalInput;
    public int droppedAllocatorTest;
    public int droppedResourcesBand;
    public int droppedGeography;
    public int droppedDedupe;

    public ScoutPrefilterResult()
    {
        kept = new ArrayList<ScoutUniverseRecord>();
        totalInput = 0;
        droppedAllocatorTest = 0;
        droppedResourcesBand = 0;
        droppedGeography = 0;
        droppedDedupe = 0;
    }

    public int keptCount()
    {
        return kept.size();
    }

    @Override
    public String toString()
    {
        return "ScoutPrefilterResult{totalInput=" + totalInput
            + ", droppedAllocatorTest=" + droppedAllocatorTest
            + ", droppedResourcesBand=" + droppedResourcesBand
            + ", droppedGeography=" + droppedGeography
            + ", droppedDedupe=" + droppedDedupe
            + ", kept=" + keptCount() + "}";
    }
}
