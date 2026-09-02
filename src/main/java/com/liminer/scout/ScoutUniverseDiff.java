package com.liminer.scout;

import java.util.ArrayList;
import java.util.List;

/*
 * ScoutUniverseDiff — month-over-month timing signals produced by
 * ScoutUniverseStore.diff(olderMonth, newerMonth). Each list holds CRDs
 * (Form ADV firm identifiers) that a later Investor Scout pipeline stage will
 * use as candidate timing signals.
 */
public class ScoutUniverseDiff
{
    public List<Integer> newCrds = new ArrayList<Integer>();
    public List<Integer> raumChangeCrds = new ArrayList<Integer>();
    public List<Integer> newFundOfFundsCrds = new ArrayList<Integer>();
}
