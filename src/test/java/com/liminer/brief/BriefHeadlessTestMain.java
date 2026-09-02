package com.liminer.brief;

import com.liminer.core.CRMSchemaConfig;
import com.liminer.core.SessionContext;
import com.liminer.sheets.DeterministicRowResolver;
import com.liminer.web.WorkflowRegistry;

import java.util.ArrayList;
import java.util.List;

/*
 * Offline verification of the headless investor-brief-pdf seam (task 0160). Never
 * touches Google Sheets, OpenAI, or the real filesystem output dir:
 *   - DeterministicRowResolver.resolve() is never called; hand-built ResolverResults
 *     are fed straight into InvestorBriefClient.runAutoResolved() instead.
 *   - InvestorBriefClient.briefGenerator is swapped for a stub so the MATCH path never
 *     reaches Sheets/PDF rendering.
 */
public class BriefHeadlessTestMain
{
    private static int failures = 0;

    public static void main(String[] args) throws Exception
    {
        InvestorBriefClient.briefGenerator = (context, cand, prompter) ->
        {
            prompter.show("(stub) PDF written to: /tmp/stub-brief.pdf");
            return "Match Found: " + cand.firstName + ", " + cand.lastName + ", "
                + cand.fundName + ", " + cand.email + ". InvestorBrief generated.";
        };

        SessionContext context = new SessionContext(null, new CRMSchemaConfig("cfg1", "user1", "Test Fund", "sheet123"));
        InvestorBriefClient.ContactQuery query =
            new InvestorBriefClient.ContactQuery("Jane", "Doe", "Acme Fund", "jane@acme.com");

        // --- single confident match: proceeds automatically, never blocks ---
        DeterministicRowResolver.Candidate match = candidate(5, 1, "Jane", "Doe", "Acme Fund", "jane@acme.com");
        DeterministicRowResolver.ResolverResult matchResult = resultOf(
            DeterministicRowResolver.Outcome.MATCH, match, null, "Matched row 5 (score 140).");
        String matchOut = InvestorBriefClient.runAutoResolved(context, query, matchResult);
        check("single match succeeds", matchOut.startsWith("Match Found:") && matchOut.contains("InvestorBrief generated."));
        check("single match includes transcript", matchOut.contains("--- Transcript ---"));

        // --- zero matches: explicit error, no guessing ---
        DeterministicRowResolver.ResolverResult noneResult = resultOf(
            DeterministicRowResolver.Outcome.NONE, null, new ArrayList<>(), "No row matched.");
        String noneOut = InvestorBriefClient.runAutoResolved(context, query, noneResult);
        check("zero match returns ERROR", noneOut.startsWith("ERROR:"));

        // --- multiple candidates: never auto-selected, names the candidates ---
        List<DeterministicRowResolver.Candidate> candidates = new ArrayList<>();
        candidates.add(candidate(5, 1, "Jane", "Doe", "Acme Fund", "jane@acme.com"));
        candidates.add(candidate(9, 1, "Jane", "Doe", "Acme Ventures", "jane.doe@acme.com"));
        DeterministicRowResolver.ResolverResult ambiguousResult = resultOf(
            DeterministicRowResolver.Outcome.AMBIGUOUS, null, candidates, "2 rows tied at score 60; disambiguation needed.");
        String ambiguousOut = InvestorBriefClient.runAutoResolved(context, query, ambiguousResult);
        check("ambiguous does not start with Match Found", !ambiguousOut.startsWith("Match Found:"));
        check("ambiguous names candidate 1 (Acme Fund)", ambiguousOut.contains("Acme Fund"));
        check("ambiguous names candidate 2 (Acme Ventures)", ambiguousOut.contains("Acme Ventures"));
        check("ambiguous does not render a brief", !ambiguousOut.contains("InvestorBrief generated."));

        // --- all-blank query: rejected before resolution ever runs ---
        InvestorBriefClient.ContactQuery blankQuery = new InvestorBriefClient.ContactQuery("", "", "", "");
        String blankOut = InvestorBriefClient.runAuto(context, blankQuery);
        check("blank query returns ERROR", blankOut.startsWith("ERROR:"));

        // --- registry reports the workflow as available ---
        List<WorkflowRegistry.WorkflowInfo> workflows = WorkflowRegistry.buildProductionRegistry().list();
        boolean found = false, available = false;
        for (WorkflowRegistry.WorkflowInfo w : workflows)
        {
            if ("investor-brief-pdf".equals(w.id)) { found = true; available = w.available; }
        }
        check("investor-brief-pdf is registered", found);
        check("investor-brief-pdf is available", available);

        if (failures == 0)
        {
            System.out.println("BRIEF_HEADLESS_OK");
        }
        else
        {
            System.out.println(failures + " check(s) failed.");
            System.exit(1);
        }
    }

    private static DeterministicRowResolver.Candidate candidate(
        int sheetRow, int slot, String first, String last, String fund, String email)
    {
        DeterministicRowResolver.Candidate c = new DeterministicRowResolver.Candidate();
        c.sheetRow = sheetRow;
        c.localIdx = sheetRow - 2;
        c.contactSlot = slot;
        c.score = 100;
        c.firstName = first;
        c.lastName = last;
        c.fundName = fund;
        c.email = email;
        return c;
    }

    private static DeterministicRowResolver.ResolverResult resultOf(
        DeterministicRowResolver.Outcome outcome, DeterministicRowResolver.Candidate match,
        List<DeterministicRowResolver.Candidate> candidates, String message) throws Exception
    {
        return new DeterministicRowResolver.ResolverResult(outcome, match, candidates, message);
    }

    private static void check(String label, boolean condition)
    {
        if (condition)
        {
            System.out.println("PASS: " + label);
        }
        else
        {
            System.out.println("FAIL: " + label);
            failures++;
        }
    }
}
