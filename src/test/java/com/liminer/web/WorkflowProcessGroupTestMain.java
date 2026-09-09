package com.liminer.web;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline check (task 0200) that buildProductionRegistry() assigns every workflow to
 * exactly one process, in the exact order the user specified, and that an unassigned
 * WorkflowInfo built with the old 6-arg constructor still serializes and does not
 * break processes(). No network, no Sheets access. Prints PROCESS_GROUPS_OK on
 * success; exits 1 on any failure.
 */
public class WorkflowProcessGroupTestMain
{
    private static final List<String> EXPECTED_PROCESS_ORDER = Arrays.asList(
        "refresh-crm", "deep-research", "discover", "analyze", "prioritize", "report");

    public static void main(String[] args) throws Exception
    {
        WorkflowRegistry registry = WorkflowRegistry.buildProductionRegistry();
        List<WorkflowRegistry.ProcessInfo> processes = registry.processes();

        check("exactly six processes", processes.size() == 6);

        for (int i = 0; i < EXPECTED_PROCESS_ORDER.size() && i < processes.size(); i++)
        {
            check(
                "process order [" + i + "] is " + EXPECTED_PROCESS_ORDER.get(i),
                EXPECTED_PROCESS_ORDER.get(i).equals(processes.get(i).id));
        }

        check(
            "refresh-crm members",
            Arrays.asList("process-intake", "update-crm").equals(findProcess(processes, "refresh-crm").workflowIds));
        check(
            "deep-research members",
            Arrays.asList("background-check", "enrich-lps", "embed-lps")
                .equals(findProcess(processes, "deep-research").workflowIds));
        check(
            "discover members",
            Arrays.asList("discover-candidates").equals(findProcess(processes, "discover").workflowIds));
        check(
            "analyze members",
            Arrays.asList("market-intelligence", "relationship-summary", "score-candidates", "investor-brief")
                .equals(findProcess(processes, "analyze").workflowIds));
        check(
            "prioritize members",
            Arrays.asList("prioritize-relationships").equals(findProcess(processes, "prioritize").workflowIds));
        check(
            "report members",
            Arrays.asList("investor-brief-pdf").equals(findProcess(processes, "report").workflowIds));

        Set<String> registeredIds = new HashSet<>();
        for (WorkflowRegistry.WorkflowInfo info : registry.list())
        {
            registeredIds.add(info.id);
        }

        Set<String> idsInProcesses = new HashSet<>();
        for (WorkflowRegistry.ProcessInfo processInfo : processes)
        {
            for (String id : processInfo.workflowIds)
            {
                check("process id " + id + " is registered", registeredIds.contains(id));
                check("id " + id + " not already claimed by another process", idsInProcesses.add(id));
            }
        }

        check("every registered workflow belongs to exactly one process", idsInProcesses.equals(registeredIds));
        check("registry has all 12 workflows", registeredIds.size() == 12);

        WorkflowRegistry.WorkflowInfo unassigned = new WorkflowRegistry.WorkflowInfo(
            "unassigned-test",
            "Unassigned Test",
            "Never assigned to a process.",
            true,
            null,
            (context, params) -> "ok");

        check("unassigned toJson has no processId key", !unassigned.toJson().has("processId"));

        WorkflowRegistry registryWithUnassigned = WorkflowRegistry.buildProductionRegistry();
        registryWithUnassigned.add(unassigned);
        List<WorkflowRegistry.ProcessInfo> processesWithUnassigned = registryWithUnassigned.processes();
        check("processes() still returns six with an unassigned workflow present", processesWithUnassigned.size() == 6);

        System.out.println("PROCESS_GROUPS_OK");
    }

    private static WorkflowRegistry.ProcessInfo findProcess(List<WorkflowRegistry.ProcessInfo> processes, String id)
    {
        for (WorkflowRegistry.ProcessInfo processInfo : processes)
        {
            if (processInfo.id.equals(id))
            {
                return processInfo;
            }
        }
        throw new RuntimeException("no such process: " + id);
    }

    private static void check(String description, boolean condition)
    {
        if (!condition)
        {
            System.out.println("FAIL: " + description);
            System.exit(1);
        }
    }
}
