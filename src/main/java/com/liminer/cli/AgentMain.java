package com.liminer.cli;

import com.liminer.brief.InvestorBriefClient;
import com.liminer.brief.InvestorBriefJsonProcessor;
import com.liminer.core.CRMRegistry;
import com.liminer.core.InvestorProfile;
import com.liminer.core.SessionContext;
import com.liminer.core.SyncResult;
import com.liminer.enrich.BasicBackgroundChecker;
import com.liminer.intake.EmailIntakeProcessor;
import com.liminer.intake.GmailIntakeSync;
import com.liminer.llm.OpenAIClient;
import com.liminer.llm.ToolArgSpec;
import com.liminer.llm.ToolCall;
import com.liminer.llm.ToolDispatcher;
import com.liminer.llm.ToolRegistry;
import com.liminer.llm.ToolSpec;
import com.liminer.pipeline.LPEnrichmentProcessor;
import com.liminer.pipeline.LPScoreProcessor;
import com.liminer.pipeline.RelationshipSummaryProcessor;
import com.liminer.scout.CandidateDiscoveryProcessor;
import com.liminer.scout.CandidateInvestor;
import com.liminer.scout.CandidateScoringProcessor;
import com.liminer.scout.Tier1SignalProcessor;
import com.liminer.sheets.CrmUpdater;
import com.liminer.sheets.SheetCall;
import com.liminer.sheets.SheetRef;
import com.liminer.sheets.SheetRegistry;
import com.liminer.web.CRMOnboard;

import java.util.Scanner;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public class AgentMain
{
    public static void main(String[] args) throws Exception
    {
        Scanner scanner0 = new Scanner(System.in);

        System.out.println("===== AI INVESTOR MANAGER =====");
        System.out.println("Choose command:");
        System.out.println("onboard user");
        System.out.println("sync gmail");
        System.out.println("process intake");
        System.out.println("update crm");
        System.out.println("delete user");
        System.out.println("enrich lps");
        System.out.println("discover candidates");
        System.out.println("score candidates");
        System.out.println("prioritize relationships");
        System.out.println("background check");
        System.out.println("market intelligence");
        System.out.println("relationship summary");
        System.out.println("investor brief");
        System.out.println("investor brief pdf");
        System.out.println();
        System.out.print("Enter command: ");

        String prompt0 = scanner0.nextLine().trim();

        // ============================================================
        // 1. ONBOARD USER
        // ============================================================
        
        if (prompt0.equalsIgnoreCase("onboard user") || prompt0.equals("1"))
        {
            String userId0 = CRMRegistry.generateUserId();
            System.out.println("Generated User ID: " + userId0);

            System.out.print("User email: ");
            String email0 = scanner0.nextLine().trim();

            System.out.print("Fund name: ");
            String fundName0 = scanner0.nextLine().trim();

            System.out.print("Client spreadsheet ID: ");
            String spreadsheetId0 = scanner0.nextLine().trim();

            System.out.print("Internal full names separated by | Ex: Don Smith|Jace Wang ");
            ArrayList<String> internalNames0 = parsePipeSeparatedList(scanner0.nextLine());

            System.out.print("Internal emails separated by | : ");
            ArrayList<String> internalEmails0 = parsePipeSeparatedList(scanner0.nextLine());

            System.out.print("Internal fund name: ");
            String internalFundName0 = scanner0.nextLine().trim();

            System.out.print("Internal website: ");
            String internalWebsite0 = scanner0.nextLine().trim();

            System.out.print("Client Sector Tags separated by | : ");
            String clientSectorTags0 = scanner0.nextLine().trim();

            System.out.print("Client Microsector Tags separated by | : ");
            String clientMicrosectorTags0 = scanner0.nextLine().trim();

            System.out.print("Client Geography: ");
            String clientGeography0 = scanner0.nextLine().trim();

            System.out.print("Client Stages separated by | : ");
            String clientStages0 = scanner0.nextLine().trim();

            System.out.print("Client Investment Thesis: ");
            String clientInvestmentThesis0 = scanner0.nextLine().trim();

            String clientProfileJson0 = buildClientProfileJson(
                clientSectorTags0,
                clientMicrosectorTags0,
                clientGeography0,
                clientInvestmentThesis0
            );

            System.out.print("Possible tab names separated by | : ");
            String[] possibleTabNames0 = parsePipeSeparatedArray(scanner0.nextLine());

            System.out.println();
            System.out.println("Starting onboarding...");

            SessionContext context0 = CRMOnboard.onboardUser(
                userId0,
                email0,
                fundName0,
                spreadsheetId0,
                internalNames0,
                internalEmails0,
                internalFundName0,
                internalWebsite0,
                clientSectorTags0,
                clientMicrosectorTags0,
                clientGeography0,
                clientStages0,
                clientInvestmentThesis0,
                clientProfileJson0,
                possibleTabNames0
            );

            System.out.println("Onboarding complete.");
            context0.printSummary();

            scanner0.close();
            System.exit(0);
        }

        // ============================================================
        // SYNC GMAIL
        //
        // Pulls new Gmail messages for the logged-in user, runs each through
        // EmailRelevanceFilter, and appends accepted messages as new "Email Intake"
        // rows for the existing EmailIntakeProcessor to pick up.
        // ============================================================

        else if (prompt0.equalsIgnoreCase("sync gmail"))
        {
            SessionContext context0 = loginFromTerminal(scanner0);

            if (context0 == null)
            {
                scanner0.close();
                System.exit(0);
            }

            System.out.println();
            System.out.println("Starting Gmail sync...");

            GmailIntakeSync sync0 = GmailIntakeSync.buildForSession(context0);
            SyncResult result0 = sync0.runSync(context0.user.userId, "data/gmail-sync");

            System.out.println(result0.summary());

            scanner0.close();
            System.exit(0);
        }

        // ============================================================
        // 2. PROCESS INTAKE
        // ============================================================

        else if (prompt0.equalsIgnoreCase("process intake") || prompt0.equals("2"))
        {
            SessionContext context0 = loginFromTerminal(scanner0);

            if (context0 == null)
            {
                scanner0.close();
                System.exit(0);
            }

            String result0 = EmailIntakeProcessor.processUnprocessedIntakeRows(context0);

            System.out.println(result0);

            scanner0.close();
            System.exit(0);
        }

        // ============================================================
        // 3. UPDATE CRM
        // ============================================================

        else if (prompt0.equalsIgnoreCase("update crm") || prompt0.equals("3"))
        {
            SessionContext context0 = loginFromTerminal(scanner0);

            if (context0 == null)
            {
                scanner0.close();
                System.exit(0);
            }

            String result0 = CrmUpdater.updateCrmFromProcessedIntakeRows(context0);

            System.out.println(result0);

            scanner0.close();
            System.exit(0);
        }

        // ============================================================
        // 4. FOLLOW UPS  (priorityscoringv2: unified Tier-1 engine)
        //
        // Replaces the old FollowUpRecommender scoring. Follow-up priority is
        // now the deterministic Action Urgency axis written by Tier1SignalProcessor
        // (no fresh LLM call); the drafted follow-up text is a Tier-2 concern.
        // ============================================================

        else if (prompt0.equalsIgnoreCase("follow ups") || prompt0.equals("4"))
        {
            SessionContext context0 = loginFromTerminal(scanner0);

            if (context0 == null)
            {
                scanner0.close();
                System.exit(0);
            }

            System.out.print("Max rows to prioritize (default 100): ");
            int maxRows0 = parseIntOrDefault(scanner0.nextLine().trim(), 100);

            System.out.println();
            System.out.println("Starting Tier-1 priority signal workflow...");

            String result0 = Tier1SignalProcessor.runTier1Signals(context0, maxRows0);

            System.out.println(result0);

            scanner0.close();
            System.exit(0);
        }

        // ============================================================
        // 5. FULL WORKFLOW
        //
        // This is the main demo flow.
        // It assumes Gmail sync already happened or intake rows already exist.
        // ============================================================

        else if (prompt0.equalsIgnoreCase("full workflow") || prompt0.equals("5"))
        {
            SessionContext context0 = loginFromTerminal(scanner0);

            if (context0 == null)
            {
                scanner0.close();
                System.exit(0);
            }

            System.out.println();
            System.out.println("STEP 1: Processing email intake...");
            String intakeResult0 = EmailIntakeProcessor.processUnprocessedIntakeRows(context0);
            System.out.println(intakeResult0);

            System.out.println();
            System.out.println("STEP 2: Updating CRM...");
            String crmResult0 = CrmUpdater.updateCrmFromProcessedIntakeRows(context0);
            System.out.println(crmResult0);

            System.out.println();
            System.out.println("STEP 3: Computing Tier-1 priority signals...");
            // priorityscoringv2: the old FollowUpRecommender generate/write steps are
            // replaced by the unified deterministic Tier-1 engine (Strategic Value +
            // Action Urgency + Priority Signal JSON; no fresh LLM call).
            String priorityResult0 = Tier1SignalProcessor.runTier1Signals(context0, 100);
            System.out.println(priorityResult0);

            System.out.println();
            System.out.println("Full workflow complete.");

            scanner0.close();
            System.exit(0);
        }

        // ============================================================
        // 6. DELETE USER
        // ============================================================
        else if (prompt0.equalsIgnoreCase("delete user"))
        {
            System.out.print("Email: ");
            String email0 = scanner0.nextLine().trim();

            System.out.print("User ID: ");
            String userId0 = scanner0.nextLine().trim();

            String result0 = CRMRegistry.deleteUser(
                email0,
                userId0
            );

            System.out.println(result0);

            scanner0.close();
            System.exit(0);
        }

        // ============================================================
        // 7. ENRICH LPS
        // ============================================================

        else if (prompt0.equalsIgnoreCase("enrich lps") || prompt0.equals("7"))
        {
            SessionContext context0 = loginFromTerminal(scanner0);

            if (context0 == null)
            {
                scanner0.close();
                System.exit(0);
            }

            System.out.println();
            System.out.println("Starting LP enrichment...");

            String result0 = LPEnrichmentProcessor.enrichLpRows(context0);

            System.out.println(result0);

            scanner0.close();
            System.exit(0);
        }

        // ============================================================
        // 8. DISCOVER CANDIDATES
        //
        // Pipeline 3 Candidate Discovery.
        // Generates LinkedIn-targeted SERP queries from a client profile,
        // discovers candidates, optionally scrapes/enriches them, scores them,
        // and optionally appends them to the CRM as Cold.
        // ============================================================

        else if (prompt0.equalsIgnoreCase("discover candidates") || prompt0.equals("8"))
        {
            SessionContext context0 = loginFromTerminal(scanner0);

            if (context0 == null)
            {
                scanner0.close();
                System.exit(0);
            }

            System.out.println();

            String sectors0 = context0.user.clientSectorTags;
            String microsectors0 = context0.user.clientMicrosectorTags;
            String geographies0 = context0.user.clientGeography;
            String thesis0 = context0.user.clientInvestmentThesis;

            System.out.println();
            System.out.println("Sector tags: " + sectors0);
            System.out.println("Microsector tags: " + microsectors0);
            System.out.println("Geographies: " + geographies0);
            System.out.println("Thesis: " + thesis0);


            System.out.print("Max SERP results per query (default 5): ");
            int maxResultsPerQuery0 = parseIntOrDefault(scanner0.nextLine().trim(), 5);

            System.out.print("Max candidates total (default 20): ");
            int maxCandidates0 = parseIntOrDefault(scanner0.nextLine().trim(), 20);

            System.out.print("Scrape LinkedIn with Bright Data? true/false (default true): ");
            boolean scrapeLinkedIn0 = parseBooleanOrDefault(scanner0.nextLine().trim(), true);

            System.out.print("Scrape candidate websites? true/false (default false): ");
            boolean scrapeWebsites0 = parseBooleanOrDefault(scanner0.nextLine().trim(), false);

            System.out.print("Extract InvestorProfiles with OpenAI? true/false (default true): ");
            boolean extractProfiles0 = parseBooleanOrDefault(scanner0.nextLine().trim(), true);

            System.out.print("Append new candidates to CRM as Cold? true/false (default true): ");
            boolean appendToCrm0 = parseBooleanOrDefault(scanner0.nextLine().trim(), true);

            ArrayList<InvestorProfile> seedProfiles0 =
                CandidateDiscoveryProcessor.buildSeedProfilesFromClientInput(
                    sectors0,
                    microsectors0,
                    geographies0,
                    thesis0
                );

            CandidateDiscoveryProcessor processor0 = new CandidateDiscoveryProcessor();

            if (appendToCrm0)
            {
                String result0 = processor0.discoverAndAppendColdCandidates(
                    context0,
                    seedProfiles0,
                    maxResultsPerQuery0,
                    maxCandidates0,
                    scrapeLinkedIn0,
                    scrapeWebsites0,
                    extractProfiles0
                );

                System.out.println(result0);
            }
            else
            {
                ArrayList<CandidateInvestor> candidates0 = processor0.discoverCandidates(
                    seedProfiles0,
                    maxResultsPerQuery0,
                    maxCandidates0,
                    scrapeLinkedIn0,
                    scrapeWebsites0,
                    extractProfiles0
                );

                System.out.println();
                System.out.println("===== DISCOVERED CANDIDATES =====");

                for (int i = 0; i < candidates0.size(); i++)
                {
                    System.out.println();
                    System.out.println("Candidate " + (i + 1));
                    candidates0.get(i).printSummary();
                }

                System.out.println();
                System.out.println("Candidate discovery complete. Candidates found: " + candidates0.size());
            }

            scanner0.close();
            System.exit(0);
        }


        // ============================================================
        // 9. SCORE CANDIDATES
        //
        // Pipeline 3 Candidate Scoring workflow.
        // It builds an average basis profile from CRM rows with
        // First Interest or better, then scores the first 10 unscored
        // rows that already have an InvestorProfile / Intelligence JSON.
        // Scores are written into the InvestmentProbability column.
        // ============================================================

        else if (prompt0.equalsIgnoreCase("score candidates") || prompt0.equals("9"))
        {
            SessionContext context0 = loginFromTerminal(scanner0);

            if (context0 == null)
            {
                scanner0.close();
                System.exit(0);
            }

            System.out.println();
            System.out.println("Starting candidate scoring...");

            String result0 = CandidateScoringProcessor.scoreNextUnscoredCandidates(
                context0,
                10
            );

            System.out.println(result0);

            scanner0.close();
            System.exit(0);
        }

        // ============================================================
        // 10. PRIORITIZE RELATIONSHIPS
        // ============================================================

        else if (prompt0.equalsIgnoreCase("prioritize relationships") || prompt0.equals("10"))
        {
            SessionContext context0 = loginFromTerminal(scanner0);

            if (context0 == null)
            {
                scanner0.close();
                System.exit(0);
            }

            System.out.print("Max rows to prioritize (default 100): ");
            int maxRows0 = parseIntOrDefault(scanner0.nextLine().trim(), 100);

            System.out.println();
            System.out.println("Starting Tier-1 priority signal workflow...");

            // priorityscoringv2: unified deterministic Tier-1 engine replaces the old
            // PriorityActionProcessor / FollowUpRecommender scoring. Writes Strategic
            // Value + Action Urgency + Priority Signal JSON (no fresh LLM call).
            String result0 = Tier1SignalProcessor.runTier1Signals(context0, maxRows0);

            System.out.println(result0);

            scanner0.close();
            System.exit(0);
        }

        // ============================================================
        // 11. BACKGROUND CHECK
        // ============================================================

        else if (prompt0.equalsIgnoreCase("background check") || prompt0.equals("11"))
        {
            SessionContext context0 = loginFromTerminal(scanner0);

            if (context0 == null)
            {
                scanner0.close();
                System.exit(0);
            }

            System.out.print("Max rows to check (default 30): ");
            int maxRows0 = parseIntOrDefault(scanner0.nextLine().trim(), 30);

            System.out.println();
            System.out.println("Starting basic background check...");

            String result0 = BasicBackgroundChecker.runBasicBackgroundCheckWorkflow(context0, maxRows0);

            System.out.println(result0);

            scanner0.close();
            System.exit(0);
        }

        // ============================================================
        // 13. MARKET INTELLIGENCE
        // ============================================================

        else if (prompt0.equalsIgnoreCase("market intelligence") || prompt0.equals("13"))
        {
            SessionContext context0 = loginFromTerminal(scanner0);

            if (context0 == null)
            {
                scanner0.close();
                System.exit(0);
            }

            System.out.println();
            System.out.println("Starting LP market intelligence...");

            String result0 = LPScoreProcessor.scoreLpRows(context0);

            System.out.println(result0);

            scanner0.close();
            System.exit(0);
        }

        // ============================================================
        // 14. RELATIONSHIP SUMMARY
        // ============================================================

        else if (prompt0.equalsIgnoreCase("relationship summary") || prompt0.equals("14"))
        {
            SessionContext context0 = loginFromTerminal(scanner0);

            if (context0 == null)
            {
                scanner0.close();
                System.exit(0);
            }

            System.out.println();
            System.out.println("Starting relationship summary...");

            String result0 = RelationshipSummaryProcessor.generateSummaries(context0);

            System.out.println(result0);

            scanner0.close();
            System.exit(0);
        }

        // ============================================================
        // 15. INVESTOR BRIEF
        // ============================================================

        else if (prompt0.equalsIgnoreCase("investor brief") || prompt0.equals("15"))
        {
            SessionContext context0 = loginFromTerminal(scanner0);

            if (context0 == null)
            {
                scanner0.close();
                System.exit(0);
            }

            System.out.print("Number of investors to brief (default 10): ");
            int maxRows0 = parseIntOrDefault(scanner0.nextLine().trim(), 10);

            System.out.println();
            System.out.println("Starting investor brief...");

            String result0 = InvestorBriefJsonProcessor.generateBriefs(context0, maxRows0);

            System.out.println(result0);

            scanner0.close();
            System.exit(0);
        }

        // ============================================================
        // 16. INVESTOR BRIEF PDF (single person)
        //
        // Collects contact details, then delegates the whole resolve ->
        // confirm/select -> render workflow to InvestorBriefClient. AgentMain
        // only gathers input here; no brief logic lives in this branch.
        // ============================================================

        else if (prompt0.equalsIgnoreCase("investor brief pdf") || prompt0.equals("16"))
        {
            SessionContext context0 = loginFromTerminal(scanner0);

            if (context0 == null)
            {
                scanner0.close();
                System.exit(0);
            }

            System.out.print("First name (blank to skip): ");
            String firstName0 = scanner0.nextLine().trim();

            System.out.print("Last name (blank to skip): ");
            String lastName0 = scanner0.nextLine().trim();

            System.out.print("Fund name (blank to skip): ");
            String fundName0 = scanner0.nextLine().trim();

            System.out.print("Email (blank to skip): ");
            String email0 = scanner0.nextLine().trim();

            System.out.println();

            String result0 = InvestorBriefClient.run(
                context0, firstName0, lastName0, fundName0, email0,
                InvestorBriefClient.consolePrompter(scanner0));

            System.out.println(result0);

            scanner0.close();
            System.exit(0);
        }

        // ============================================================
        // 12. NATURAL LANGUAGE SHEET REQUEST
        //
        // Keeps your old OpenAI tool-call spreadsheet agent.
        // ============================================================

        if (prompt0.equalsIgnoreCase("natural language sheet request") || prompt0.equals("12"))
        {
            System.out.println("Enter your spreadsheet request:");
            prompt0 = scanner0.nextLine();
        }

        //runNaturalLanguageSheetAgent(scanner0, prompt0);
    }

    // ============================================================
    // LOGIN HELPER
    // ============================================================

    private static SessionContext loginFromTerminal(Scanner scanner0) throws Exception
    {
        System.out.print("Login email: ");
        String email0 = scanner0.nextLine().trim();

        SessionContext context0 = CRMRegistry.login(email0);

        if (context0 == null)
        {
            System.out.println("Login failed.");
            return null;
        }

        System.out.println("Login successful.");
        context0.printSummary();

        return context0;
    }

    // ============================================================
    // OLD NATURAL LANGUAGE TOOL AGENT
    // ============================================================

    private static void runNaturalLanguageSheetAgent(
        Scanner scanner0,
        String prompt0) throws Exception
    {
        String runningPrompt0 =
            //SheetRegistry.buildSheetAccessPrompt()
             "\n"
            + "Headers are located in the first row of the spreadsheet tab you have access to. "
            + "There will be no more than 13 columns. "
            + "Orient yourself by first reading the header range, usually A1:M1. "
            + "When you update a value, make sure the result value matches the user's requested value. "
            + "After an update, if needed, use another function call to verify or correct mistakes. "
            + "For requests with conditions such as, but not limited to, 'if', 'only if', 'unless', or 'otherwise', "
            + "you should first read the relevant value before making any update. "
            + "Do not perform a conditional update unless the condition has been checked with a read function call. "
            + "For simple direct updates with no condition, an extra verification read is allowed but not required. "
            + "When the user asks to extract data from rows, first read the relevant source columns. "
            + "When writing multiple related outputs, use update_range_matrix to write them in one call. "
            + prompt0;

        int maxSteps0 = 8;

        String lastToolName0 = null;
        String lastArgumentsString0 = null;

        for (int stepIndex0 = 1; stepIndex0 <= maxSteps0; stepIndex0++)
        {
            System.out.println();
            System.out.println("----- STEP " + stepIndex0 + " -----");

            JSONObject response0 = OpenAIClient.getToolCall(runningPrompt0);

            JSONObject errorObject0 = response0.optJSONObject("error");

            if (errorObject0 != null)
            {
                System.out.println("OpenAI API Error:");
                System.out.println(response0.toString(2));
                return;
            }

            if (!response0.has("output"))
            {
                System.out.println("No output field found in response:");
                System.out.println(response0.toString(2));
                return;
            }

            JSONArray outputArray0 = response0.getJSONArray("output");

            boolean functionCallFound0 = false;

            for (int outputIndex0 = 0; outputIndex0 < outputArray0.length(); outputIndex0++)
            {
                JSONObject outputItem0 = outputArray0.getJSONObject(outputIndex0);

                String outputType0 = outputItem0.getString("type");

                if (outputType0.equals("function_call"))
                {
                    functionCallFound0 = true;

                    String toolName0 = outputItem0.getString("name");
                    String argumentsString0 = outputItem0.getString("arguments");

                    JSONObject argumentsObject0 = new JSONObject(argumentsString0);

                    String sheetName0 = argumentsObject0.optString("sheetName", "");
                    String tabName0 = argumentsObject0.optString("tabName", "");

                    SheetRef sheetRef0 = SheetRegistry.getSheetByName(sheetName0);

                    if (sheetRef0 == null)
                    {
                        System.out.println("ERROR: Unknown sheet name: " + sheetName0);
                        return;
                    }

                    if (!sheetRef0.hasTab(tabName0))
                    {
                        System.out.println("ERROR: Invalid tab name: " + tabName0 + " for sheet " + sheetName0);
                        return;
                    }

                    SheetCall sheetCall0 = new SheetCall();
                    sheetCall0.sheetName0 = sheetName0;
                    sheetCall0.tabName0 = tabName0;
                    sheetCall0.spreadsheetId0 = sheetRef0.spreadsheetId0;

                    ToolSpec toolSpec0 = ToolRegistry.getToolByName(toolName0);

                    System.out.println();
                    System.out.println("FUNCTION CALL FOUND: true");
                    System.out.println("finished: false");
                    System.out.println("TOOL NAME: " + toolName0);

                    for (String key0 : argumentsObject0.keySet())
                    {
                        Object value0 = argumentsObject0.isNull(key0) ? null : argumentsObject0.get(key0);
                        System.out.println(key0 + ": " + value0);
                    }

                    System.out.println();

                    if (toolSpec0 == null)
                    {
                        System.out.println("ERROR: Tool returned by model is not in registry: " + toolName0);
                        return;
                    }

                    String currentArgumentsString0 = buildArgumentFingerprint(
                        toolSpec0,
                        argumentsObject0
                    );

                    if (toolName0.equals(lastToolName0) &&
                        currentArgumentsString0.equals(lastArgumentsString0))
                    {
                        System.out.println("ERROR: Repeated tool call detected. Stopping loop.");
                        System.exit(1);
                    }

                    ToolCall toolCall0 = new ToolCall();
                    toolCall0.toolName = toolName0;

                    mapArgumentsIntoCall(toolCall0, toolSpec0, argumentsObject0);

                    String result0 = ToolDispatcher.dispatch(toolCall0, sheetCall0);

                    System.out.println("DISPATCH RESULT: " + result0);

                    lastToolName0 = toolName0;
                    lastArgumentsString0 = currentArgumentsString0;

                    runningPrompt0 =
                        runningPrompt0
                        + "\n\nThe previous tool call was:"
                        + "\nTool name: " + toolName0
                        + "\nSheet name: " + sheetCall0.sheetName0
                        + "\nTab name: " + sheetCall0.tabName0
                        + "\nArguments: " + argumentsObject0.toString()
                        + "\nTool result: " + result0
                        + "\nCheck whether the spreadsheet now matches the user's request exactly."
                        + "\nIf a value was written incorrectly, call another function to fix it."
                        + "\nIf the user's full request is complete, do not call a function."
                        + "\nIf more spreadsheet actions are needed, call the next function.";

                    break;
                }
            }

            if (!functionCallFound0)
            {
                System.out.println("FUNCTION CALL FOUND: false");
                System.out.println("finished: true");
                scanner0.close();
                System.exit(0);
            }
        }

        System.out.println("ERROR: Max step limit reached.");
    }

    // ============================================================
    // ARGUMENT MAPPING FOR TOOL CALLS
    // ============================================================

    private static void mapArgumentsIntoCall(
        ToolCall toolCall0,
        ToolSpec toolSpec0,
        JSONObject argumentsObject0)
    {
        int strIndex0 = 1;
        int intIndex0 = 11;
        int doubIndex0 = 21;

        for (ToolArgSpec toolArgSpec0 : toolSpec0.args)
        {
            Object rawValue0 =
                argumentsObject0.has(toolArgSpec0.name) && !argumentsObject0.isNull(toolArgSpec0.name)
                    ? argumentsObject0.get(toolArgSpec0.name)
                    : null;

            String argType0 = toolArgSpec0.type;

            if (argType0.equals("string"))
            {
                String value0 = rawValue0 == null ? null : rawValue0.toString();

                if (strIndex0 == 1) toolCall0.arg1 = value0;
                else if (strIndex0 == 2) toolCall0.arg2 = value0;
                else if (strIndex0 == 3) toolCall0.arg3 = value0;
                else if (strIndex0 == 4) toolCall0.arg4 = value0;
                else if (strIndex0 == 5) toolCall0.arg5 = value0;
                else if (strIndex0 == 6) toolCall0.arg6 = value0;
                else if (strIndex0 == 7) toolCall0.arg7 = value0;
                else if (strIndex0 == 8) toolCall0.arg8 = value0;
                else if (strIndex0 == 9) toolCall0.arg9 = value0;
                else if (strIndex0 == 10) toolCall0.arg10 = value0;

                strIndex0++;
            }
            else if (argType0.equals("integer"))
            {
                Integer value0 = rawValue0 == null ? null : ((Number) rawValue0).intValue();

                if (intIndex0 == 11) toolCall0.arg11 = value0;
                else if (intIndex0 == 12) toolCall0.arg12 = value0;
                else if (intIndex0 == 13) toolCall0.arg13 = value0;
                else if (intIndex0 == 14) toolCall0.arg14 = value0;
                else if (intIndex0 == 15) toolCall0.arg15 = value0;
                else if (intIndex0 == 16) toolCall0.arg16 = value0;
                else if (intIndex0 == 17) toolCall0.arg17 = value0;
                else if (intIndex0 == 18) toolCall0.arg18 = value0;
                else if (intIndex0 == 19) toolCall0.arg19 = value0;
                else if (intIndex0 == 20) toolCall0.arg20 = value0;

                intIndex0++;
            }
            else if (argType0.equals("double"))
            {
                Double value0 = rawValue0 == null ? null : ((Number) rawValue0).doubleValue();

                if (doubIndex0 == 21) toolCall0.arg21 = value0;
                else if (doubIndex0 == 22) toolCall0.arg22 = value0;
                else if (doubIndex0 == 23) toolCall0.arg23 = value0;
                else if (doubIndex0 == 24) toolCall0.arg24 = value0;
                else if (doubIndex0 == 25) toolCall0.arg25 = value0;

                doubIndex0++;
            }
        }
    }

    private static String buildArgumentFingerprint(
        ToolSpec toolSpec0,
        JSONObject argumentsObject0)
    {
        StringBuilder fingerprint0 = new StringBuilder();

        for (ToolArgSpec argSpec0 : toolSpec0.args)
        {
            Object rawValue0 =
                argumentsObject0.has(argSpec0.name) && !argumentsObject0.isNull(argSpec0.name)
                    ? argumentsObject0.get(argSpec0.name)
                    : null;

            fingerprint0
                .append(argSpec0.name)
                .append("=")
                .append(rawValue0 == null ? "null" : rawValue0.toString())
                .append("|");
        }

        return fingerprint0.toString();
    }

    // ============================================================
    // SIMPLE PARSING HELPERS
    // ============================================================

    private static ArrayList<String> parsePipeSeparatedList(String value0)
    {
        ArrayList<String> list0 = new ArrayList<>();

        if (value0 == null || value0.trim().equals(""))
        {
            return list0;
        }

        String[] split0 = value0.split("\\|");

        for (int i = 0; i < split0.length; i++)
        {
            String item0 = split0[i].trim();

            if (!item0.equals(""))
            {
                list0.add(item0);
            }
        }

        return list0;
    }

    private static String[] parsePipeSeparatedArray(String value0)
    {
        ArrayList<String> list0 = parsePipeSeparatedList(value0);

        String[] array0 = new String[list0.size()];

        for (int i = 0; i < list0.size(); i++)
        {
            array0[i] = list0.get(i);
        }

        return array0;
    }

    private static int parseIntOrDefault(
        String value0,
        int defaultValue0)
    {
        try
        {
            if (value0 == null || value0.trim().equals(""))
            {
                return defaultValue0;
            }

            return Integer.parseInt(value0.trim());
        }
        catch (Exception exception0)
        {
            return defaultValue0;
        }
    }

    private static boolean parseBooleanOrDefault(
        String value0,
        boolean defaultValue0)
    {
        if (value0 == null || value0.trim().equals(""))
        {
            return defaultValue0;
        }

        String normalized0 = value0.trim().toLowerCase();

        if (normalized0.equals("true")
            || normalized0.equals("yes")
            || normalized0.equals("y"))
        {
            return true;
        }

        if (normalized0.equals("false")
            || normalized0.equals("no")
            || normalized0.equals("n"))
        {
            return false;
        }

        return defaultValue0;
    }


    private static String buildClientProfileJson(
        String sectorTags0,
        String microsectorTags0,
        String geography0,
        String investmentThesis0)
    {
        JSONObject object0 = new JSONObject();
        object0.put("client_sector_tags", sectorTags0 == null ? "" : sectorTags0);
        object0.put("client_microsector_tags", microsectorTags0 == null ? "" : microsectorTags0);
        object0.put("client_geography", geography0 == null ? "" : geography0);
        object0.put("client_investment_thesis", investmentThesis0 == null ? "" : investmentThesis0);
        return object0.toString();
    }

}