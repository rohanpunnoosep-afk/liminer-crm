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
 * accepted when a website DOMAIN or address anchor corroborates it. Without an
 * anchor we do not guess — "the right Acme Capital" matters. Status is graded with
 * the same IdentityResolutionScorer thresholds the background checker uses:
 *   confidence >= THRESHOLD_SAFE (0.85)   -> SAFE
 *   confidence >= THRESHOLD_REVIEW (0.65) -> REVIEW
 *   otherwise                             -> UNRESOLVED
 *
 * Stateless and thread-safe: the clients hold only static shared HttpClients, and
 * resolve() mutates no shared state. The actual column WRITE of these keys happens
 * later in LPScoreProcessor's single-threaded write phase, never inside the
 * parallel row body. LPScoreProcessor should read cached keys off the row first
 * (IdentityKeys.fromCached) and only call resolve() when needsResolution() is true
 * (resolve-once).
 *
 * NOTE: the Task 3 filing clients are still stubs, so resolve() returns UNRESOLVED
 * in practice today — expected until the clients go live. The anchoring/scoring
 * logic below is the real behavior that activates the moment they do.
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
        // The anchor that kills name-collision false positives. Without it we never
        // accept a key from a bare name match.
        boolean hasAnchor0 = !isBlank(domain0) || !isBlank(address0);

        // CRD via IAPD (US registered advisers).
        try
        {
            String crd0 = iapdClient0.lookupCrdByName(name0, website0);
            if (!isBlank(crd0) && hasAnchor0) keys0.crd = crd0.trim();
        }
        catch (Exception e0) { logSkip("IAPD/CRD", name0, domain0, e0); }

        // EIN via ProPublica (US tax-exempts).
        try
        {
            String ein0 = nonprofitClient0.lookupEinByName(name0);
            if (!isBlank(ein0) && hasAnchor0) keys0.ein = ein0.trim();
        }
        catch (Exception e0) { logSkip("ProPublica/EIN", name0, domain0, e0); }

        // LEI via GLEIF (entities holding an LEI).
        try
        {
            String lei0 = gleifClient0.lookupLei(name0);
            if (!isBlank(lei0) && hasAnchor0) keys0.lei = lei0.trim();
        }
        catch (Exception e0) { logSkip("GLEIF/LEI", name0, domain0, e0); }

        // CIK via EDGAR full-text search; accept the first hit whose entity title
        // corroborates the LP name (anchor still required).
        try
        {
            ArrayList<EdgarClient.SearchHit> hits0 = edgarClient0.fullTextSearch(name0);
            if (hits0 != null && hasAnchor0)
            {
                for (EdgarClient.SearchHit hit0 : hits0)
                {
                    if (hit0 != null && !isBlank(hit0.cik) && titleCorroborates(hit0.title, name0))
                    {
                        keys0.cik = hit0.cik.trim();
                        break;
                    }
                }
            }
        }
        catch (Exception e0) { logSkip("EDGAR/CIK", name0, domain0, e0); }

        double confidence0 = computeConfidence(keys0, !isBlank(domain0), !isBlank(address0));
        keys0.status = statusFor(confidence0);
        return keys0;
    }

    // Confidence rises with the number of independently resolved keys and with the
    // strength of the corroborating anchor (domain > address). Tune the anchor
    // weighting HERE — never let a filing leaf do its own name search.
    private double computeConfidence(IdentityKeys keys0, boolean hasDomain0, boolean hasAddress0)
    {
        int count0 = 0;
        if (!isBlank(keys0.crd)) count0++;
        if (!isBlank(keys0.cik)) count0++;
        if (!isBlank(keys0.lei)) count0++;
        if (!isBlank(keys0.ein)) count0++;

        if (count0 == 0) return 0.0;

        double c0 = 0.5 + (0.12 * count0);
        if (hasDomain0) c0 += 0.20;
        if (hasAddress0) c0 += 0.08;
        if (c0 > 1.0) c0 = 1.0;
        return c0;
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
