package com.liminer.web;

import com.liminer.core.CRMRegistry;
import com.liminer.core.CRMSchemaConfig;
import com.liminer.core.SessionContext;
import com.liminer.core.UserAccount;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Loopback test for user reset endpoint: starts a server on an ephemeral test port
 * with a fake LoginPort and FakeRegistry (never touches Google Sheets or live User DB),
 * then drives it over real HTTP to itself. Each test gets a fresh server and a
 * fresh session, because a successful reset deliberately invalidates the token.
 */
public class UserResetTest
{
    private static final int TEST_PORT = 7998;
    private static final String BASE_URL = "http://127.0.0.1:" + TEST_PORT;
    private static int deleteCount = 0;
    private static WebServer server;
    private String token;

    @BeforeEach
    void startServer() throws Exception
    {
        deleteCount = 0;

        WebServer.LoginPort fakeLogin = email ->
        {
            if ("test@example.com".equals(email))
            {
                UserAccount user = new UserAccount(
                    "user_test", email, "Test Fund", "config_test",
                    new java.util.ArrayList<>(), new java.util.ArrayList<>(),
                    "", "", "", "", "", "", "", "", ""
                );

                CRMSchemaConfig config = new CRMSchemaConfig("config_test", "user_test", "TestCRM", "sheet_test");

                return new SessionContext(user, config);
            }

            throw new Exception("no such user");
        };

        CRMRegistry.setFakeRegistry(() -> {
            deleteCount++;
            return "Successfully deleted user.";
        });

        server = new WebServer(fakeLogin);
        server.start(TEST_PORT);

        token = extractJsonField(post("/api/login", "{\"email\":\"test@example.com\"}"), "token");
        check("token obtained", token != null && token.length() > 0);
    }

    @AfterEach
    void stopServer()
    {
        if (server != null)
        {
            server.stop();
        }

        CRMRegistry.clearFakeRegistry();
    }

    @Test
    void resetWithoutATokenIsUnauthorized() throws Exception
    {
        check("reset without token -> 401",
            postStatus("/api/user/reset", "{\"confirm\":\"test@example.com\"}", null) == 401);
    }

    @Test
    void resetWithAnEmptyConfirmationIsRejected() throws Exception
    {
        check("reset with empty confirm -> 400",
            postStatus("/api/user/reset", "{\"confirm\":\"\"}", token) == 400);
    }

    @Test
    void resetWithTheWrongConfirmationIsRejected() throws Exception
    {
        check("reset with wrong confirm -> 400",
            postStatus("/api/user/reset", "{\"confirm\":\"wrong@example.com\"}", token) == 400);
    }

    @Test
    void resetUsesTheSessionEmailAndIgnoresTheBody() throws Exception
    {
        int status0 = postStatus("/api/user/reset",
            "{\"confirm\":\"test@example.com\",\"email\":\"attacker@example.com\"}", token);

        check("reset ignores email in body, uses session", status0 == 200);
        check("deleteUser called exactly once", deleteCount == 1);
    }

    @Test
    void aSuccessfulResetInvalidatesTheToken() throws Exception
    {
        check("reset succeeds",
            postStatus("/api/user/reset", "{\"confirm\":\"test@example.com\"}", token) == 200);

        check("token invalid after reset -> 401",
            postStatus("/api/user/reset", "{\"confirm\":\"test@example.com\"}", token) == 401);
    }

    private static void check(String label, boolean condition)
    {
        assertTrue(condition, label);
    }

    private static String post(String path, String jsonBody) throws Exception
    {
        HttpURLConnection conn = openPost(path, jsonBody, null);
        return readBody(conn);
    }

    private static int postStatus(String path, String jsonBody, String token) throws Exception
    {
        HttpURLConnection conn = openPost(path, jsonBody, token);
        readBody(conn);
        return conn.getResponseCode();
    }

    private static HttpURLConnection openPost(String path, String jsonBody, String token) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

        if (token != null)
        {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }

        try (OutputStream os = conn.getOutputStream())
        {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        return conn;
    }

    private static String readBody(HttpURLConnection conn) throws Exception
    {
        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();

        if (stream == null)
        {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        try (InputStream is = stream)
        {
            byte[] buf = new byte[4096];
            int n;

            while ((n = is.read(buf)) != -1)
            {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }

        return sb.toString();
    }

    private static String extractJsonField(String json, String field)
    {
        org.json.JSONObject obj = new org.json.JSONObject(json);
        return obj.optString(field, null);
    }
}
