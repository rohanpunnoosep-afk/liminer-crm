package com.liminer.scout;

/*
 * ============================================================================
 * MASTER KILL SWITCH — Investor Scout pipeline (tasks 0084-0089 chain).
 *
 * WHY: the operator's machine has LESS THAN 240 MB of free disk. The live
 * Scout workflow downloads a ~250 MB SEC ADV bulk file and spends Bright
 * Data / OpenAI quota on every run. Running it end-to-end would fill the
 * disk. Every production Scout job handler must therefore refuse to run
 * until this is explicitly turned back on.
 *
 * HOW TO ENABLE LATER: flip SCOUT_WORKFLOW_ENABLED to true, once there is
 * confirmed headroom on disk (and Bright Data / OpenAI spend is intended).
 * That one constant is the only thing standing between "Scout is fully
 * built" and "Scout is live."
 * ============================================================================
 */
public class ScoutWorkflowGate
{
    public static final boolean SCOUT_WORKFLOW_ENABLED = false;

    public static void requireEnabled(String what)
    {
        if (!SCOUT_WORKFLOW_ENABLED)
        {
            throw new IllegalStateException(
                "SCOUT WORKFLOW IS DISABLED (disk-space guard). Flip ScoutWorkflowGate.SCOUT_WORKFLOW_ENABLED to true to enable: " + what);
        }
    }
}
