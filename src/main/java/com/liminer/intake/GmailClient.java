package com.liminer.intake;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.json.JsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.util.store.FileDataStoreFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

public class GmailClient
{
    private static final String APPLICATION_NAME = "Liminer Gmail Intake";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH0 = "tokens-gmail";
    private static final List<String> SCOPES = Collections.singletonList(GmailScopes.GMAIL_READONLY);

    private static final int MAX_MESSAGE_IDS0 = 500;
    private static final int MAX_BODY_TEXT_LENGTH0 = 40000;

    public static List<String> listMessageIds(String userId0, String query0, String labelId0) throws Exception
    {
        Gmail service0 = getGmailService(userId0);

        ArrayList<String> messageIds0 = new ArrayList<>();
        String pageToken0 = null;

        do
        {
            Gmail.Users.Messages.List request0 = service0.users().messages().list("me");

            if (query0 != null && query0.trim().length() > 0)
            {
                request0.setQ(query0);
            }

            if (labelId0 != null && labelId0.trim().length() > 0)
            {
                request0.setLabelIds(Collections.singletonList(labelId0));
            }

            if (pageToken0 != null)
            {
                request0.setPageToken(pageToken0);
            }

            ListMessagesResponse response0 = request0.execute();

            if (response0.getMessages() != null)
            {
                for (Message message0 : response0.getMessages())
                {
                    messageIds0.add(message0.getId());

                    if (messageIds0.size() >= MAX_MESSAGE_IDS0)
                    {
                        return messageIds0;
                    }
                }
            }

            pageToken0 = response0.getNextPageToken();
        }
        while (pageToken0 != null);

        return messageIds0;
    }

    public static GmailMessage fetchMessage(String userId0, String messageId0) throws Exception
    {
        Gmail service0 = getGmailService(userId0);

        Message message0 = service0.users().messages().get("me", messageId0).setFormat("full").execute();

        return parseMessage(message0);
    }

    public static GmailMessage parseMessage(Message message0)
    {
        GmailMessage result0 = new GmailMessage();

        if (message0 == null)
        {
            return result0;
        }

        result0.messageId = message0.getId() == null ? "" : message0.getId();
        result0.threadId = message0.getThreadId() == null ? "" : message0.getThreadId();
        result0.internalDate = message0.getInternalDate() == null ? 0L : message0.getInternalDate();
        result0.snippet = message0.getSnippet() == null ? "" : message0.getSnippet();

        if (message0.getLabelIds() != null)
        {
            result0.labelIds = new ArrayList<>(message0.getLabelIds());
        }

        MessagePart payload0 = message0.getPayload();

        result0.from = extractHeader(payload0, "From");
        result0.to = extractHeader(payload0, "To");
        result0.subject = extractHeader(payload0, "Subject");

        String bodyText0 = extractBodyText(payload0);

        if (bodyText0.length() > MAX_BODY_TEXT_LENGTH0)
        {
            bodyText0 = bodyText0.substring(0, MAX_BODY_TEXT_LENGTH0);
        }

        result0.bodyText = bodyText0;

        return result0;
    }

    public static String extractHeader(MessagePart part0, String name0)
    {
        if (part0 == null || part0.getHeaders() == null || name0 == null)
        {
            return "";
        }

        for (MessagePartHeader header0 : part0.getHeaders())
        {
            if (header0.getName() != null && header0.getName().equalsIgnoreCase(name0))
            {
                return header0.getValue() == null ? "" : header0.getValue();
            }
        }

        return "";
    }

    public static String extractBodyText(MessagePart part0)
    {
        if (part0 == null)
        {
            return "";
        }

        String plainText0 = findBodyByMimeType(part0, "text/plain");

        if (plainText0 != null)
        {
            return plainText0;
        }

        String htmlText0 = findBodyByMimeType(part0, "text/html");

        if (htmlText0 != null)
        {
            return htmlToText(htmlText0);
        }

        return "";
    }

    private static String findBodyByMimeType(MessagePart part0, String mimeType0)
    {
        if (part0 == null)
        {
            return null;
        }

        if (mimeType0.equalsIgnoreCase(part0.getMimeType()))
        {
            return decodeBodyData(part0.getBody());
        }

        if (part0.getParts() != null)
        {
            for (MessagePart childPart0 : part0.getParts())
            {
                String found0 = findBodyByMimeType(childPart0, mimeType0);

                if (found0 != null)
                {
                    return found0;
                }
            }
        }

        return null;
    }

    private static String decodeBodyData(MessagePartBody body0)
    {
        if (body0 == null || body0.getData() == null || body0.getData().trim().length() == 0)
        {
            return null;
        }

        try
        {
            byte[] decoded0 = Base64.getUrlDecoder().decode(body0.getData());
            return new String(decoded0, java.nio.charset.StandardCharsets.UTF_8);
        }
        catch (Exception exception0)
        {
            return null;
        }
    }

    public static String htmlToText(String html0)
    {
        if (html0 == null)
        {
            return "";
        }

        String text0 = html0;

        text0 = text0.replaceAll("(?i)<(br|/p|/div|/tr)[^>]*>", "\n");
        text0 = text0.replaceAll("(?is)<script.*?</script>", "");
        text0 = text0.replaceAll("(?is)<style.*?</style>", "");
        text0 = text0.replaceAll("(?s)<[^>]+>", "");

        text0 = text0.replace("&nbsp;", " ");
        text0 = text0.replace("&amp;", "&");
        text0 = text0.replace("&lt;", "<");
        text0 = text0.replace("&gt;", ">");
        text0 = text0.replace("&quot;", "\"");
        text0 = text0.replace("&#39;", "'");

        text0 = text0.replaceAll("[ \\t]+", " ");
        text0 = text0.replaceAll("\\n[ \\t]+", "\n");
        text0 = text0.replaceAll("\\n{3,}", "\n\n");

        return text0.trim();
    }

    private static Gmail getGmailService(String userId0) throws Exception
    {
        final NetHttpTransport httpTransport0 = GoogleNetHttpTransport.newTrustedTransport();

        Credential credential0 = getCredentials(httpTransport0, userId0);

        return new Gmail.Builder(httpTransport0, JSON_FACTORY, credential0)
            .setApplicationName(APPLICATION_NAME)
            .build();
    }

    private static Credential getCredentials(final NetHttpTransport httpTransport0, String userId0) throws Exception
    {
        InputStream in0 = GmailClient.class.getResourceAsStream("/credentials.json");

        GoogleClientSecrets clientSecrets0 = GoogleClientSecrets.load(
            JSON_FACTORY,
            new InputStreamReader(in0)
        );

        String tokensPath0 = TOKENS_DIRECTORY_PATH0 + "/" + userId0;

        GoogleAuthorizationCodeFlow flow0 = new GoogleAuthorizationCodeFlow.Builder(
            httpTransport0,
            JSON_FACTORY,
            clientSecrets0,
            SCOPES
        )
        .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(tokensPath0)))
        .setAccessType("offline")
        .build();

        LocalServerReceiver receiver0 = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow0, receiver0).authorize("user");
    }
}
