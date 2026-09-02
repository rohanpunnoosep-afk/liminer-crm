package com.liminer.web;

import com.liminer.billing.CostCeilingExceededException;
import com.liminer.billing.CostMeter;
import com.liminer.brief.InvestorBriefJson;
import com.liminer.brief.InvestorBriefPdfRenderer;
import com.liminer.core.CRMRegistry;
import com.liminer.core.SessionContext;
import com.liminer.sheets.SheetsApp;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * First web entry point for Liminer AI (the terminal workflow in AgentMain.java is
 * untouched and remains a separate, fully independent entry point).
 *
 * Binds 127.0.0.1 on a configurable port (env LIMINER_PORT, default 7070 — never 8888,
 * which the Google OAuth LocalServerReceiver uses), serves the static frontend from
 * classpath /public, and exposes a small JSON API used by that frontend.
 *
 * Session tokens are held only in an in-memory map (ConcurrentHashMap) and are lost on
 * restart. That is acceptable for this localhost v1; a persistent session store can be
 * added later without changing the API shape.
 *
 * Start with:
 *   mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "target/classes:$(cat cp.txt)" WebServer
 */
public class WebServer
{
    public interface LoginPort
    {
        SessionContext login(String email) throws Exception;
    }

    public interface OnboardPort
    {
        JSONObject detect(String spreadsheetId, String[] possibleTabNames) throws Exception;
        SessionContext commit(JSONObject input, JSONObject approvedSchema) throws Exception;
        JSONObject plan(JSONObject input, JSONObject approvedSchema) throws Exception;
    }

    public interface BriefPort
    {
        // Summaries only ({rowNumber, contactName, fundName, asOfDate, hasBrief}) for rows
        // with a non-empty stored brief. Never includes full brief bodies.
        JSONArray list(SessionContext context) throws Exception;

        // The stored brief JSON for one row, or null when that row has no brief.
        JSONObject get(SessionContext context, int row) throws Exception;
    }

    public static final int DEFAULT_PORT = 7070;
    private static final int MAX_JOB_OUTPUT_CHARS = 200_000;
    private static final int MAX_COLUMNS = 200;
    private static final int MAX_CRM_ROWS = 500;

    private static class Job
    {
        String id;
        String workflowId;
        String sessionToken;
        volatile String status = "QUEUED";
        volatile String startedAt;
        volatile String finishedAt;
        volatile String output = "";
        volatile String summary = "";
        volatile JSONObject cost = null;
    }

    private final LoginPort loginPort;
    private final WorkflowRegistry workflowRegistry;
    private final OnboardPort onboardPort;
    private final BriefPort briefPort;
    private final ConcurrentHashMap<String, SessionContext> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    // Pending onboarding drafts (detect response + submitted input) awaiting confirm.
    // Like sessions, these are in-memory only and are lost on restart.
    private final ConcurrentHashMap<String, JSONObject> onboardDrafts = new ConcurrentHashMap<>();
    private final ExecutorService workflowExecutor = Executors.newSingleThreadExecutor();
    private Javalin app;

    public WebServer(LoginPort loginPort)
    {
        this(loginPort, WorkflowRegistry.buildProductionRegistry());
    }

    public WebServer(LoginPort loginPort, WorkflowRegistry workflowRegistry)
    {
        this(loginPort, workflowRegistry, defaultOnboardPort());
    }

    public WebServer(LoginPort loginPort, WorkflowRegistry workflowRegistry, OnboardPort onboardPort)
    {
        this(loginPort, workflowRegistry, onboardPort, defaultBriefPort());
    }

    public WebServer(LoginPort loginPort, WorkflowRegistry workflowRegistry, OnboardPort onboardPort, BriefPort briefPort)
    {
        this.loginPort = loginPort;
        this.workflowRegistry = workflowRegistry;
        this.onboardPort = onboardPort;
        this.briefPort = briefPort;
    }

    private static OnboardPort defaultOnboardPort()
    {
        return new OnboardPort()
        {
            @Override
            public JSONObject detect(String spreadsheetId, String[] possibleTabNames) throws Exception
            {
                return OnboardService.detectSchemaProposal(spreadsheetId, possibleTabNames);
            }

            @Override
            public SessionContext commit(JSONObject input, JSONObject approvedSchema) throws Exception
            {
                return OnboardService.commitOnboarding(input, approvedSchema);
            }

            @Override
            public JSONObject plan(JSONObject input, JSONObject approvedSchema) throws Exception
            {
                String spreadsheetId = input.optString("spreadsheetId", "");
                return OnboardService.planFromApprovedSchema(spreadsheetId, approvedSchema).toJson();
            }
        };
    }

    private static BriefPort defaultBriefPort()
    {
        return new BriefPort()
        {
            @Override
            public JSONArray list(SessionContext context) throws Exception
            {
                JSONArray result = new JSONArray();

                if (context == null || context.config == null)
                {
                    return result;
                }

                String spreadsheetId = context.config.spreadsheetId;
                String tabName = context.config.mainTabName;
                int headerRow = context.config.mainTabHeaderRow;
                int dataStartRow = context.config.mainTabDataStartRow;

                HashMap<String, Integer> headerMap = SheetsApp.buildHeaderMap(spreadsheetId, tabName, headerRow, MAX_COLUMNS);
                int col = SheetsApp.findColumnInHeaderMap(headerMap, context.config.getCol("mainTabInvestorBriefJsonCol"));

                if (col < 1)
                {
                    return result;
                }

                String[][] briefCol = SheetsApp.readRangeMatrix(
                    spreadsheetId, tabName, dataStartRow, col, dataStartRow + MAX_CRM_ROWS - 1, col);

                if (briefCol == null)
                {
                    return result;
                }

                for (int i = 0; i < briefCol.length; i++)
                {
                    String cell = briefCol[i] != null && briefCol[i].length > 0 ? briefCol[i][0] : null;

                    if (cell == null || cell.trim().isEmpty())
                    {
                        continue;
                    }

                    JSONObject brief = InvestorBriefJson.parseBlobObject(cell);

                    if (brief == null)
                    {
                        continue;
                    }

                    result.put(briefSummary(dataStartRow + i, brief));
                }

                return result;
            }

            @Override
            public JSONObject get(SessionContext context, int row) throws Exception
            {
                if (context == null || context.config == null)
                {
                    return null;
                }

                String spreadsheetId = context.config.spreadsheetId;
                String tabName = context.config.mainTabName;
                int headerRow = context.config.mainTabHeaderRow;

                HashMap<String, Integer> headerMap = SheetsApp.buildHeaderMap(spreadsheetId, tabName, headerRow, MAX_COLUMNS);
                int col = SheetsApp.findColumnInHeaderMap(headerMap, context.config.getCol("mainTabInvestorBriefJsonCol"));

                if (col < 1)
                {
                    return null;
                }

                String[][] cell = SheetsApp.readRangeMatrix(spreadsheetId, tabName, row, col, row, col);
                String value = cell != null && cell.length > 0 && cell[0] != null && cell[0].length > 0 ? cell[0][0] : null;

                if (value == null || value.trim().isEmpty())
                {
                    return null;
                }

                return InvestorBriefJson.parseBlobObject(value);
            }
        };
    }

    private static JSONObject briefSummary(int rowNumber, JSONObject brief)
    {
        JSONObject contact = brief.optJSONObject("contactAndFirmProfile");
        if (contact == null) contact = new JSONObject();

        String contactName = (contact.optString("firstName", "").trim() + " "
            + contact.optString("lastName", "").trim()).trim();

        JSONObject entry = new JSONObject();
        entry.put("rowNumber", rowNumber);
        entry.put("contactName", contactName);
        entry.put("fundName", contact.optString("fundName", ""));
        entry.put("asOfDate", brief.optString("asOfDate", ""));
        entry.put("hasBrief", true);
        return entry;
    }

    private static String briefDownloadFilename(JSONObject brief)
    {
        JSONObject contact = brief == null ? null : brief.optJSONObject("contactAndFirmProfile");
        if (contact == null) contact = new JSONObject();

        String last = sanitizeFilenamePart(contact.optString("lastName", ""));
        String first = sanitizeFilenamePart(contact.optString("firstName", ""));
        String fund = sanitizeFilenamePart(contact.optString("fundName", ""));

        StringBuilder sb = new StringBuilder();
        if (!last.isEmpty()) sb.append(last);
        if (!first.isEmpty()) { if (sb.length() > 0) sb.append('_'); sb.append(first); }
        if (!fund.isEmpty()) { if (sb.length() > 0) sb.append('_'); sb.append(fund); }
        if (sb.length() == 0) sb.append("Investor_Brief");
        sb.append(".pdf");
        return sb.toString();
    }

    private static String sanitizeFilenamePart(String s)
    {
        return s == null ? "" : s.trim().replaceAll("[^A-Za-z0-9._-]", "");
    }

    private static int parseRow(String raw)
    {
        try
        {
            return Integer.parseInt(raw.trim());
        }
        catch (Exception e)
        {
            return -1;
        }
    }

    public void start(int port)
    {
        String host = System.getenv().getOrDefault("LIMINER_HOST", "127.0.0.1");

        app = Javalin.create(cfg -> cfg.staticFiles.add("/public", Location.CLASSPATH));

        app.get("/api/health", ctx -> writeJson(ctx, new JSONObject().put("status", "ok")));

        app.post("/api/login", ctx ->
        {
            JSONObject body = new JSONObject(ctx.body());
            String email = body.optString("email", null);

            SessionContext context;

            try
            {
                context = email == null ? null : loginPort.login(email);
            }
            catch (Exception e)
            {
                context = null;
            }

            if (context == null)
            {
                ctx.status(401);
                writeJson(ctx, new JSONObject().put("error", "login failed"));
                return;
            }

            String token = UUID.randomUUID().toString();
            sessions.put(token, context);

            writeJson(ctx, new JSONObject().put("token", token).put("email", context.user.email));
        });

        app.post("/api/onboard/detect", ctx ->
        {
            JSONObject body = new JSONObject(ctx.body());

            java.util.ArrayList<String> errors = OnboardService.validateOnboardingInput(body);

            if (!errors.isEmpty())
            {
                ctx.status(400);
                writeJson(ctx, new JSONObject().put("errors", errors));
                return;
            }

            JSONObject detectResult;

            try
            {
                String spreadsheetId = body.optString("spreadsheetId", "");
                String[] possibleTabNames = OnboardService.parsePipeSeparatedArray(body.optString("possibleTabNames", ""));
                detectResult = onboardPort.detect(spreadsheetId, possibleTabNames);
            }
            catch (Exception e)
            {
                ctx.status(502);
                writeJson(ctx, new JSONObject().put("error", e.getMessage() == null ? e.toString() : e.getMessage()));
                return;
            }

            String draftId = UUID.randomUUID().toString();

            JSONObject draft = new JSONObject();
            draft.put("input", body);
            draft.put("schema", detectResult.optJSONObject("schema"));
            onboardDrafts.put(draftId, draft);

            JSONObject response = new JSONObject();
            response.put("draftId", draftId);
            response.put("schema", detectResult.optJSONObject("schema"));
            response.put("tabs", detectResult.optJSONArray("tabs"));
            response.put("fields", OnboardService.describeMappableFields());

            writeJson(ctx, response);
        });

        app.post("/api/onboard/preview", ctx ->
        {
            JSONObject body = new JSONObject(ctx.body());
            String draftId = body.optString("draftId", null);

            JSONObject draft = draftId == null ? null : onboardDrafts.get(draftId);

            if (draft == null)
            {
                ctx.status(404);
                writeJson(ctx, new JSONObject().put("error", "unknown draft"));
                return;
            }

            JSONObject schema = body.optJSONObject("schema");

            java.util.ArrayList<String> errors = new java.util.ArrayList<>();

            if (schema == null
                || schema.optString("mainTabName", "").trim().isEmpty())
            {
                errors.add("schema.mainTabName is required");
            }

            if (schema == null
                || schema.optString("intakeTabName", "").trim().isEmpty())
            {
                errors.add("schema.intakeTabName is required");
            }

            if (!errors.isEmpty())
            {
                ctx.status(400);
                writeJson(ctx, new JSONObject().put("errors", errors));
                return;
            }

            JSONObject plan;

            try
            {
                // Preview must NEVER consume the draft — it stays previewable
                // repeatedly and still confirmable afterward.
                plan = onboardPort.plan(draft.optJSONObject("input"), schema);
            }
            catch (Exception e)
            {
                ctx.status(502);
                writeJson(ctx, new JSONObject().put("error", e.getMessage() == null ? e.toString() : e.getMessage()));
                return;
            }

            writeJson(ctx, plan);
        });

        app.post("/api/onboard/confirm", ctx ->
        {
            JSONObject body = new JSONObject(ctx.body());
            String draftId = body.optString("draftId", null);

            JSONObject draft = draftId == null ? null : onboardDrafts.get(draftId);

            if (draft == null)
            {
                ctx.status(404);
                writeJson(ctx, new JSONObject().put("error", "unknown draft"));
                return;
            }

            JSONObject approvedSchema = body.optJSONObject("schema");

            java.util.ArrayList<String> errors = new java.util.ArrayList<>();

            if (approvedSchema == null
                || approvedSchema.optString("mainTabName", "").trim().isEmpty())
            {
                errors.add("schema.mainTabName is required");
            }

            if (approvedSchema == null
                || approvedSchema.optString("intakeTabName", "").trim().isEmpty())
            {
                errors.add("schema.intakeTabName is required");
            }

            if (!errors.isEmpty())
            {
                ctx.status(400);
                writeJson(ctx, new JSONObject().put("errors", errors));
                return;
            }

            SessionContext context;

            try
            {
                context = onboardPort.commit(draft.optJSONObject("input"), approvedSchema);
            }
            catch (Exception e)
            {
                ctx.status(500);
                writeJson(ctx, new JSONObject().put("error", e.getMessage() == null ? e.toString() : e.getMessage()));
                return;
            }

            onboardDrafts.remove(draftId);

            String token = UUID.randomUUID().toString();
            sessions.put(token, context);

            JSONObject response = new JSONObject();
            response.put("token", token);
            response.put("email", context.user.email);
            response.put("userId", context.user.userId);
            writeJson(ctx, response);
        });

        app.get("/api/session", ctx ->
        {
            String header = ctx.header("Authorization");
            String token = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length())
                : null;

            SessionContext context = token == null ? null : sessions.get(token);

            if (context == null)
            {
                ctx.status(401);
                writeJson(ctx, new JSONObject().put("error", "not logged in"));
                return;
            }

            writeJson(ctx, new JSONObject().put("email", context.user.email));
        });

        app.get("/api/workflows", ctx ->
        {
            String token = authenticate(ctx);

            if (token == null)
            {
                return;
            }

            JSONArray array = new JSONArray();

            for (WorkflowRegistry.WorkflowInfo info : workflowRegistry.list())
            {
                array.put(info.toJson());
            }

            writeJson(ctx, new JSONObject().put("workflows", array));
        });

        app.post("/api/workflows/{id}/run", ctx ->
        {
            String token = authenticate(ctx);

            if (token == null)
            {
                return;
            }

            SessionContext context = sessions.get(token);
            String workflowId = ctx.pathParam("id");
            WorkflowRegistry.WorkflowInfo info = workflowRegistry.get(workflowId);

            if (info == null || !info.available)
            {
                ctx.status(404);
                writeJson(ctx, new JSONObject().put("error", "unknown workflow"));
                return;
            }

            for (Job existing : jobs.values())
            {
                if (existing.sessionToken.equals(token)
                    && ("QUEUED".equals(existing.status) || "RUNNING".equals(existing.status)))
                {
                    ctx.status(409);
                    writeJson(ctx, new JSONObject().put("error", "a job is already running"));
                    return;
                }
            }

            JSONObject params;

            try
            {
                String body = ctx.body();
                params = body == null || body.isEmpty() ? new JSONObject() : new JSONObject(body);
            }
            catch (Exception e)
            {
                params = new JSONObject();
            }

            Job job = new Job();
            job.id = UUID.randomUUID().toString();
            job.workflowId = workflowId;
            job.sessionToken = token;
            jobs.put(job.id, job);

            JSONObject finalParams = params;

            workflowExecutor.submit(() -> runJob(job, info, context, finalParams));

            writeJson(ctx, new JSONObject().put("jobId", job.id));
        });

        app.post("/api/workflows/{id}/plan", ctx ->
        {
            String token = authenticate(ctx);

            if (token == null)
            {
                return;
            }

            SessionContext context = sessions.get(token);
            String workflowId = ctx.pathParam("id");
            WorkflowRegistry.WorkflowInfo info = workflowRegistry.get(workflowId);

            if (info == null || !info.available)
            {
                ctx.status(404);
                writeJson(ctx, new JSONObject().put("error", "unknown workflow"));
                return;
            }

            if (info.planner == null)
            {
                ctx.status(400);
                writeJson(ctx, new JSONObject().put("error", "workflow has no dry-run plan"));
                return;
            }

            JSONObject params;

            try
            {
                String body = ctx.body();
                params = body == null || body.isEmpty() ? new JSONObject() : new JSONObject(body);
            }
            catch (Exception e)
            {
                params = new JSONObject();
            }

            JSONObject plan;

            try
            {
                plan = info.planner.plan(context, params);
            }
            catch (Exception e)
            {
                ctx.status(502);
                writeJson(ctx, new JSONObject().put("error", e.getMessage() == null ? e.toString() : e.getMessage()));
                return;
            }

            writeJson(ctx, plan);
        });

        app.get("/api/jobs/{id}", ctx ->
        {
            String token = authenticate(ctx);

            if (token == null)
            {
                return;
            }

            Job job = jobs.get(ctx.pathParam("id"));

            if (job == null)
            {
                ctx.status(404);
                writeJson(ctx, new JSONObject().put("error", "unknown job"));
                return;
            }

            JSONObject json = new JSONObject();
            json.put("id", job.id);
            json.put("workflowId", job.workflowId);
            json.put("status", job.status);
            json.put("summary", job.summary);
            json.put("startedAt", job.startedAt);
            json.put("finishedAt", job.finishedAt);
            json.put("output", job.output);
            json.put("cost", job.cost == null ? JSONObject.NULL : job.cost);

            writeJson(ctx, json);
        });

        app.get("/api/briefs", ctx ->
        {
            String token = authenticate(ctx);

            if (token == null)
            {
                return;
            }

            SessionContext context = sessions.get(token);
            JSONArray briefs;

            try
            {
                briefs = briefPort.list(context);
            }
            catch (Exception e)
            {
                ctx.status(502);
                writeJson(ctx, new JSONObject().put("error", e.getMessage() == null ? e.toString() : e.getMessage()));
                return;
            }

            writeJson(ctx, new JSONObject().put("briefs", briefs));
        });

        app.get("/api/briefs/{row}", ctx ->
        {
            String token = authenticate(ctx);

            if (token == null)
            {
                return;
            }

            SessionContext context = sessions.get(token);
            int row = parseRow(ctx.pathParam("row"));

            if (row < 1)
            {
                ctx.status(404);
                writeJson(ctx, new JSONObject().put("error", "unknown row"));
                return;
            }

            JSONObject brief;

            try
            {
                brief = briefPort.get(context, row);
            }
            catch (Exception e)
            {
                ctx.status(502);
                writeJson(ctx, new JSONObject().put("error", e.getMessage() == null ? e.toString() : e.getMessage()));
                return;
            }

            if (brief == null)
            {
                ctx.status(404);
                writeJson(ctx, new JSONObject().put("error", "no brief for this row"));
                return;
            }

            writeJson(ctx, brief);
        });

        app.get("/api/briefs/{row}/pdf", ctx ->
        {
            String token = authenticate(ctx);

            if (token == null)
            {
                return;
            }

            SessionContext context = sessions.get(token);
            int row = parseRow(ctx.pathParam("row"));

            if (row < 1)
            {
                ctx.status(404);
                writeJson(ctx, new JSONObject().put("error", "unknown row"));
                return;
            }

            JSONObject brief;

            try
            {
                brief = briefPort.get(context, row);
            }
            catch (Exception e)
            {
                ctx.status(502);
                writeJson(ctx, new JSONObject().put("error", e.getMessage() == null ? e.toString() : e.getMessage()));
                return;
            }

            if (brief == null)
            {
                ctx.status(404);
                writeJson(ctx, new JSONObject().put("error", "no brief for this row"));
                return;
            }

            byte[] pdf;

            try
            {
                pdf = InvestorBriefPdfRenderer.render(brief);
            }
            catch (Exception e)
            {
                ctx.status(500);
                writeJson(ctx, new JSONObject().put("error", e.getMessage() == null ? e.toString() : e.getMessage()));
                return;
            }

            ctx.contentType("application/pdf");
            ctx.header("Content-Disposition", "attachment; filename=\"" + briefDownloadFilename(brief) + "\"");
            ctx.result(pdf);
        });

        app.post("/api/user/reset", ctx ->
        {
            String token = authenticate(ctx);

            if (token == null)
            {
                return;
            }

            SessionContext context = sessions.get(token);

            JSONObject body;

            try
            {
                String bodyStr = ctx.body();
                body = bodyStr == null || bodyStr.isEmpty() ? new JSONObject() : new JSONObject(bodyStr);
            }
            catch (Exception e)
            {
                body = new JSONObject();
            }

            String confirm = body.optString("confirm", "").trim();
            String userEmail = context.user.email;

            if (!userEmail.equals(confirm))
            {
                ctx.status(400);
                writeJson(ctx, new JSONObject().put("error", "confirmation email does not match"));
                return;
            }

            String userId = context.user.userId;

            String result;

            try
            {
                result = CRMRegistry.deleteUser(userEmail, userId);
            }
            catch (Exception e)
            {
                ctx.status(500);
                writeJson(ctx, new JSONObject().put("error", e.getMessage() == null ? e.toString() : e.getMessage()));
                return;
            }

            sessions.remove(token);

            if (result.toLowerCase().startsWith("error"))
            {
                ctx.status(400);
                writeJson(ctx, new JSONObject().put("error", result));
                return;
            }

            writeJson(ctx, new JSONObject().put("message", result));
        });

        app.start(host, port);
    }

    private String authenticate(Context ctx)
    {
        String header = ctx.header("Authorization");
        String token = header != null && header.startsWith("Bearer ")
            ? header.substring("Bearer ".length())
            : null;

        if (token == null || !sessions.containsKey(token))
        {
            ctx.status(401);
            writeJson(ctx, new JSONObject().put("error", "not logged in"));
            return null;
        }

        return token;
    }

    private void runJob(Job job, WorkflowRegistry.WorkflowInfo info, SessionContext context, JSONObject params)
    {
        job.status = "RUNNING";
        job.startedAt = Instant.now().toString();

        CostMeter meter = new CostMeter();
        CostMeter.bind(meter);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();

        try
        {
            System.setOut(new PrintStream(capture, true));

            String result = info.handler.run(context, params);

            System.setOut(originalOut);

            String captured = capture.toString();
            String combined = captured.isEmpty() ? result : captured + "\n" + result;

            job.output = truncateOutput(combined);
            job.status = classifyResult(result);
            job.summary = summarize(result);
        }
        catch (CostCeilingExceededException e)
        {
            System.setOut(originalOut);

            String captured = capture.toString();
            String combined = captured.isEmpty() ? e.getMessage() : captured + "\n" + e.getMessage();

            job.output = truncateOutput(combined);
            job.status = "FAILED";
            job.summary = e.getMessage();
        }
        catch (Exception e)
        {
            System.setOut(originalOut);

            String captured = capture.toString();
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            String combined = captured.isEmpty() ? message : captured + "\n" + message;

            job.output = truncateOutput(combined);
            job.status = "FAILED";
            job.summary = summarize(message);
        }
        finally
        {
            System.setOut(originalOut);
            job.finishedAt = Instant.now().toString();
            job.cost = meter.toJson();
            CostMeter.unbind();
        }
    }

    private static String classifyResult(String result)
    {
        if (result == null || result.trim().isEmpty())
        {
            return "DONE";
        }

        String trimmed = result.trim();
        String lower = trimmed.toLowerCase();

        if (lower.startsWith("error:"))
        {
            return "FAILED";
        }

        if (lower.contains("no eligible rows") || lower.contains("nothing to")
            || lower.contains("no rows") || lower.contains("0 rows"))
        {
            return "NOOP";
        }

        return "DONE";
    }

    private static String summarize(String result)
    {
        if (result == null)
        {
            return "";
        }

        String[] lines = result.split("\\R");
        String lastNonBlank = "";

        for (String line : lines)
        {
            if (!line.trim().isEmpty())
            {
                lastNonBlank = line.trim();
            }
        }

        if (lastNonBlank.length() > 300)
        {
            lastNonBlank = lastNonBlank.substring(0, 300);
        }

        return lastNonBlank;
    }

    private static String truncateOutput(String output)
    {
        if (output == null)
        {
            return "";
        }

        if (output.length() <= MAX_JOB_OUTPUT_CHARS)
        {
            return output;
        }

        int keepTail = MAX_JOB_OUTPUT_CHARS - 200;
        String tail = output.substring(output.length() - keepTail);

        return "[...truncated...]\n" + tail;
    }

    private static void writeJson(Context ctx, JSONObject body)
    {
        ctx.contentType("application/json").result(body.toString());
    }

    public void stop()
    {
        if (app != null)
        {
            app.stop();
        }
        workflowExecutor.shutdownNow();
    }

    public static void main(String[] args)
    {
        int port = DEFAULT_PORT;

        try
        {
            port = Integer.parseInt(System.getenv().getOrDefault("LIMINER_PORT", String.valueOf(DEFAULT_PORT)));
        }
        catch (NumberFormatException e)
        {
            // fall back to DEFAULT_PORT
        }

        WebServer server = new WebServer(CRMRegistry::login);
        server.start(port);
    }
}
