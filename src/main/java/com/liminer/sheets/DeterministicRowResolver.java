package com.liminer.sheets;

import com.liminer.core.SessionContext;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/*
 * DeterministicRowResolver — v1 single-person row lookup for the investor-brief PDF path
 * (ExtraDocuments/pdfinfraneeds.md step #4). Given structured parameters (first name,
 * last name, fund name, email) it resolves the request to a single CRM main-tab row so a
 * caller can fetch or generate that row's Investor Brief JSON and render it.
 *
 * This is deliberately deterministic: NO LLM and NO free-form prompt. A blank ("") or null
 * field is treated as "not provided" and never counted against a candidate. A natural-
 * language front end will be added later BEHIND this resolver (it will parse a prompt into
 * these same fields and/or use this resolver's ranked candidates as a pre-filter), so the
 * public surface here is intentionally a stable seam: resolve(...) -> ResolverResult.
 *
 * Matching is precision-biased because a false positive (a confident brief for the WRONG
 * LP) is far worse than a false negative (no match -> the caller re-prompts). Concretely:
 *   - Diacritics/case/whitespace are handled by Unicode normalization (NFC -> strip
 *     combining marks -> lowercase -> collapse spaces), so "Merieux" == "Mérieux" with no
 *     model call.
 *   - Fund truncation ("Hummingbird" for "Hummingbird Impact") matches via token-subset /
 *     prefix, and reordered names (family-name-first) match via order-independent tokens.
 *   - Common nicknames (Nick/Nicholas) match via a small static table plus prefix/edit.
 *   - A candidate qualifies ONLY on a strong identity signal (exact email, OR a last-name
 *     match, OR a fund match). A shared first name or a single common fund word alone is
 *     never enough to select.
 *
 * Both contacts on a row (Contact 1 and Contact 2) are indexed as separate candidates, but
 * since the stored brief is per ROW, winners that share a sheet row collapse to one MATCH;
 * winners spanning different rows are reported AMBIGUOUS for the caller to disambiguate.
 *
 * Spreadsheet Rules: build the header map, then read each contact column separately (no
 * rectangles). This resolver only reads.
 */
public class DeterministicRowResolver
{
    private static final int MAX_CRM_ROWS = 500;
    private static final int MAX_COLUMNS  = 200;

    // Contact identity columns, read one column at a time (no rectangles).
    private static final String K_C1_FIRST = "mainTabContact1FirstNameCol";
    private static final String K_C1_LAST  = "mainTabContact1LastNameCol";
    private static final String K_C1_EMAIL = "mainTabContact1EmailCol";
    private static final String K_C2_FIRST = "mainTabContact2FirstNameCol";
    private static final String K_C2_LAST  = "mainTabContact2LastNameCol";
    private static final String K_C2_EMAIL = "mainTabContact2EmailCol";
    private static final String K_FUND     = "mainTabFundNameCol";

    // Per-signal score weights. Tuned so any single strong signal (email/last/fund)
    // outranks any pile of weak ones, keeping the resolver precision-biased.
    private static final int SC_EMAIL_EXACT   = 100;
    private static final int SC_LAST_EXACT    = 40;
    private static final int SC_LAST_TYPO     = 25;   // edit distance <= 1
    private static final int SC_FUND_EXACT    = 40;
    private static final int SC_FUND_SUBSET   = 30;   // query tokens subset of / prefix of candidate
    private static final int SC_FUND_OVERLAP  = 10;   // shares a non-trivial token
    private static final int SC_FIRST_EXACT   = 20;
    private static final int SC_FIRST_NICK    = 15;   // nickname-equivalent
    private static final int SC_FIRST_PREFIX  = 12;   // one is a prefix of the other (>=3 chars)
    private static final int SC_FIRST_TYPO    = 10;   // edit distance <= 1

    // Small, bidirectional nickname table. Deliberately short; extend as real CRM data
    // surfaces gaps. Keys and values are compared after normalization.
    private static final String[][] NICKNAMES = {
        {"nick", "nicholas"}, {"bill", "william"}, {"will", "william"}, {"liam", "william"},
        {"bob", "robert"}, {"rob", "robert"}, {"bobby", "robert"},
        {"dick", "richard"}, {"rick", "richard"}, {"rich", "richard"},
        {"jim", "james"}, {"jamie", "james"}, {"jimmy", "james"},
        {"joe", "joseph"}, {"tom", "thomas"}, {"tommy", "thomas"},
        {"mike", "michael"}, {"mick", "michael"}, {"tony", "anthony"},
        {"chris", "christopher"}, {"steve", "steven"}, {"stevphen", "stephen"},
        {"dave", "david"}, {"dan", "daniel"}, {"danny", "daniel"},
        {"matt", "matthew"}, {"andy", "andrew"}, {"drew", "andrew"},
        {"ed", "edward"}, {"ted", "edward"}, {"ben", "benjamin"},
        {"sam", "samuel"}, {"alex", "alexander"}, {"greg", "gregory"},
        {"kate", "katherine"}, {"katie", "katherine"}, {"kathy", "katherine"},
        {"liz", "elizabeth"}, {"beth", "elizabeth"}, {"betsy", "elizabeth"},
        {"sue", "susan"}, {"suzy", "susan"}, {"peggy", "margaret"}, {"maggie", "margaret"},
        {"meg", "margaret"}, {"jen", "jennifer"}, {"jenny", "jennifer"},
        {"cathy", "catherine"}, {"abby", "abigail"}, {"becky", "rebecca"},
        {"tina", "christina"}, {"chris", "christina"}, {"vicky", "victoria"},
        {"trish", "patricia"}, {"pat", "patricia"}, {"patty", "patricia"}
    };

    // Fund tokens too generic to count as a meaningful overlap signal on their own.
    private static final Set<String> FUND_STOPWORDS = new HashSet<>(Arrays.asList(
        "the", "of", "and", "for", "fund", "funds", "capital", "ventures", "venture",
        "partners", "partner", "management", "group", "llc", "lp", "llp", "inc",
        "ltd", "limited", "company", "co", "holdings", "investments", "investment",
        "asset", "assets", "advisors", "advisers", "trust", "foundation", "global",
        "international", "agency", "corporation", "corp"));

    // -----------------------------------------------------------------------
    // Public surface
    // -----------------------------------------------------------------------

    public enum Outcome { MATCH, AMBIGUOUS, NONE, ERROR }

    /** A single scored contact identity located in the sheet. */
    public static class Candidate
    {
        public int    sheetRow;     // 1-based sheet row of the LP record
        public int    localIdx;     // 0-based offset from data start (for caller's column arrays)
        public int    contactSlot;  // 1 or 2 (which contact on the row matched)
        public int    score;
        public String firstName;
        public String lastName;
        public String fundName;
        public String email;
        public List<String> reasons = new ArrayList<>();

        @Override
        public String toString()
        {
            String name = (firstName + " " + lastName).trim();
            if (name.isEmpty()) name = "(no name)";
            String fund = isBlank(fundName) ? "(no fund)" : fundName;
            return name + " — " + fund + " [row " + sheetRow + ", contact " + contactSlot
                + ", score " + score + ", " + String.join("; ", reasons) + "]";
        }
    }

    public static class ResolverResult
    {
        public Outcome         outcome;
        public Candidate       match;       // populated only when outcome == MATCH
        public List<Candidate> candidates;  // ranked; for AMBIGUOUS the tied winners, else best-effort
        public String          message;

        public ResolverResult(Outcome o, Candidate m, List<Candidate> c, String msg)
        {
            this.outcome = o; this.match = m;
            this.candidates = c != null ? c : new ArrayList<>();
            this.message = msg;
        }
    }

    /**
     * Resolve a person to a single CRM row from structured fields. Any field passed as ""
     * or null is ignored (not counted against candidates).
     */
    public static ResolverResult resolve(
        SessionContext context, String firstName, String lastName, String fundName, String email)
    {
        if (context == null || context.config == null)
        {
            return new ResolverResult(Outcome.ERROR, null, null, "Missing session context or config.");
        }

        String qFirst = normName(firstName);
        String qLast  = normName(lastName);
        String qFund  = normName(fundName);
        String qEmail = normEmail(email);
        Set<String> qFundTokens = tokens(qFund);

        if (qFirst.isEmpty() && qLast.isEmpty() && qFund.isEmpty() && qEmail.isEmpty())
        {
            return new ResolverResult(Outcome.NONE, null, null,
                "No search fields provided (first/last/fund/email all blank).");
        }

        final String spreadsheetId = context.config.spreadsheetId;
        final String tabName       = context.config.mainTabName;
        final int    headerRow     = context.config.mainTabHeaderRow;
        final int    dataStartRow  = context.config.mainTabDataStartRow;

        try
        {
            // Step 1: header map.
            HashMap<String, Integer> headerMap = SheetsApp.buildHeaderMap(
                spreadsheetId, tabName, headerRow, MAX_COLUMNS);

            // Step 2: read each identity column separately (no rectangles).
            String[][] c1First = readCol(context, headerMap, spreadsheetId, tabName, dataStartRow, K_C1_FIRST);
            String[][] c1Last  = readCol(context, headerMap, spreadsheetId, tabName, dataStartRow, K_C1_LAST);
            String[][] c1Email = readCol(context, headerMap, spreadsheetId, tabName, dataStartRow, K_C1_EMAIL);
            String[][] c2First = readCol(context, headerMap, spreadsheetId, tabName, dataStartRow, K_C2_FIRST);
            String[][] c2Last  = readCol(context, headerMap, spreadsheetId, tabName, dataStartRow, K_C2_LAST);
            String[][] c2Email = readCol(context, headerMap, spreadsheetId, tabName, dataStartRow, K_C2_EMAIL);
            String[][] fund    = readCol(context, headerMap, spreadsheetId, tabName, dataStartRow, K_FUND);

            int rowCount = maxLen(c1First, c1Last, c1Email, c2First, c2Last, c2Email, fund);

            // Step 3: score every populated contact slot.
            List<Candidate> qualifying = new ArrayList<>();
            List<Candidate> all        = new ArrayList<>();
            for (int i = 0; i < rowCount; i++)
            {
                String fundRaw = cell(fund, i);
                scoreSlot(all, qualifying, dataStartRow, i, 1,
                    cell(c1First, i), cell(c1Last, i), cell(c1Email, i), fundRaw,
                    qFirst, qLast, qEmail, qFund, qFundTokens);
                scoreSlot(all, qualifying, dataStartRow, i, 2,
                    cell(c2First, i), cell(c2Last, i), cell(c2Email, i), fundRaw,
                    qFirst, qLast, qEmail, qFund, qFundTokens);
            }

            return decide(qualifying, all);
        }
        catch (Exception e)
        {
            return new ResolverResult(Outcome.ERROR, null, null,
                "Row resolution failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Scoring
    // -----------------------------------------------------------------------

    private static void scoreSlot(
        List<Candidate> all, List<Candidate> qualifying,
        int dataStartRow, int localIdx, int slot,
        String cFirstRaw, String cLastRaw, String cEmailRaw, String cFundRaw,
        String qFirst, String qLast, String qEmail, String qFund, Set<String> qFundTokens)
    {
        // A slot with no identity at all is not a candidate.
        if (isBlank(cFirstRaw) && isBlank(cLastRaw) && isBlank(cEmailRaw)) return;

        String cFirst = normName(cFirstRaw);
        String cLast  = normName(cLastRaw);
        String cEmail = normEmail(cEmailRaw);
        String cFund  = normName(cFundRaw);

        int score = 0;
        boolean strong = false;   // qualifies only on a strong identity signal
        List<String> reasons = new ArrayList<>();

        // Email — the most unique key.
        if (!qEmail.isEmpty() && !cEmail.isEmpty() && qEmail.equals(cEmail))
        {
            score += SC_EMAIL_EXACT; strong = true; reasons.add("email exact");
        }

        // Last name.
        if (!qLast.isEmpty() && !cLast.isEmpty())
        {
            if (qLast.equals(cLast))            { score += SC_LAST_EXACT; strong = true; reasons.add("last exact"); }
            else if (editWithin(qLast, cLast, 1)) { score += SC_LAST_TYPO; strong = true; reasons.add("last ~typo"); }
        }

        // Fund — token-subset / prefix handles truncation; order-independent.
        if (!qFund.isEmpty() && !cFund.isEmpty())
        {
            if (qFund.equals(cFund))                       { score += SC_FUND_EXACT; strong = true; reasons.add("fund exact"); }
            else if (isFundSubsetOrPrefix(qFund, cFund, qFundTokens)) { score += SC_FUND_SUBSET; strong = true; reasons.add("fund subset/prefix"); }
            else if (sharesMeaningfulToken(qFundTokens, cFund))      { score += SC_FUND_OVERLAP; reasons.add("fund token overlap"); }
        }

        // First name — supporting signal only (order-independent against the last too).
        if (!qFirst.isEmpty() && !cFirst.isEmpty())
        {
            if (qFirst.equals(cFirst))             { score += SC_FIRST_EXACT; reasons.add("first exact"); }
            else if (nicknameEq(qFirst, cFirst))   { score += SC_FIRST_NICK; reasons.add("first nickname"); }
            else if (isPrefix(qFirst, cFirst, 3))  { score += SC_FIRST_PREFIX; reasons.add("first prefix"); }
            else if (editWithin(qFirst, cFirst, 1)) { score += SC_FIRST_TYPO; reasons.add("first ~typo"); }
        }

        if (score <= 0) return;

        Candidate cand = new Candidate();
        cand.sheetRow    = dataStartRow + localIdx;
        cand.localIdx    = localIdx;
        cand.contactSlot = slot;
        cand.score       = score;
        cand.firstName   = safe(cFirstRaw).trim();
        cand.lastName    = safe(cLastRaw).trim();
        cand.fundName    = safe(cFundRaw).trim();
        cand.email       = safe(cEmailRaw).trim();
        cand.reasons     = reasons;

        all.add(cand);
        if (strong) qualifying.add(cand);
    }

    private static ResolverResult decide(List<Candidate> qualifying, List<Candidate> all)
    {
        if (qualifying.isEmpty())
        {
            List<Candidate> nearMisses = topN(sortByScore(all), 5);
            return new ResolverResult(Outcome.NONE, null, nearMisses,
                "No row matched on a strong identity signal (email, last name, or fund).");
        }

        List<Candidate> ranked = sortByScore(qualifying);
        int topScore = ranked.get(0).score;

        List<Candidate> winners = new ArrayList<>();
        for (Candidate c : ranked) { if (c.score == topScore) winners.add(c); }

        // The brief is per ROW: winners on the same sheet row resolve to one match.
        Set<Integer> winnerRows = new HashSet<>();
        for (Candidate c : winners) winnerRows.add(c.sheetRow);

        if (winnerRows.size() == 1)
        {
            return new ResolverResult(Outcome.MATCH, winners.get(0), ranked,
                "Matched row " + winners.get(0).sheetRow + " (score " + topScore + ").");
        }

        return new ResolverResult(Outcome.AMBIGUOUS, null, winners,
            winnerRows.size() + " rows tied at score " + topScore + "; disambiguation needed.");
    }

    // -----------------------------------------------------------------------
    // Matching primitives
    // -----------------------------------------------------------------------

    private static boolean isFundSubsetOrPrefix(String qFund, String cFund, Set<String> qFundTokens)
    {
        // Prefix: "hummingbird" vs "hummingbird impact".
        if (cFund.startsWith(qFund) || qFund.startsWith(cFund)) return true;
        // Token subset: every (meaningful) query token appears in the candidate.
        Set<String> cTokens = tokens(cFund);
        if (qFundTokens.isEmpty()) return false;
        boolean anyMeaningful = false;
        for (String t : qFundTokens)
        {
            if (FUND_STOPWORDS.contains(t)) continue;
            anyMeaningful = true;
            if (!cTokens.contains(t)) return false;
        }
        return anyMeaningful;
    }

    private static boolean sharesMeaningfulToken(Set<String> qFundTokens, String cFund)
    {
        Set<String> cTokens = tokens(cFund);
        for (String t : qFundTokens)
        {
            if (t.length() < 3 || FUND_STOPWORDS.contains(t)) continue;
            if (cTokens.contains(t)) return true;
        }
        return false;
    }

    private static boolean nicknameEq(String a, String b)
    {
        for (String[] pair : NICKNAMES)
        {
            if ((a.equals(pair[0]) && b.equals(pair[1])) || (a.equals(pair[1]) && b.equals(pair[0])))
                return true;
        }
        return false;
    }

    private static boolean isPrefix(String a, String b, int minLen)
    {
        if (a.length() < minLen || b.length() < minLen) return false;
        return a.startsWith(b) || b.startsWith(a);
    }

    /** True when the Levenshtein distance between a and b is <= max (bounded, cheap). */
    private static boolean editWithin(String a, String b, int max)
    {
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > max) return false;
        int[] prev = new int[lb + 1];
        int[] curr = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++)
        {
            curr[0] = i;
            int rowMin = curr[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++)
            {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > max) return false;   // whole row already exceeds budget
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[lb] <= max;
    }

    // -----------------------------------------------------------------------
    // Normalization
    // -----------------------------------------------------------------------

    /** NFC -> strip diacritics -> lowercase -> drop punctuation -> collapse whitespace. */
    private static String normName(String s)
    {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        n = n.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");   // é -> e
        n = n.toLowerCase(Locale.ROOT);
        n = n.replaceAll("[^a-z0-9 ]", " ");                          // O'Brien -> o brien
        n = n.replaceAll("\\s+", " ").trim();
        return n;
    }

    private static String normEmail(String s)
    {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static Set<String> tokens(String normalized)
    {
        Set<String> set = new HashSet<>();
        if (normalized == null || normalized.isEmpty()) return set;
        for (String t : normalized.split(" ")) { if (!t.isEmpty()) set.add(t); }
        return set;
    }

    // -----------------------------------------------------------------------
    // Sheet reads (column-by-column)
    // -----------------------------------------------------------------------

    private static String[][] readCol(
        SessionContext ctx, HashMap<String, Integer> headerMap,
        String spreadsheetId, String tabName, int startRow, String key) throws Exception
    {
        String header = ctx.config.getCol(key);
        int col = header != null ? SheetsApp.findColumnInHeaderMap(headerMap, header) : -1;
        if (col < 1) return new String[0][1];
        return SheetsApp.readRangeMatrix(
            spreadsheetId, tabName, startRow, col, startRow + MAX_CRM_ROWS - 1, col);
    }

    // -----------------------------------------------------------------------
    // Small utilities
    // -----------------------------------------------------------------------

    private static List<Candidate> sortByScore(List<Candidate> in)
    {
        List<Candidate> out = new ArrayList<>(in);
        // Deterministic order: score desc, then row asc, then slot asc.
        out.sort((a, b) ->
        {
            if (a.score != b.score) return Integer.compare(b.score, a.score);
            if (a.sheetRow != b.sheetRow) return Integer.compare(a.sheetRow, b.sheetRow);
            return Integer.compare(a.contactSlot, b.contactSlot);
        });
        return out;
    }

    private static List<Candidate> topN(List<Candidate> in, int n)
    {
        return in.size() <= n ? in : new ArrayList<>(in.subList(0, n));
    }

    @SafeVarargs
    private static int maxLen(String[][]... cols)
    {
        int max = 0;
        for (String[][] c : cols) { if (c != null && c.length > max) max = c.length; }
        return Math.min(max, MAX_CRM_ROWS);
    }

    private static String cell(String[][] col, int idx)
    {
        if (col == null || idx >= col.length || col[idx] == null || col[idx].length == 0) return "";
        return safe(col[idx][0]);
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String safe(String s) { return s == null ? "" : s; }
}
