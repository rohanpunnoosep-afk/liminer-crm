package com.liminer.intake;

import com.liminer.core.SessionContext;
import com.liminer.core.SyncResult;
import com.liminer.sheets.IntakeSheetPort;
import com.liminer.sheets.SheetsIntakeSheetPort;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Orchestrator: pulls new Gmail messages, runs each through EmailRelevanceFilter, and
// appends accepted messages as new "Email Intake" rows so the existing
// EmailIntakeProcessor.processUnprocessedIntakeRows(SessionContext) picks them up unchanged.
public class GmailIntakeSync
{
    private static final int MAX_BODY_CHARS0 = 50000;
    private static final long DEFAULT_LOOKBACK_MILLIS0 = 7L * 24 * 60 * 60 * 1000;
    private static final DateTimeFormatter GMAIL_QUERY_DATE_FORMAT0 =
        DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter INTAKE_TIMESTAMP_FORMAT0 =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final GmailSource source;
    private final IntakeSheetPort sheetPort;
    private final EmailRelevanceFilter filter;

    public GmailIntakeSync(
        GmailSource source0,
        IntakeSheetPort sheetPort0,
        EmailRelevanceFilter filter0)
    {
        this.source = source0;
        this.sheetPort = sheetPort0;
        this.filter = filter0;
    }

    // Full sync: load watermark, list+fetch, dedupe, filter, append, advance watermark.
    public SyncResult runSync(String userId0, String stateDir0) throws Exception
    {
        long lastSyncEpochMillis0 = loadWatermark(userId0, stateDir0);

        SyncResult result0 = run(lastSyncEpochMillis0);

        saveWatermark(userId0, stateDir0, result0.newWatermarkEpochMillis);

        return result0;
    }

    // Pure orchestration core, fully exercisable offline: no file I/O.
    public SyncResult run(long lastSyncEpochMillis0) throws Exception
    {
        SyncResult result0 = new SyncResult();
        result0.newWatermarkEpochMillis = lastSyncEpochMillis0;

        String query0 = "after:" + GMAIL_QUERY_DATE_FORMAT0.format(Instant.ofEpochMilli(lastSyncEpochMillis0));

        List<String> messageIds0 = source.listMessageIds(query0);
        result0.fetched = messageIds0.size();

        Set<String> existingMessageIds0 = new HashSet<>(sheetPort.readColumnValues("intakeTabGmailMessageIdCol"));

        List<Map<String, String>> rowsToAppend0 = new ArrayList<>();
        long maxInternalDate0 = lastSyncEpochMillis0;

        for (String messageId0 : messageIds0)
        {
            GmailMessage message0 = source.fetchMessage(messageId0);

            if (message0.internalDate > maxInternalDate0)
            {
                maxInternalDate0 = message0.internalDate;
            }

            if (existingMessageIds0.contains(message0.messageId))
            {
                result0.duplicates++;
                continue;
            }

            RelevanceDecision decision0 = filter.decide(message0);

            if (!decision0.include)
            {
                result0.denied++;
                continue;
            }

            result0.accepted++;
            rowsToAppend0.add(buildIntakeRow(message0));
        }

        if (!rowsToAppend0.isEmpty())
        {
            sheetPort.appendIntakeRows(rowsToAppend0);
            result0.appended = rowsToAppend0.size();
        }

        result0.newWatermarkEpochMillis = maxInternalDate0;

        return result0;
    }

    private Map<String, String> buildIntakeRow(GmailMessage message0)
    {
        Map<String, String> row0 = new HashMap<>();

        String bodyText0 = message0.bodyText == null ? "" : message0.bodyText;

        if (bodyText0.length() > MAX_BODY_CHARS0)
        {
            bodyText0 = bodyText0.substring(0, MAX_BODY_CHARS0);
        }

        row0.put("intakeTabIntakeIdCol", UUID.randomUUID().toString());
        row0.put("intakeTabGmailMessageIdCol", message0.messageId);
        row0.put("intakeTabGmailThreadIdCol", message0.threadId);
        row0.put("intakeTabIntakeTypeCol", "EMAIL");
        row0.put("intakeTabTimestampCol", INTAKE_TIMESTAMP_FORMAT0.format(Instant.ofEpochMilli(message0.internalDate)));
        row0.put("intakeTabToCol", message0.to == null ? "" : message0.to);
        row0.put("intakeTabFromCol", message0.from == null ? "" : message0.from);
        row0.put("intakeTabSubjectCol", message0.subject == null ? "" : message0.subject);
        row0.put("intakeTabBodyCol", bodyText0);

        return row0;
    }

    public static long loadWatermark(String userId0, String stateDir0) throws Exception
    {
        Path file0 = watermarkFile(userId0, stateDir0);

        if (!Files.exists(file0))
        {
            return Instant.now().toEpochMilli() - DEFAULT_LOOKBACK_MILLIS0;
        }

        String json0 = Files.readString(file0, StandardCharsets.UTF_8);
        JSONObject object0 = new JSONObject(json0);

        return object0.optLong("lastSyncEpochMillis", Instant.now().toEpochMilli() - DEFAULT_LOOKBACK_MILLIS0);
    }

    public static void saveWatermark(String userId0, String stateDir0, long epochMillis0) throws Exception
    {
        Path file0 = watermarkFile(userId0, stateDir0);

        Files.createDirectories(file0.getParent());

        JSONObject object0 = new JSONObject();
        object0.put("lastSyncEpochMillis", epochMillis0);

        Files.writeString(file0, object0.toString(), StandardCharsets.UTF_8);
    }

    private static Path watermarkFile(String userId0, String stateDir0)
    {
        return java.nio.file.Paths.get(stateDir0, userId0 + ".json");
    }

    // Production wiring: builds the filter (correspondent allowlist + known thread ids
    // read from the CRM), the SheetsApp-backed port, and the GmailClient-backed source.
    // Keyword+AI relevance layer stays disabled per this task's scope.
    public static GmailIntakeSync buildForSession(SessionContext context0) throws Exception
    {
        IntakeSheetPort sheetPort0 = new SheetsIntakeSheetPort(context0);

        Set<String> crmEmails0 = new HashSet<>();

        for (String rawEmail0 : sheetPort0.readColumnValues("mainTabContact1EmailCol"))
        {
            crmEmails0.add(EmailRelevanceFilter.normalizeEmail(rawEmail0));
        }

        for (String rawEmail0 : sheetPort0.readColumnValues("mainTabContact2EmailCol"))
        {
            crmEmails0.add(EmailRelevanceFilter.normalizeEmail(rawEmail0));
        }

        Set<String> knownThreadIds0 = new HashSet<>(sheetPort0.readColumnValues("intakeTabGmailThreadIdCol"));

        String liminerLabelId0 = System.getenv("LIMINER_GMAIL_LABEL");

        EmailRelevanceFilter filter0 = new EmailRelevanceFilter(
            liminerLabelId0,
            crmEmails0,
            knownThreadIds0,
            false,
            null,
            null
        );

        GmailSource source0 = new GmailClientSource(context0.user.userId);

        return new GmailIntakeSync(source0, sheetPort0, filter0);
    }
}
