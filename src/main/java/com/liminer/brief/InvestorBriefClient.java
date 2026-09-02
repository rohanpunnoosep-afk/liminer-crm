package com.liminer.brief;

import com.liminer.core.SessionContext;
import com.liminer.sheets.DeterministicRowResolver;
import com.liminer.sheets.SheetsApp;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;

/*
 * InvestorBriefClient — end-to-end "give me a brief for this person" workflow, kept
 * deliberately independent of AgentMain so any front end can drive it.
 *
 * Flow:
 *   1. Take structured contact details (first name, last name, fund name, email).
 *   2. Resolve them to a CRM row via DeterministicRowResolver (no LLM, no prompt).
 *   3. If a single confident MATCH: confirm with the user (Accept/Deny).
 *      If NOT resolved (multiple tied, or only weak near-misses): show a numbered
 *      candidate list and let the user pick the right one.
 *   4. On the chosen row, read the stored Investor Brief JSON and render a PDF via
 *      InvestorBriefPdfRenderer.
 *
 * Abstraction seams (so a natural-language tool can be added later WITHOUT touching this
 * class or AgentMain):
 *   - ContactQuery is the unit of input. A future NL tool implements ContactQueryProvider
 *     (userRequest -> ContactQuery) and hands the result to run(...). This class never
 *     parses prose; it only consumes the four structured fields.
 *   - Prompter abstracts all user I/O. AgentMain passes a console-backed Prompter; an NL
 *     or test driver can pass its own (e.g. auto-accepting). Nothing here touches System.in
 *     directly except the provided consolePrompter() convenience factory.
 *
 * This class only reads from the sheet (one cell for the resolved row's brief JSON); it
 * does not write. The resolver does the column-by-column identity reads.
 */
public class InvestorBriefClient
{
    private static final int MAX_COLUMNS = 200;

    // -----------------------------------------------------------------------
    // Input abstraction (the NL tool will later produce a ContactQuery)
    // -----------------------------------------------------------------------

    /** The structured contact details the resolver needs. Blank fields are ignored. */
    public static class ContactQuery
    {
        public final String firstName;
        public final String lastName;
        public final String fundName;
        public final String email;

        public ContactQuery(String firstName, String lastName, String fundName, String email)
        {
            this.firstName = safe(firstName);
            this.lastName  = safe(lastName);
            this.fundName  = safe(fundName);
            this.email     = safe(email);
        }

        public boolean isEmpty()
        {
            return firstName.trim().isEmpty() && lastName.trim().isEmpty()
                && fundName.trim().isEmpty() && email.trim().isEmpty();
        }
    }

    /**
     * Seam for a future natural-language front end: parse a free-form user request into a
     * ContactQuery (first/last/fund/email when available). NOT implemented yet — the
     * deterministic workflow takes a ContactQuery directly. When built, the NL tool's
     * output flows straight into run(ctx, query, prompter).
     */
    public interface ContactQueryProvider
    {
        ContactQuery fromRequest(String userRequest) throws Exception;
    }

    // -----------------------------------------------------------------------
    // I/O abstraction (so we do not depend on AgentMain)
    // -----------------------------------------------------------------------

    /** All user interaction goes through this so the workflow is front-end agnostic. */
    public interface Prompter
    {
        /** Print the message and return the user's typed line (trimmed). */
        String ask(String message);
        /** Print an informational message. */
        void show(String message);
    }

    /** Console-backed Prompter for terminal front ends (e.g. AgentMain). */
    public static Prompter consolePrompter(final Scanner scanner)
    {
        return new Prompter()
        {
            @Override public String ask(String message) { System.out.print(message); return scanner.nextLine().trim(); }
            @Override public void show(String message) { System.out.println(message); }
        };
    }

    /**
     * Non-interactive Prompter for headless front ends (web app workflows, tests). NEVER
     * blocks on input. Every show()/ask() message is appended to transcript so the caller
     * can see what was decided.
     *
     * Policy: the only "yes/no" prompt in this class is the single-confident-match confirm
     * ("Accept (1). Deny (2)."), which this accepts automatically. Every other prompt (the
     * numbered candidate-selection prompt, reached only when there is NOT one confident
     * match) gets "0" — i.e. refuse to guess, never auto-select an ambiguous candidate.
     */
    public static Prompter autoPrompter(final StringBuilder transcript)
    {
        return new Prompter()
        {
            @Override public String ask(String message)
            {
                if (transcript != null) transcript.append(message).append('\n');
                return (message != null && message.contains("Accept (1)")) ? "1" : "0";
            }
            @Override public void show(String message)
            {
                if (transcript != null) transcript.append(message).append('\n');
            }
        };
    }

    // -----------------------------------------------------------------------
    // Public entry points
    // -----------------------------------------------------------------------

    /** Convenience overload taking the four fields directly. */
    public static String run(
        SessionContext context, String firstName, String lastName,
        String fundName, String email, Prompter prompter) throws Exception
    {
        return run(context, new ContactQuery(firstName, lastName, fundName, email), prompter);
    }

    /**
     * Resolve the query to a row, confirm/select with the user, and render the brief PDF.
     * Returns a human-readable result string (the success form is
     * "Match Found: First, Last, Fund, Email. InvestorBrief generated.").
     */
    public static String run(SessionContext context, ContactQuery query, Prompter prompter) throws Exception
    {
        if (context == null || context.config == null) return "ERROR: Missing session context or config.";
        if (query == null || query.isEmpty())          return "ERROR: No contact details provided.";

        DeterministicRowResolver.ResolverResult result = DeterministicRowResolver.resolve(
            context, query.firstName, query.lastName, query.fundName, query.email);

        return runResolved(context, result, prompter);
    }

    /**
     * Same as run(), but starting from an already-computed resolver outcome. Lets a test
     * drive the confirm/select policy with a hand-built ResolverResult, without ever
     * calling DeterministicRowResolver.resolve() (and therefore without touching Sheets).
     */
    static String runResolved(
        SessionContext context, DeterministicRowResolver.ResolverResult result, Prompter prompter) throws Exception
    {
        switch (result.outcome)
        {
            case MATCH:
                return handleMatch(context, result.match, prompter);

            case AMBIGUOUS:
            case NONE:
                return handleSelection(context, result.candidates, prompter);

            case ERROR:
            default:
                return "ERROR: " + safe(result.message);
        }
    }

    /**
     * Headless entry point for the "investor-brief-pdf" workflow. Never blocks and never
     * auto-selects among ambiguous candidates:
     *   - exactly one confident match -> generates the brief automatically
     *   - zero matches                -> "ERROR: No matching contact found for <query>."
     *   - two or more candidates      -> lists them and asks the caller to supply more
     *                                    detail (e.g. email); nothing is rendered
     * The returned string always includes the prompter transcript so an operator can see
     * what was decided.
     */
    public static String runAuto(SessionContext context, ContactQuery query) throws Exception
    {
        if (context == null || context.config == null) return "ERROR: Missing session context or config.";
        if (query == null || query.isEmpty())          return "ERROR: No contact details provided.";

        DeterministicRowResolver.ResolverResult result = DeterministicRowResolver.resolve(
            context, query.firstName, query.lastName, query.fundName, query.email);

        return runAutoResolved(context, query, result);
    }

    /** Same as runAuto(), but starting from an already-computed resolver outcome (test seam). */
    static String runAutoResolved(
        SessionContext context, ContactQuery query, DeterministicRowResolver.ResolverResult result) throws Exception
    {
        StringBuilder transcript = new StringBuilder();
        String result0 = runResolved(context, result, autoPrompter(transcript));

        if ("No matching contact found.".equals(result0))
        {
            result0 = "ERROR: No matching contact found for " + describeQuery(query) + ".";
        }
        else if (result0 != null && result0.startsWith("Cancelled. No contact selected."))
        {
            result0 = "AMBIGUOUS: Multiple possible contacts found for " + describeQuery(query)
                + ". Supply more detail (e.g. email) to disambiguate.";
        }

        if (transcript.length() > 0)
        {
            result0 = result0 + "\n\n--- Transcript ---\n" + transcript;
        }
        return result0;
    }

    private static String describeQuery(ContactQuery q)
    {
        return (safe(q.firstName) + " " + safe(q.lastName) + " " + safe(q.fundName) + " " + safe(q.email)).trim();
    }

    // -----------------------------------------------------------------------
    // Single confident match -> Accept/Deny
    // -----------------------------------------------------------------------

    private static String handleMatch(
        SessionContext context, DeterministicRowResolver.Candidate match, Prompter prompter) throws Exception
    {
        String header = "Match Found: " + describe(match);
        String choice = prompter.ask(header + ". Accept (1). Deny (2). ");

        if (!"1".equals(choice))
        {
            return header + ". Cancelled by user.";
        }
        return generateForCandidate(context, match, prompter);
    }

    // -----------------------------------------------------------------------
    // Not resolved -> numbered candidate list
    // -----------------------------------------------------------------------

    private static String handleSelection(
        SessionContext context, List<DeterministicRowResolver.Candidate> candidates, Prompter prompter) throws Exception
    {
        if (candidates == null || candidates.isEmpty())
        {
            return "No matching contact found.";
        }

        prompter.show("No single confident match. Possible contacts:");
        for (int i = 0; i < candidates.size(); i++)
        {
            prompter.show("  " + (i + 1) + ". " + describe(candidates.get(i)));
        }

        String choice = prompter.ask("Type the number of the correct contact (0 to cancel): ");
        int idx = parseIntOrDefault(choice, 0);
        if (idx < 1 || idx > candidates.size())
        {
            return "Cancelled. No contact selected.";
        }
        return generateForCandidate(context, candidates.get(idx - 1), prompter);
    }

    // -----------------------------------------------------------------------
    // Render the brief PDF for a resolved candidate
    // -----------------------------------------------------------------------

    /** Test seam (pattern used elsewhere, e.g. SheetsApp.retrySleeper): swap this out to
     *  avoid Sheets/PDF I/O in headless tests. Defaults to the real implementation. */
    public interface BriefGenerator
    {
        String generate(SessionContext context, DeterministicRowResolver.Candidate cand, Prompter prompter) throws Exception;
    }

    public static BriefGenerator briefGenerator = InvestorBriefClient::defaultGenerateForCandidate;

    private static String generateForCandidate(
        SessionContext context, DeterministicRowResolver.Candidate cand, Prompter prompter) throws Exception
    {
        return briefGenerator.generate(context, cand, prompter);
    }

    private static String defaultGenerateForCandidate(
        SessionContext context, DeterministicRowResolver.Candidate cand, Prompter prompter) throws Exception
    {
        String storedJson = fetchStoredBriefJson(context, cand.sheetRow);
        if (storedJson == null || storedJson.trim().isEmpty())
        {
            return "Match Found: " + describe(cand)
                + ". No investor brief has been generated for this contact yet. "
                + "Run 'investor brief' first, then retry.";
        }

        try
        {
            Path out = InvestorBriefPdfRenderer.renderFromStoredJson(storedJson);
            prompter.show("PDF written to: " + out.toAbsolutePath());
        }
        catch (IllegalArgumentException e)
        {
            return "Match Found: " + describe(cand)
                + ". Stored brief could not be rendered: " + e.getMessage();
        }

        return "Match Found: " + describe(cand) + ". InvestorBrief generated.";
    }

    /** Read just the one Investor Brief JSON cell for the resolved row (column-by-column). */
    private static String fetchStoredBriefJson(SessionContext context, int sheetRow) throws Exception
    {
        String spreadsheetId = context.config.spreadsheetId;
        String tabName       = context.config.mainTabName;
        int    headerRow     = context.config.mainTabHeaderRow;

        HashMap<String, Integer> headerMap = SheetsApp.buildHeaderMap(
            spreadsheetId, tabName, headerRow, MAX_COLUMNS);

        String header = context.config.getCol("mainTabInvestorBriefJsonCol");
        int col = header != null ? SheetsApp.findColumnInHeaderMap(headerMap, header) : -1;
        if (col < 1) return "";

        String[][] cell = SheetsApp.readRangeMatrix(spreadsheetId, tabName, sheetRow, col, sheetRow, col);
        if (cell == null || cell.length == 0 || cell[0] == null || cell[0].length == 0) return "";
        return cell[0][0] == null ? "" : cell[0][0];
    }

    // -----------------------------------------------------------------------
    // Formatting / utilities
    // -----------------------------------------------------------------------

    /** "First Name, Last Name, Fund Name, Email" — blanks shown as empty between commas. */
    private static String describe(DeterministicRowResolver.Candidate c)
    {
        return safe(c.firstName) + ", " + safe(c.lastName) + ", "
             + safe(c.fundName) + ", " + safe(c.email);
    }

    private static int parseIntOrDefault(String s, int def)
    {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
