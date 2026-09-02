package com.liminer.intake;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class EmailRelevanceFilterTestMain
{
    private static final String LIMINER_LABEL_ID = "Label_LIMINER_1";

    public static void main(String[] args0)
    {
        try
        {
            testUnlabeledUnknownCorrespondentDenied();
            testLiminerLabelIncluded();
            testKnownCorrespondentMatchesWithNoise();
            testKnownThreadIncluded();
            testKeywordAiDisabledStillDenied();
            testKeywordAiEnabledPrescreenHitClassifierYes();
            testKeywordAiPrescreenMiss();
            testConfidentialFixtureClassifierNo();

            System.out.println("RELEVANCE_FILTER_OK");
        }
        catch (Exception exception0)
        {
            System.out.println("TEST FAILURE: " + exception0.getMessage());
            exception0.printStackTrace();
            System.exit(1);
        }
    }

    private static void testUnlabeledUnknownCorrespondentDenied() throws Exception
    {
        CountingClassifier classifier0 = new CountingClassifier(true);

        EmailRelevanceFilter filter0 = new EmailRelevanceFilter(
            LIMINER_LABEL_ID,
            new HashSet<>(),
            new HashSet<>(),
            false,
            null,
            classifier0
        );

        GmailMessage message0 = baseMessage();
        message0.from = "randomperson@example.com";
        message0.to = "someoneelse@example.com";

        RelevanceDecision decision0 = filter0.decide(message0);

        require(!decision0.include, "(a) expected denied");
        require(decision0.reason.equals(EmailRelevanceFilter.DENIED_DEFAULT), "(a) expected DENIED_DEFAULT");
        require(classifier0.callCount == 0, "(a) classifier should never be called");
    }

    private static void testLiminerLabelIncluded() throws Exception
    {
        EmailRelevanceFilter filter0 = new EmailRelevanceFilter(
            LIMINER_LABEL_ID,
            new HashSet<>(),
            new HashSet<>(),
            false,
            null,
            null
        );

        GmailMessage message0 = baseMessage();
        message0.from = "randomperson@example.com";
        message0.labelIds = new ArrayList<>(Arrays.asList(LIMINER_LABEL_ID));

        RelevanceDecision decision0 = filter0.decide(message0);

        require(decision0.include, "(b) expected included");
        require(decision0.reason.equals(EmailRelevanceFilter.LAYER_USER_LABEL), "(b) expected LAYER_USER_LABEL");
    }

    private static void testKnownCorrespondentMatchesWithNoise() throws Exception
    {
        Set<String> crmEmails0 = new HashSet<>(Arrays.asList("investor@lpfund.com"));

        EmailRelevanceFilter filter0 = new EmailRelevanceFilter(
            LIMINER_LABEL_ID,
            crmEmails0,
            new HashSet<>(),
            false,
            null,
            null
        );

        GmailMessage message0 = baseMessage();
        message0.from = "Jane Investor <Investor+newsletter@LPFund.com>";
        message0.to = "gp@ourfund.com";

        RelevanceDecision decision0 = filter0.decide(message0);

        require(decision0.include, "(c) expected included");
        require(
            decision0.reason.equals(EmailRelevanceFilter.LAYER_KNOWN_CORRESPONDENT),
            "(c) expected LAYER_KNOWN_CORRESPONDENT"
        );
    }

    private static void testKnownThreadIncluded() throws Exception
    {
        Set<String> knownThreadIds0 = new HashSet<>(Arrays.asList("thread-123"));

        EmailRelevanceFilter filter0 = new EmailRelevanceFilter(
            LIMINER_LABEL_ID,
            new HashSet<>(),
            knownThreadIds0,
            false,
            null,
            null
        );

        GmailMessage message0 = baseMessage();
        message0.from = "randomperson@example.com";
        message0.threadId = "thread-123";

        RelevanceDecision decision0 = filter0.decide(message0);

        require(decision0.include, "(d) expected included");
        require(
            decision0.reason.equals(EmailRelevanceFilter.LAYER_EXISTING_THREAD),
            "(d) expected LAYER_EXISTING_THREAD"
        );
    }

    private static void testKeywordAiDisabledStillDenied() throws Exception
    {
        CountingClassifier classifier0 = new CountingClassifier(true);

        EmailRelevanceFilter filter0 = new EmailRelevanceFilter(
            LIMINER_LABEL_ID,
            new HashSet<>(),
            new HashSet<>(),
            false,
            null,
            classifier0
        );

        GmailMessage message0 = baseMessage();
        message0.from = "randomperson@example.com";
        message0.subject = "Our fund's LP allocation update";
        message0.bodyText = "Talking about capital raise and portfolio manager topics.";

        RelevanceDecision decision0 = filter0.decide(message0);

        require(!decision0.include, "(e) expected denied when keywordAiEnabled=false");
        require(classifier0.callCount == 0, "(e) classifier should never be called when disabled");
    }

    private static void testKeywordAiEnabledPrescreenHitClassifierYes() throws Exception
    {
        CountingClassifier classifier0 = new CountingClassifier(true);

        EmailRelevanceFilter filter0 = new EmailRelevanceFilter(
            LIMINER_LABEL_ID,
            new HashSet<>(),
            new HashSet<>(),
            true,
            null,
            classifier0
        );

        StringBuilder longBody0 = new StringBuilder();
        for (int i0 = 0; i0 < 700; i0++)
        {
            longBody0.append("x");
        }

        GmailMessage message0 = baseMessage();
        message0.from = "randomperson@lpfirm.com";
        message0.subject = "Interested in your fund raise";
        message0.bodyText = longBody0.toString();

        RelevanceDecision decision0 = filter0.decide(message0);

        require(decision0.include, "(f) expected included");
        require(
            decision0.reason.equals(EmailRelevanceFilter.LAYER_KEYWORD_AI),
            "(f) expected LAYER_KEYWORD_AI"
        );
        require(classifier0.callCount == 1, "(f) classifier should be called once");
        require(
            classifier0.lastBodyPrefix.length() <= 500,
            "(f) classifier must receive at most 500 body chars, got " + classifier0.lastBodyPrefix.length()
        );
    }

    private static void testKeywordAiPrescreenMiss() throws Exception
    {
        CountingClassifier classifier0 = new CountingClassifier(true);

        EmailRelevanceFilter filter0 = new EmailRelevanceFilter(
            LIMINER_LABEL_ID,
            new HashSet<>(),
            new HashSet<>(),
            true,
            null,
            classifier0
        );

        GmailMessage message0 = baseMessage();
        message0.from = "randomperson@example.com";
        message0.subject = "Lunch tomorrow?";
        message0.bodyText = "Want to grab lunch tomorrow at noon?";

        RelevanceDecision decision0 = filter0.decide(message0);

        require(!decision0.include, "(g) expected denied on prescreen miss");
        require(classifier0.callCount == 0, "(g) classifier should never be called on prescreen miss");
    }

    private static void testConfidentialFixtureClassifierNo() throws Exception
    {
        CountingClassifier classifier0 = new CountingClassifier(false);

        EmailRelevanceFilter filter0 = new EmailRelevanceFilter(
            LIMINER_LABEL_ID,
            new HashSet<>(),
            new HashSet<>(),
            true,
            null,
            classifier0
        );

        GmailMessage message0 = baseMessage();
        message0.from = "hrteam@ourfund.com";
        message0.subject = "Payroll for our fund ops team";
        message0.bodyText = "Attached is the payroll spreadsheet for our fund's internal ops team this month.";

        RelevanceDecision decision0 = filter0.decide(message0);

        require(!decision0.include, "(h) expected denied for confidential internal email");
        require(classifier0.callCount == 1, "(h) classifier should be called after prescreen hit");
    }

    private static GmailMessage baseMessage()
    {
        GmailMessage message0 = new GmailMessage();
        message0.messageId = "msg-1";
        message0.threadId = "thread-1";
        message0.internalDate = 0L;
        message0.from = "";
        message0.to = "";
        message0.subject = "";
        message0.labelIds = new ArrayList<>();
        message0.bodyText = "";
        message0.snippet = "";
        return message0;
    }

    private static void require(boolean condition0, String message0) throws Exception
    {
        if (!condition0)
        {
            throw new Exception(message0);
        }
    }

    private static class CountingClassifier implements RelevanceClassifier
    {
        int callCount = 0;
        String lastBodyPrefix = "";
        boolean answer;

        CountingClassifier(boolean answer0)
        {
            this.answer = answer0;
        }

        @Override
        public boolean isRelevant(String fromDomain0, String subject0, String bodyPrefix0)
        {
            callCount++;
            lastBodyPrefix = bodyPrefix0 == null ? "" : bodyPrefix0;
            return answer;
        }
    }
}
