package com.liminer.web;

import com.liminer.brief.InvestorBriefClient;
import com.liminer.brief.InvestorBriefJsonProcessor;
import com.liminer.core.InvestorProfile;
import com.liminer.core.SessionContext;
import com.liminer.enrich.BasicBackgroundChecker;
import com.liminer.intake.EmailIntakeProcessor;
import com.liminer.pipeline.LPEnrichmentProcessor;
import com.liminer.pipeline.LPScoreProcessor;
import com.liminer.pipeline.RelationshipSummaryProcessor;
import com.liminer.scout.CandidateDiscoveryProcessor;
import com.liminer.scout.CandidateScoringProcessor;
import com.liminer.scout.Tier1SignalProcessor;
import com.liminer.sheets.CrmUpdater;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Named workflows the web UI can run against a logged-in session, mirroring the
 * AgentMain.java terminal menu branches. Each entry calls exactly the same
 * processor entry point(s) as the corresponding AgentMain branch; AgentMain
 * itself is never touched or refactored.
 *
 * "delete user" and "natural language sheet request" are excluded (available: false)
 * because their AgentMain branches have interactive steps with no sensible default
 * (destructive delete confirmation and a free-form NL prompt respectively).
 * "investor brief pdf" runs headlessly via InvestorBriefClient.runAuto(), which uses
 * a non-blocking Prompter: it auto-accepts a single confident match and, for zero or
 * multiple candidate matches, returns an explanatory result instead of guessing.
 * "onboard user" is not excluded for being interactive — it has its own dedicated
 * pre-login web endpoints (POST /api/onboard/detect and POST /api/onboard/confirm
 * in WebServer.java) and is deliberately not a workflow at all.
 */
public class WorkflowRegistry
{
    public interface WorkflowHandler
    {
        String run(SessionContext context, JSONObject params) throws Exception;
    }

    /**
     * Optional read-only dry-run seam for a workflow (task 0150). Must never write to
     * the spreadsheet. Null on a WorkflowInfo means the workflow has no dry run.
     */
    public interface WorkflowPlanner
    {
        JSONObject plan(SessionContext context, JSONObject params) throws Exception;
    }

    public static class WorkflowInfo
    {
        public final String id;
        public final String name;
        public final String description;
        public final boolean available;
        public final String unavailableReason;
        public final WorkflowHandler handler;
        public final WorkflowPlanner planner;

        public WorkflowInfo(
            String id,
            String name,
            String description,
            boolean available,
            String unavailableReason,
            WorkflowHandler handler)
        {
            this(id, name, description, available, unavailableReason, handler, null);
        }

        public WorkflowInfo(
            String id,
            String name,
            String description,
            boolean available,
            String unavailableReason,
            WorkflowHandler handler,
            WorkflowPlanner planner)
        {
            this.id = id;
            this.name = name;
            this.description = description;
            this.available = available;
            this.unavailableReason = unavailableReason;
            this.handler = handler;
            this.planner = planner;
        }

        public JSONObject toJson()
        {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("name", name);
            json.put("description", description);
            json.put("available", available);
            json.put("hasPlan", planner != null);
            if (!available && unavailableReason != null)
            {
                json.put("reason", unavailableReason);
            }
            return json;
        }
    }

    private final List<WorkflowInfo> workflows = new ArrayList<>();

    public List<WorkflowInfo> list()
    {
        return workflows;
    }

    public WorkflowInfo get(String id)
    {
        for (WorkflowInfo info : workflows)
        {
            if (info.id.equals(id))
            {
                return info;
            }
        }
        return null;
    }

    public void add(WorkflowInfo info)
    {
        workflows.add(info);
    }

    private static int paramInt(JSONObject params, String key, int defaultValue)
    {
        if (params == null || !params.has(key) || params.isNull(key))
        {
            return defaultValue;
        }
        try
        {
            return params.getInt(key);
        }
        catch (Exception exception)
        {
            return defaultValue;
        }
    }

    private static boolean paramBool(JSONObject params, String key, boolean defaultValue)
    {
        if (params == null || !params.has(key) || params.isNull(key))
        {
            return defaultValue;
        }
        try
        {
            return params.getBoolean(key);
        }
        catch (Exception exception)
        {
            return defaultValue;
        }
    }

    /**
     * Builds the production registry mirroring AgentMain's dispatch (~lines 88-560).
     */
    public static WorkflowRegistry buildProductionRegistry()
    {
        WorkflowRegistry registry = new WorkflowRegistry();

        registry.add(new WorkflowInfo(
            "process-intake",
            "Process Intake",
            "Process unprocessed email intake rows.",
            true,
            null,
            (context, params) -> EmailIntakeProcessor.processUnprocessedIntakeRows(context)));

        registry.add(new WorkflowInfo(
            "update-crm",
            "Update CRM",
            "Update CRM rows from processed intake rows.",
            true,
            null,
            (context, params) -> CrmUpdater.updateCrmFromProcessedIntakeRows(context),
            (context, params) -> CrmUpdater.planCrmUpdate(context)));

        registry.add(new WorkflowInfo(
            "enrich-lps",
            "Enrich LPs",
            "Run LP enrichment.",
            true,
            null,
            (context, params) -> LPEnrichmentProcessor.enrichLpRows(context),
            (context, params) -> LPEnrichmentProcessor.planEnrichment(context, paramInt(params, "maxRows", 25))));

        registry.add(new WorkflowInfo(
            "discover-candidates",
            "Discover Candidates",
            "Discover new LP candidates from the client profile and append them to the CRM as Cold.",
            true,
            null,
            (context, params) ->
            {
                String sectors = context.user.clientSectorTags;
                String microsectors = context.user.clientMicrosectorTags;
                String geographies = context.user.clientGeography;
                String thesis = context.user.clientInvestmentThesis;

                int maxResultsPerQuery = paramInt(params, "maxResultsPerQuery", 5);
                int maxCandidates = paramInt(params, "maxCandidates", 20);
                boolean scrapeLinkedIn = paramBool(params, "scrapeLinkedIn", true);
                boolean scrapeWebsites = paramBool(params, "scrapeWebsites", false);
                boolean extractProfiles = paramBool(params, "extractProfiles", true);

                ArrayList<InvestorProfile> seedProfiles =
                    CandidateDiscoveryProcessor.buildSeedProfilesFromClientInput(
                        sectors, microsectors, geographies, thesis);

                CandidateDiscoveryProcessor processor = new CandidateDiscoveryProcessor();

                return processor.discoverAndAppendColdCandidates(
                    context,
                    seedProfiles,
                    maxResultsPerQuery,
                    maxCandidates,
                    scrapeLinkedIn,
                    scrapeWebsites,
                    extractProfiles);
            }));

        registry.add(new WorkflowInfo(
            "score-candidates",
            "Score Candidates",
            "Score the next batch of unscored candidates.",
            true,
            null,
            (context, params) -> CandidateScoringProcessor.scoreNextUnscoredCandidates(context, 10)));

        registry.add(new WorkflowInfo(
            "prioritize-relationships",
            "Prioritize Relationships",
            "Compute Tier-1 priority signals (Strategic Value + Action Urgency).",
            true,
            null,
            (context, params) ->
                Tier1SignalProcessor.runTier1Signals(context, paramInt(params, "maxRows", 100))));

        registry.add(new WorkflowInfo(
            "background-check",
            "Background Check",
            "Run the basic background check workflow.",
            true,
            null,
            (context, params) ->
                BasicBackgroundChecker.runBasicBackgroundCheckWorkflow(context, paramInt(params, "maxRows", 30))));

        registry.add(new WorkflowInfo(
            "market-intelligence",
            "Market Intelligence",
            "Run LP market intelligence scoring.",
            true,
            null,
            (context, params) -> LPScoreProcessor.scoreLpRows(context)));

        registry.add(new WorkflowInfo(
            "relationship-summary",
            "Relationship Summary",
            "Generate relationship summaries.",
            true,
            null,
            (context, params) -> RelationshipSummaryProcessor.generateSummaries(context)));

        registry.add(new WorkflowInfo(
            "investor-brief",
            "Investor Brief",
            "Generate investor briefs.",
            true,
            null,
            (context, params) ->
                InvestorBriefJsonProcessor.generateBriefs(context, paramInt(params, "maxRows", 10))));

        registry.add(new WorkflowInfo(
            "investor-brief-pdf",
            "Investor Brief PDF",
            "Generate a single-person investor brief PDF.",
            true,
            null,
            (context, params) ->
            {
                String firstName = params.optString("firstName", "");
                String lastName  = params.optString("lastName", "");
                String fundName  = params.optString("fundName", "");
                String email     = params.optString("email", "");
                InvestorBriefClient.ContactQuery query =
                    new InvestorBriefClient.ContactQuery(firstName, lastName, fundName, email);
                if (query.isEmpty()) return "ERROR: No contact details provided.";
                return InvestorBriefClient.runAuto(context, query);
            }));

        return registry;
    }
}
