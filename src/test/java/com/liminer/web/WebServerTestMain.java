package com.liminer.web;

import com.liminer.core.CRMSchemaConfig;
import com.liminer.core.SessionContext;
import com.liminer.core.UserAccount;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Loopback test for WebServer: starts a server on an ephemeral test port with a fake
 * LoginPort (never touches Google Sheets), then drives it over real HTTP to itself.
 * Prints WEB_SERVER_OK on success; exits 1 on any failure.
 */
public class WebServerTestMain
{
    private static final int TEST_PORT = 7999;
    private static final String BASE_URL = "http://127.0.0.1:" + TEST_PORT;

    public static void main(String[] args) throws Exception
    {
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

        WebServer server = new WebServer(fakeLogin);
        server.start(TEST_PORT);

        try
        {
            check("GET /api/health", get("/api/health", null).contains("ok"));

            check("GET /", get("/", null).length() > 0);

            String loginBody = post("/api/login", "{\"email\":\"test@example.com\"}");
            check("POST /api/login good email", loginBody.contains("\"token\""));

            String token = extractJsonField(loginBody, "token");
            check("token non-empty", token != null && token.length() > 0);

            int badStatus = postStatus("/api/login", "{\"email\":\"nobody@example.com\"}");
            check("POST /api/login bad email -> 401", badStatus == 401);

            String sessionBody = getWithAuth("/api/session", token);
            check("GET /api/session with token", sessionBody.contains("test@example.com"));

            int garbageStatus = getStatusWithAuth("/api/session", "garbage-token");
            check("GET /api/session with garbage token -> 401", garbageStatus == 401);

            System.out.println("WEB_SERVER_OK");
        }
        catch (Throwable t)
        {
            System.out.println("TEST FAILED: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
        finally
        {
            server.stop();
        }
    }

    private static void check(String label, boolean condition) throws Exception
    {
        if (!condition)
        {
            throw new Exception("Check failed: " + label);
        }

        System.out.println("OK: " + label);
    }

    private static String get(String path, String authHeader) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod("GET");

        if (authHeader != null)
        {
            conn.setRequestProperty("Authorization", authHeader);
        }

        return readBody(conn);
    }

    private static String getWithAuth(String path, String token) throws Exception
    {
        return get(path, "Bearer " + token);
    }

    private static int getStatusWithAuth(String path, String token) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        readBody(conn);
        return conn.getResponseCode();
    }

    private static String post(String path, String jsonBody) throws Exception
    {
        HttpURLConnection conn = openPost(path, jsonBody);
        return readBody(conn);
    }

    private static int postStatus(String path, String jsonBody) throws Exception
    {
        HttpURLConnection conn = openPost(path, jsonBody);
        readBody(conn);
        return conn.getResponseCode();
    }

    private static HttpURLConnection openPost(String path, String jsonBody) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

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
