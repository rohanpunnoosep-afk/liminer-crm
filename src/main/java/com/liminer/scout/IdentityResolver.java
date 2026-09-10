package com.liminer.scout;

import com.liminer.enrich.EdgarClient;
import com.liminer.enrich.GleifClient;
import com.liminer.enrich.IapdClient;
import com.liminer.enrich.ProPublicaNonprofitClient;
import com.liminer.enrich.ScrapeCache;

import java.util.ArrayList;

/*
 * IdentityResolver is the keystone of the LP market-intelligence component. It
 * resolves an LP's name + website + address to canonical regulator identity keys
 * {CRD, CIK, LEI, EIN} ONCE, so every filing leaf (RaumIndicator, NonprofitAssets,
 * FundClose, ...) becomes a cheap keyed lookup instead of a fragile per-leaf name
 * search.
 *
 * Anti-collision discipline (the doc's hardest cross-cutting risk): a key is only
 * accepted when evidence actually TIES the candidate record to this LP.
 *
 * This used to be enforced by a flag named hasAnchor0 that tested only whether the
 * LP had a website string at all — not whether that website matched the candidate.
 * Any LP with a website in its row therefore passed the "anchor" test, and every
 * name-only guess was accepted. Combined with the first-hit fallthroughs that used
 * to sit at the bottom of IapdClient.lookupCrdByName and
 * ProPublicaNonprofitClient.lookupEinByName, a UK advisory firm called
 * "Nelson Advisors" (nelsonadvisors.co.uk) was resolved to CRD 307706
 * (Nelson Capital Advisors, Orono ME) and EIN 611400414
 * (Nelson County Horticulture Advisory Board, Bardstown KY) — and, because
 * confidence was computed from the NUMBER of keys found, three bad guesses
 * reinforced each other into a "SAFE" verdict on the row.
 *
 * A key is now accepted only when one of these holds:
 *   1. DOMAIN match  — the LP's own registrable domain appears in the candidate's
 *                      regulator record. Decisive on its own.
 *   2. NAME match corroborated by JURISDICTION — a strong normalized name overlap
 *                      AND no conflict between where the LP is and where the
 *                      candidate is registered.
 * A name match that CONFLICTS on jurisdiction is discarded outright: a .co.uk
 * adviser does not hold a Maine RIA's CRD or a Kentucky charity's EIN.
 *
 * Status is graded with the same IdentityResolutionScorer thresholds the background
 * checker uses, but confidence is now driven by CORROBORATION STRENGTH rather than
 * key count, so uncorroborated guesses can no longer add up to SAFE:
 *   confidence >= THRESHOLD_SAFE (0.85)   -> SAFE      (domain-anchored)
 *   confidence >= THRESHOLD_REVIEW (0.65) -> REVIEW    (name + jurisdiction only)
 *   otherwise                             -> UNRESOLVED
 *
 * Stateless and thread-safe: the clients hold only static shared HttpClients, and
 * resolve() mutates no shared state. The actual column WRITE of these keys happens
 * later in LPScoreProcessor's single-threaded write phase, never inside the
 * parallel row body. LPScoreProcessor should read cached keys off the row first
 * (IdentityKeys.fromCached) and only call resolve() when needsResolution() is true
 * (resolve-once).
 *
 */
public class IdentityResolver
{
    public static final String STATUS_SAFE = "SAFE";
    public static final String STATUS_REVIEW = "REVIEW";
    public static final String STATUS_UNRESOLVED = "UNRESOLVED";

    // The resolved (or cached) canonical keys for one LP.
    public static class IdentityKeys
    {
        public String crd = "";
        public String cik = "";
        public String lei = "";
        public String ein = "";
        public String status = STATUS_UNRESOLVED;

        public IdentityKeys() {}

        // Rebuild keys already cached on the CRM row so we do NOT re-resolve.
        public static IdentityKeys fromCached(String crd0, String cik0, String lei0,
                                              String ein0, String status0)
        {
            IdentityKeys k = new IdentityKeys();
            k.crd = safe(crd0);
            k.cik = safe(cik0);
            k.lei = safe(lei0);
            k.ein = safe(ein0);
            k.status = isBlank(status0) ? STATUS_UNRESOLVED : status0.trim();
            return k;
        }

        public boolean hasAnyKey()
        {
            return !isBlank(crd) || !isBlank(cik) || !isBlank(lei) || !isBlank(ein);
        }
    }

    private final IapdClient iapdClient0;
    private final EdgarClient edgarClient0;
    private final GleifClient gleifClient0;
    private final ProPublicaNonprofitClient nonprofitClient0;

    public IdentityResolver()
    {
        this.iapdClient0 = new IapdClient();
        this.edgarClient0 = new EdgarClient();
        this.gleifClient0 = new GleifClient();
        this.nonprofitClient0 = new ProPublicaNonprofitClient();
    }

    // Resolve-once gate: true only when the row carries no usable cached key and a
    // prior run did not already mark it resolved.
    public boolean needsResolution(IdentityKeys cached0)
    {
        if (cached0 == null) return true;
        if (cached0.hasAnyKey()) return false;
        return !STATUS_SAFE.equals(cached0.status) && !STATUS_REVIEW.equals(cached0.status);
    }

    // Resolve name+website+address to identity keys. Never throws into the caller:
    // a failing client is caught and skipped so a row is never crashed by resolution.
    public IdentityKeys resolve(String name0, String website0, String address0, ScrapeCache cache0)
    {
        IdentityKeys keys0 = new IdentityKeys();
        if (isBlank(name0))
        {
            keys0.status = STATUS_UNRESOLVED;
            return keys0;
        }

        String domain0 = extractDomain(website0);
        // Where this LP actually is. "" means unknown, which never CONFLICTS with
        // anything — it simply fails to corroborate, which is the safe direction.
        String lpCountry0 = inferCountry(address0, domain0);

        boolean domainAnchored0 = false;
        boolean nameAnchored0 = false;

        // CRD via IAPD (US registered advisers).
        try
        {
            IapdClient.FirmMatch firm0 = iapdClient0.lookupFirm(name0, website0);
            if (firm0 != null)
            {
                Verdict v0 = judge(firm0.domainMatch, firm0.nameMatch,
                                   lpCountry0, normalizeCountry(firm0.country));
                if (v0.accept)
                {
                    keys0.crd = firm0.crd;
                    domainAnchored0 |= v0.byDomain;
                    nameAnchored0 |= !v0.byDomain;
                }
                else
                {
                    logReject("IAPD/CRD", name0, firm0.crd + " " + firm0.firmName
                        + " (" + firm0.city + " " + firm0.state + " " + firm0.country + ")", v0.reason);
                }
            }
        }
        catch (Exception e0) { logSkip("IAPD/CRD", name0, domain0, e0); }

        // EIN via ProPublica (US tax-exempts). A non-US LP cannot hold one, so the
        // jurisdiction test below rejects the whole class rather than name-matching
        // a UK firm onto an American charity.
        try
        {
            ProPublicaNonprofitClient.OrgMatch org0 = nonprofitClient0.lookupOrg(name0);
            if (org0 != null)
            {
                Verdict v0 = judge(false, org0.nameMatch, lpCountry0, "US");
                if (v0.accept)
                {
                    keys0.ein = org0.ein;
                    nameAnchored0 = true;
                }
                else
                {
                    logReject("ProPublica/EIN", name0,
                        org0.ein + " " + org0.name + " (" + org0.city + " " + org0.state + ")", v0.reason);
                }
            }
        }
        catch (Exception e0) { logSkip("ProPublica/EIN", name0, domain0, e0); }

        // LEI via GLEIF (entities holding an LEI). GLEIF is name-searched and
        // returns no corroborating detail through this client, so an LEI is only
        // accepted once another source has already anchored the entity — it can
        // enrich a resolved identity but must never establish one.
        try
        {
            String lei0 = gleifClient0.lookupLei(name0);
            if (!isBlank(lei0) && (domainAnchored0 || nameAnchored0)) keys0.lei = lei0.trim();
        }
        catch (Exception e0) { logSkip("GLEIF/LEI", name0, domain0, e0); }

        // CIK via EDGAR full-text search; accept only on a corroborating entity title.
        try
        {
            ArrayList<EdgarClient.SearchHit> hits0 = edgarClient0.fullTextSearch(name0);
            if (hits0 != null)
            {
                for (EdgarClient.SearchHit hit0 : hits0)
                {
                    if (hit0 == null || isBlank(hit0.cik)) continue;
                    if (!titleCorroborates(hit0.title, name0)) continue;
                    // EDGAR is a US registry: same jurisdiction gate as the EIN.
                    Verdict v0 = judge(false, true, lpCountry0, "US");
                    if (!v0.accept)
                    {
                        logReject("EDGAR/CIK", name0, hit0.cik + " " + hit0.title, v0.reason);
                        break;
                    }
                    keys0.cik = hit0.cik.trim();
                    nameAnchored0 = true;
                    break;
                }
            }
        }
        catch (Exception e0) { logSkip("EDGAR/CIK", name0, domain0, e0); }

        keys0.status = statusFor(computeConfidence(keys0, domainAnchored0, nameAnchored0));
        return keys0;
    }

    // The outcome of testing one candidate record against this LP.
    private static class Verdict
    {
        boolean accept;
        boolean byDomain;   // accepted on the decisive domain anchor
        String reason = "";

        static Verdict yes(boolean byDomain0)
        {
            Verdict v0 = new Verdict();
            v0.accept = true;
            v0.byDomain = byDomain0;
            return v0;
        }

        static Verdict no(String reason0)
        {
            Verdict v0 = new Verdict();
            v0.accept = false;
            v0.reason = reason0;
            return v0;
        }
    }

    /*
     * The single place a candidate is accepted or refused. Tune identity strictness
     * HERE — never by loosening a client's name matcher, and never by adding another
     * "we found something, take it" fallthrough.
     */
    private Verdict judge(boolean domainMatch0, boolean nameMatch0,
                          String lpCountry0, String candidateCountry0)
    {
        // A shared registrable domain is decisive; jurisdiction cannot override it
        // (a UK firm may legitimately hold a US registration under its own domain).
        if (domainMatch0) return Verdict.yes(true);

        if (!nameMatch0) return Verdict.no("no domain match and no strong name match");

        // Name-only match: it must at least not contradict where the LP is.
        if (!isBlank(lpCountry0) && !isBlank(candidateCountry0)
            && !lpCountry0.equals(candidateCountry0))
        {
            return Verdict.no("jurisdiction conflict: LP is " + lpCountry0
                + ", candidate is " + candidateCountry0);
        }

        return Verdict.yes(false);
    }

    /*
     * Confidence reflects HOW WELL the identity is corroborated, not how many keys
     * were collected. Counting keys was the original defect: three independent
     * name-only guesses summed to 1.0 and stamped the row SAFE.
     *
     *   domain-anchored              -> 0.90  (SAFE)
     *   name + jurisdiction only     -> 0.70  (REVIEW — a human should confirm)
     *   nothing accepted             -> 0.00  (UNRESOLVED)
     *
     * Additional corroborating keys add a little, but can never lift a name-only
     * resolution into SAFE on their own.
     */
    private double computeConfidence(IdentityKeys keys0, boolean domainAnchored0,
                                     boolean nameAnchored0)
    {
        if (!keys0.hasAnyKey()) return 0.0;

        double c0;
        if (domainAnchored0)   c0 = 0.90;
        else if (nameAnchored0) c0 = 0.70;
        else                    return 0.0;

        int count0 = 0;
        if (!isBlank(keys0.crd)) count0++;
        if (!isBlank(keys0.cik)) count0++;
        if (!isBlank(keys0.lei)) count0++;
        if (!isBlank(keys0.ein)) count0++;
        if (count0 > 1) c0 += 0.03 * (count0 - 1);

        // Hard ceiling below SAFE for anything not domain-anchored.
        if (!domainAnchored0) c0 = Math.min(c0, IdentityResolutionScorer.THRESHOLD_SAFE - 0.01);
        return Math.min(c0, 1.0);
    }

    /*
     * Best-effort ISO-ish country code for the LP from its address text, falling
     * back to the website's country-code TLD. Returns "" when unknown — an unknown
     * jurisdiction never conflicts, it just fails to corroborate.
     *
     * The ccTLD fallback is what catches the Nelson case: the CRM row carried no
     * Country at all, but nelsonadvisors.co.uk is unambiguously GB.
     */
    private static String inferCountry(String address0, String domain0)
    {
        String a0 = safe(address0).toLowerCase();
        if (a0.contains("united kingdom") || a0.contains("england") || a0.contains("scotland")
            || a0.contains("wales") || a0.contains(" uk") || a0.equals("uk")) return "GB";
        if (a0.contains("united states") || a0.contains("usa") || a0.equals("us")
            || a0.contains(", us")) return "US";
        if (a0.contains("canada")) return "CA";

        String d0 = safe(domain0).toLowerCase();
        if (d0.endsWith(".uk")) return "GB";
        if (d0.endsWith(".ca")) return "CA";
        if (d0.endsWith(".au")) return "AU";
        if (d0.endsWith(".de")) return "DE";
        if (d0.endsWith(".fr")) return "FR";
        if (d0.endsWith(".ch")) return "CH";
        if (d0.endsWith(".nl")) return "NL";
        if (d0.endsWith(".sg")) return "SG";
        if (d0.endsWith(".jp")) return "JP";
        if (d0.endsWith(".in")) return "IN";
        if (d0.endsWith(".ae")) return "AE";
        if (d0.endsWith(".hk")) return "HK";
        if (d0.endsWith(".ie")) return "IE";
        if (d0.endsWith(".se")) return "SE";
        if (d0.endsWith(".no")) return "NO";
        if (d0.endsWith(".dk")) return "DK";
        // .com/.org/.net/.io and friends are global — they say nothing about country.
        return "";
    }

    // Regulator records spell the country out; reduce to the same codes inferCountry emits.
    private static String normalizeCountry(String country0)
    {
        String c0 = safe(country0).trim().toLowerCase();
        if (c0.isEmpty()) return "";
        if (c0.startsWith("united states") || c0.equals("us") || c0.equals("usa")) return "US";
        if (c0.startsWith("united kingdom") || c0.equals("uk") || c0.equals("gb")) return "GB";
        if (c0.startsWith("canada")) return "CA";
        return c0.toUpperCase();
    }

    private String statusFor(double confidence0)
    {
        if (confidence0 >= IdentityResolutionScorer.THRESHOLD_SAFE) return STATUS_SAFE;
        if (confidence0 >= IdentityResolutionScorer.THRESHOLD_REVIEW) return STATUS_REVIEW;
        return STATUS_UNRESOLVED;
    }

    // Loose name corroboration for an EDGAR title vs. the LP name: normalized
    // substring overlap in either direction. Tighten here if false positives appear.
    private boolean titleCorroborates(String title0, String name0)
    {
        String t0 = IdentityResolutionScorer.normalizeCompanyName(title0);
        String n0 = IdentityResolutionScorer.normalizeCompanyName(name0);
        if (isBlank(t0) || isBlank(n0)) return false;
        return t0.contains(n0) || n0.contains(t0);
    }

    // Reduce a website to a bare lowercase host: strip scheme, www., port, path.
    private static String extractDomain(String website0)
    {
        if (isBlank(website0)) return "";
        String d0 = website0.trim().toLowerCase();
        int scheme0 = d0.indexOf("://");
        if (scheme0 >= 0) d0 = d0.substring(scheme0 + 3);
        if (d0.startsWith("www.")) d0 = d0.substring(4);
        int slash0 = d0.indexOf('/');
        if (slash0 >= 0) d0 = d0.substring(0, slash0);
        int colon0 = d0.indexOf(':');
        if (colon0 >= 0) d0 = d0.substring(0, colon0);
        return d0.trim();
    }

    private void logReject(String source0, String name0, String candidate0, String reason0)
    {
        System.out.println("  IdentityResolver: " + source0 + " candidate REJECTED for \""
            + safe(name0) + "\" -> " + safe(candidate0) + " (" + safe(reason0) + ")");
    }

    private void logSkip(String source0, String name0, String domain0, Exception e0)
    {
        System.out.println("  IdentityResolver: " + source0 + " lookup skipped for \""
            + safe(name0) + "\" (domain=" + safe(domain0) + "): " + e0.getMessage());
    }

    private static boolean isBlank(String s0)
    {
        return s0 == null || s0.trim().isEmpty();
    }

    private static String safe(String s0)
    {
        return s0 == null ? "" : s0;
    }
}
