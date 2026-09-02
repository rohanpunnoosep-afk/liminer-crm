package com.liminer.intake;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

// DEFAULT_DENY: nothing enters the CRM unless a layer explicitly allows it.
public class EmailRelevanceFilter
{
    public static final String DEFAULT_DENY = "DEFAULT_DENY";

    public static final List<String> DEFAULT_KEYWORDS = Arrays.asList(
        "fund", "lp", "limited partner", "gp", "general partner", "portfolio",
        "capital", "invest", "allocat", "manager", "aum", "deck", "raise"
    );

    public static final String DENIED_DEFAULT = "DENIED_DEFAULT";
    public static final String LAYER_USER_LABEL = "LAYER_USER_LABEL";
    public static final String LAYER_KNOWN_CORRESPONDENT = "LAYER_KNOWN_CORRESPONDENT";
    public static final String LAYER_EXISTING_THREAD = "LAYER_EXISTING_THREAD";
    public static final String LAYER_KEYWORD_AI = "LAYER_KEYWORD_AI";

    private static final int MAX_BODY_PREFIX_CHARS = 500;

    private final String liminerLabelId;
    private final Set<String> crmEmails;
    private final Set<String> knownThreadIds;
    private final boolean keywordAiEnabled;
    private final List<String> keywords;
    private final RelevanceClassifier classifier;

    public EmailRelevanceFilter(
        String liminerLabelId0,
        Set<String> crmEmails0,
        Set<String> knownThreadIds0,
        boolean keywordAiEnabled0,
        List<String> keywords0,
        RelevanceClassifier classifier0)
    {
        this.liminerLabelId = liminerLabelId0 == null ? "" : liminerLabelId0;
        this.crmEmails = crmEmails0;
        this.knownThreadIds = knownThreadIds0;
        this.keywordAiEnabled = keywordAiEnabled0;
        this.keywords = keywords0 == null ? DEFAULT_KEYWORDS : keywords0;
        this.classifier = classifier0;
    }

    public RelevanceDecision decide(GmailMessage message0)
    {
        if (message0 == null)
        {
            return new RelevanceDecision(false, DENIED_DEFAULT, "");
        }

        if (hasLiminerLabel(message0))
        {
            return new RelevanceDecision(true, LAYER_USER_LABEL, liminerLabelId);
        }

        String matchedCorrespondent0 = findKnownCorrespondent(message0);

        if (matchedCorrespondent0 != null)
        {
            return new RelevanceDecision(true, LAYER_KNOWN_CORRESPONDENT, matchedCorrespondent0);
        }

        if (knownThreadIds != null && knownThreadIds.contains(message0.threadId))
        {
            return new RelevanceDecision(true, LAYER_EXISTING_THREAD, message0.threadId);
        }

        if (keywordAiEnabled && classifier != null)
        {
            String combinedText0 = (message0.subject + " " + message0.bodyText).toLowerCase();

            if (matchesAnyKeyword(combinedText0))
            {
                String fromDomain0 = extractDomain(normalizeEmail(message0.from));
                String bodyPrefix0 = truncateBody(message0.bodyText);

                boolean relevant0 = classifier.isRelevant(fromDomain0, message0.subject, bodyPrefix0);

                if (relevant0)
                {
                    return new RelevanceDecision(true, LAYER_KEYWORD_AI, fromDomain0);
                }
            }
        }

        return new RelevanceDecision(false, DENIED_DEFAULT, "");
    }

    private boolean hasLiminerLabel(GmailMessage message0)
    {
        if (message0.labelIds == null || liminerLabelId.length() == 0)
        {
            return false;
        }

        for (int i0 = 0; i0 < message0.labelIds.size(); i0++)
        {
            if (liminerLabelId.equals(message0.labelIds.get(i0)))
            {
                return true;
            }
        }

        return false;
    }

    private String findKnownCorrespondent(GmailMessage message0)
    {
        if (crmEmails == null || crmEmails.isEmpty())
        {
            return null;
        }

        String fromEmail0 = normalizeEmail(message0.from);

        if (fromEmail0.length() > 0 && crmEmails.contains(fromEmail0))
        {
            return fromEmail0;
        }

        if (message0.to == null || message0.to.length() == 0)
        {
            return null;
        }

        String[] toParts0 = message0.to.split(",");

        for (int i0 = 0; i0 < toParts0.length; i0++)
        {
            String toEmail0 = normalizeEmail(toParts0[i0]);

            if (toEmail0.length() > 0 && crmEmails.contains(toEmail0))
            {
                return toEmail0;
            }
        }

        return null;
    }

    private boolean matchesAnyKeyword(String lowercasedText0)
    {
        if (keywords == null)
        {
            return false;
        }

        for (int i0 = 0; i0 < keywords.size(); i0++)
        {
            String keyword0 = keywords.get(i0);

            if (keyword0 != null && lowercasedText0.contains(keyword0.toLowerCase()))
            {
                return true;
            }
        }

        return false;
    }

    private String truncateBody(String bodyText0)
    {
        if (bodyText0 == null)
        {
            return "";
        }

        if (bodyText0.length() <= MAX_BODY_PREFIX_CHARS)
        {
            return bodyText0;
        }

        return bodyText0.substring(0, MAX_BODY_PREFIX_CHARS);
    }

    private static String extractDomain(String normalizedEmail0)
    {
        if (normalizedEmail0 == null)
        {
            return "";
        }

        int atIndex0 = normalizedEmail0.indexOf("@");

        if (atIndex0 == -1 || atIndex0 == normalizedEmail0.length() - 1)
        {
            return "";
        }

        return normalizedEmail0.substring(atIndex0 + 1);
    }

    public static String normalizeEmail(String raw0)
    {
        if (raw0 == null)
        {
            return "";
        }

        String trimmed0 = raw0.trim();

        int start0 = trimmed0.indexOf("<");
        int end0 = trimmed0.indexOf(">");

        if (start0 != -1 && end0 != -1 && end0 > start0)
        {
            trimmed0 = trimmed0.substring(start0 + 1, end0);
        }

        trimmed0 = trimmed0.trim().toLowerCase();

        int atIndex0 = trimmed0.indexOf("@");

        if (atIndex0 == -1)
        {
            return trimmed0;
        }

        String localPart0 = trimmed0.substring(0, atIndex0);
        String domainPart0 = trimmed0.substring(atIndex0);

        int plusIndex0 = localPart0.indexOf("+");

        if (plusIndex0 != -1)
        {
            localPart0 = localPart0.substring(0, plusIndex0);
        }

        return localPart0 + domainPart0;
    }
}
