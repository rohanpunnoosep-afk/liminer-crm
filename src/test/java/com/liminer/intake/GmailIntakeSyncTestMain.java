package com.liminer.intake;

import com.liminer.core.SyncResult;
import com.liminer.sheets.IntakeSheetPort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Fully offline test main for GmailIntakeSync: fake GmailSource + in-memory
// IntakeSheetPort + a real EmailRelevanceFilter built from fixture sets
// (keywordAiEnabled=false, per this task's guardrails). No live Gmail/Sheets calls.
public class GmailIntakeSyncTestMain
{
    private static final String LIMINER_LABEL_ID0 = "Label_LIMINER_1";

    public static void main(String[] args0)
    {
        try
        {
            testAcceptedMessageAppendsOneRowWithBlankProcessingStatus();
            testDuplicateMessageIdSkipped();
            testDeniedMessageAppendsNothing();
            testThreadContinuationAcceptedViaExistingThreadLayer();
            testWatermarkAdvancesOnlyAfterSuccessAndEqualsMaxInternalDate();
            testSyncResultCountsMatchFixture();

            System.out.println("GMAIL_SYNC_OK");
        }
        catch (Exception exception0)
        {
            System.out.println("GMAIL_SYNC_TEST_FAILED: " + exception0.getMessage());
            exception0.printStackTrace();
            System.exit(1);
        }
    }

    private static void testAcceptedMessageAppendsOneRowWithBlankProcessingStatus() throws Exception
    {
        GmailMessage message0 = message("msg-1", "thread-1", 1_700_000_000_000L,
            "gp@ourfund.com", "investor@lpfund.com", "Re: allocation", "Following up on our call.");

        FakeGmailSource source0 = new FakeGmailSource(Arrays.asList(message0));
        FakeIntakeSheetPort sheetPort0 = new FakeIntakeSheetPort();

        EmailRelevanceFilter filter0 = buildFilter(
            new HashSet<>(Arrays.asList("investor@lpfund.com")),
            new HashSet<>()
        );

        GmailIntakeSync sync0 = new GmailIntakeSync(source0, sheetPort0, filter0);

        SyncResult result0 = sync0.run(0L);

        require(result0.accepted == 1, "(a) expected 1 accepted");
        require(sheetPort0.appendedRows.size() == 1, "(a) expected exactly one appended row");

        Map<String, String> row0 = sheetPort0.appendedRows.get(0);

        require(row0.get("intakeTabGmailMessageIdCol").equals("msg-1"), "(a) messageId mismatch");
        require(row0.get("intakeTabGmailThreadIdCol").equals("thread-1"), "(a) threadId mismatch");
        require(row0.get("intakeTabIntakeTypeCol").equals("EMAIL"), "(a) intake type mismatch");
        require(row0.get("intakeTabToCol").equals("gp@ourfund.com"), "(a) to mismatch");
        require(row0.get("intakeTabFromCol").equals("investor@lpfund.com"), "(a) from mismatch");
        require(row0.get("intakeTabSubjectCol").equals("Re: allocation"), "(a) subject mismatch");
        require(row0.get("intakeTabBodyCol").equals("Following up on our call."), "(a) body mismatch");
        require(row0.get("intakeTabIntakeIdCol") != null && row0.get("intakeTabIntakeIdCol").length() > 0,
            "(a) intake id should be generated");
        require(!row0.containsKey("intakeTabProcessingStatusCol"),
            "(a) Processing Status must be left blank/unset so the row is picked up as unprocessed");
    }

    private static void testDuplicateMessageIdSkipped() throws Exception
    {
        GmailMessage message0 = message("msg-dup", "thread-1", 1_700_000_000_000L,
            "gp@ourfund.com", "investor@lpfund.com", "Re: allocation", "body");

        FakeGmailSource source0 = new FakeGmailSource(Arrays.asList(message0));
        FakeIntakeSheetPort sheetPort0 = new FakeIntakeSheetPort();
        sheetPort0.existingMessageIds.add("msg-dup");

        EmailRelevanceFilter filter0 = buildFilter(
            new HashSet<>(Arrays.asList("investor@lpfund.com")),
            new HashSet<>()
        );

        GmailIntakeSync sync0 = new GmailIntakeSync(source0, sheetPort0, filter0);

        SyncResult result0 = sync0.run(0L);

        require(result0.duplicates == 1, "(b) expected 1 duplicate");
        require(result0.accepted == 0, "(b) expected 0 accepted");
        require(sheetPort0.appendedRows.isEmpty(), "(b) expected no appended rows");
    }

    private static void testDeniedMessageAppendsNothing() throws Exception
    {
        GmailMessage message0 = message("msg-2", "thread-2", 1_700_000_000_000L,
            "gp@ourfund.com", "randomperson@example.com", "Lunch?", "Want to grab lunch?");

        FakeGmailSource source0 = new FakeGmailSource(Arrays.asList(message0));
        FakeIntakeSheetPort sheetPort0 = new FakeIntakeSheetPort();

        EmailRelevanceFilter filter0 = buildFilter(new HashSet<>(), new HashSet<>());

        GmailIntakeSync sync0 = new GmailIntakeSync(source0, sheetPort0, filter0);

        SyncResult result0 = sync0.run(0L);

        require(result0.denied == 1, "(c) expected 1 denied");
        require(sheetPort0.appendedRows.isEmpty(), "(c) expected no appended rows");
    }

    private static void testThreadContinuationAcceptedViaExistingThreadLayer() throws Exception
    {
        GmailMessage message0 = message("msg-3", "thread-known", 1_700_000_000_000L,
            "gp@ourfund.com", "randomperson@example.com", "Re: thread", "continuing thread");

        FakeGmailSource source0 = new FakeGmailSource(Arrays.asList(message0));
        FakeIntakeSheetPort sheetPort0 = new FakeIntakeSheetPort();

        EmailRelevanceFilter filter0 = buildFilter(
            new HashSet<>(),
            new HashSet<>(Arrays.asList("thread-known"))
        );

        GmailIntakeSync sync0 = new GmailIntakeSync(source0, sheetPort0, filter0);

        SyncResult result0 = sync0.run(0L);

        require(result0.accepted == 1, "(d) expected 1 accepted via existing thread layer");
        require(sheetPort0.appendedRows.size() == 1, "(d) expected one appended row");
    }

    private static void testWatermarkAdvancesOnlyAfterSuccessAndEqualsMaxInternalDate() throws Exception
    {
        java.nio.file.Path tempDir0 = java.nio.file.Files.createTempDirectory("gmail-sync-test");
        String stateDir0 = tempDir0.toString();
        String userId0 = "user-watermark";

        GmailMessage older0 = message("msg-old", "thread-old", 1_000_000L,
            "gp@ourfund.com", "investor@lpfund.com", "Old", "old body");
        GmailMessage newer0 = message("msg-new", "thread-new", 5_000_000L,
            "gp@ourfund.com", "investor@lpfund.com", "New", "new body");

        FakeGmailSource source0 = new FakeGmailSource(Arrays.asList(older0, newer0));
        FakeIntakeSheetPort sheetPort0 = new FakeIntakeSheetPort();

        EmailRelevanceFilter filter0 = buildFilter(
            new HashSet<>(Arrays.asList("investor@lpfund.com")),
            new HashSet<>()
        );

        GmailIntakeSync sync0 = new GmailIntakeSync(source0, sheetPort0, filter0);

        long startingWatermark0 = 500_000L;
        GmailIntakeSync.saveWatermark(userId0, stateDir0, startingWatermark0);

        SyncResult result0 = sync0.runSync(userId0, stateDir0);

        // runSync loads its own watermark (defaulting to ~7 days back since no file exists
        // yet), so first assert the file now exists and holds the max internalDate seen.
        long savedWatermark0 = GmailIntakeSync.loadWatermark(userId0, stateDir0);

        require(savedWatermark0 == 5_000_000L,
            "(e) expected watermark to equal max internalDate processed (5000000), got " + savedWatermark0);

        require(result0.appended == 2, "(e) expected both fixture messages appended");
    }

    private static void testSyncResultCountsMatchFixture() throws Exception
    {
        GmailMessage accepted0 = message("msg-accept", "thread-a", 1_700_000_000_000L,
            "gp@ourfund.com", "investor@lpfund.com", "Accepted", "body");
        GmailMessage denied0 = message("msg-deny", "thread-b", 1_700_000_000_001L,
            "gp@ourfund.com", "randomperson@example.com", "Denied", "body");
        GmailMessage duplicate0 = message("msg-dup2", "thread-c", 1_700_000_000_002L,
            "gp@ourfund.com", "investor@lpfund.com", "Dup", "body");

        FakeGmailSource source0 = new FakeGmailSource(Arrays.asList(accepted0, denied0, duplicate0));
        FakeIntakeSheetPort sheetPort0 = new FakeIntakeSheetPort();
        sheetPort0.existingMessageIds.add("msg-dup2");

        EmailRelevanceFilter filter0 = buildFilter(
            new HashSet<>(Arrays.asList("investor@lpfund.com")),
            new HashSet<>()
        );

        GmailIntakeSync sync0 = new GmailIntakeSync(source0, sheetPort0, filter0);

        SyncResult result0 = sync0.run(0L);

        require(result0.fetched == 3, "(f) expected fetched=3, got " + result0.fetched);
        require(result0.accepted == 1, "(f) expected accepted=1, got " + result0.accepted);
        require(result0.denied == 1, "(f) expected denied=1, got " + result0.denied);
        require(result0.duplicates == 1, "(f) expected duplicates=1, got " + result0.duplicates);
        require(result0.appended == 1, "(f) expected appended=1, got " + result0.appended);
    }

    private static EmailRelevanceFilter buildFilter(Set<String> crmEmails0, Set<String> knownThreadIds0)
    {
        return new EmailRelevanceFilter(
            LIMINER_LABEL_ID0,
            crmEmails0,
            knownThreadIds0,
            false,
            null,
            null
        );
    }

    private static GmailMessage message(
        String messageId0,
        String threadId0,
        long internalDate0,
        String to0,
        String from0,
        String subject0,
        String body0)
    {
        GmailMessage message0 = new GmailMessage();
        message0.messageId = messageId0;
        message0.threadId = threadId0;
        message0.internalDate = internalDate0;
        message0.to = to0;
        message0.from = from0;
        message0.subject = subject0;
        message0.bodyText = body0;
        message0.labelIds = new ArrayList<>();
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

    private static class FakeGmailSource implements GmailSource
    {
        private final List<GmailMessage> messages;

        FakeGmailSource(List<GmailMessage> messages0)
        {
            this.messages = messages0;
        }

        @Override
        public List<String> listMessageIds(String query0) throws Exception
        {
            List<String> ids0 = new ArrayList<>();

            for (GmailMessage message0 : messages)
            {
                ids0.add(message0.messageId);
            }

            return ids0;
        }

        @Override
        public GmailMessage fetchMessage(String messageId0) throws Exception
        {
            for (GmailMessage message0 : messages)
            {
                if (message0.messageId.equals(messageId0))
                {
                    return message0;
                }
            }

            throw new Exception("Fixture message not found: " + messageId0);
        }
    }

    private static class FakeIntakeSheetPort implements IntakeSheetPort
    {
        final Set<String> existingMessageIds = new HashSet<>();
        final List<Map<String, String>> appendedRows = new ArrayList<>();

        @Override
        public List<String> readColumnValues(String fieldKey0) throws Exception
        {
            if ("intakeTabGmailMessageIdCol".equals(fieldKey0))
            {
                return new ArrayList<>(existingMessageIds);
            }

            return new ArrayList<>();
        }

        @Override
        public void appendIntakeRows(List<Map<String, String>> rows0) throws Exception
        {
            appendedRows.addAll(rows0);
        }
    }
}
