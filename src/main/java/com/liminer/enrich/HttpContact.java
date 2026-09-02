package com.liminer.enrich;

/**
 * Shared User-Agent for the public-register clients (EDGAR, IAPD, Companies
 * House, FCA, ESMA, GLEIF, IRS 990, ProPublica).
 *
 * Those registers require requests to carry an identifiable contact address --
 * SEC fair-access rejects anonymous agents outright, and the UK/EU registers
 * rate-limit them hard. Sending a real address is correct etiquette, not an
 * oversight. It is read from LIMINER_CONTACT_EMAIL rather than compiled in so
 * that a checkout of this repository never ships an operator's inbox; the
 * fallback is an obvious placeholder, which makes an unset variable easy to
 * spot in a request log rather than silently anonymous.
 */
public final class HttpContact
{
    private static final String DEFAULT_CONTACT0 = "contact@example.com";

    /** e.g. {@code "LiminerAI/1.0 ops@example.com"}. */
    public static final String USER_AGENT0 =
        "LiminerAI/1.0 " + System.getenv()
            .getOrDefault("LIMINER_CONTACT_EMAIL", DEFAULT_CONTACT0);

    private HttpContact() { }
}
