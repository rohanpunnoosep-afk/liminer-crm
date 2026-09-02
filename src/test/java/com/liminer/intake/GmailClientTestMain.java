package com.liminer.intake;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.MessagePartBody;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;

public class GmailClientTestMain
{
    public static void main(String[] args)
    {
        try
        {
            testPlainTextSinglePart();
            testMultipartAlternativePrefersPlainText();
            testNestedMultipartHtmlOnlyFallback();
            testBase64UrlDecoding();
            testMissingSubjectHeader();
            testBodyTruncation();

            System.out.println("GMAIL_CLIENT_OK");
        }
        catch (Exception exception0)
        {
            System.out.println("GMAIL_CLIENT_TEST_FAILED: " + exception0.getMessage());
            exception0.printStackTrace();
            System.exit(1);
        }
    }

    private static void testPlainTextSinglePart() throws Exception
    {
        Message message0 = new Message();
        message0.setId("msg-1");
        message0.setThreadId("thread-1");
        message0.setInternalDate(1720000000000L);
        message0.setSnippet("Hello there");
        message0.setLabelIds(Arrays.asList("INBOX", "UNREAD"));

        MessagePart payload0 = new MessagePart();
        payload0.setMimeType("text/plain");
        payload0.setHeaders(Arrays.asList(
            header("From", "alice@example.com"),
            header("To", "bob@example.com"),
            header("Subject", "Plain text test")
        ));
        payload0.setBody(bodyOf("Hello, this is plain text."));

        message0.setPayload(payload0);

        GmailMessage parsed0 = GmailClient.parseMessage(message0);

        assertEquals("msg-1", parsed0.messageId, "messageId");
        assertEquals("thread-1", parsed0.threadId, "threadId");
        assertEquals("alice@example.com", parsed0.from, "from");
        assertEquals("bob@example.com", parsed0.to, "to");
        assertEquals("Plain text test", parsed0.subject, "subject");
        assertEquals("Hello, this is plain text.", parsed0.bodyText, "bodyText");
        assertEquals(1720000000000L, parsed0.internalDate, "internalDate");

        if (!parsed0.labelIds.contains("INBOX") || !parsed0.labelIds.contains("UNREAD"))
        {
            throw new Exception("labelIds mismatch: " + parsed0.labelIds);
        }
    }

    private static void testMultipartAlternativePrefersPlainText() throws Exception
    {
        MessagePart plainPart0 = new MessagePart();
        plainPart0.setMimeType("text/plain");
        plainPart0.setBody(bodyOf("Plain preferred body."));

        MessagePart htmlPart0 = new MessagePart();
        htmlPart0.setMimeType("text/html");
        htmlPart0.setBody(bodyOf("<p>HTML body</p>"));

        MessagePart payload0 = new MessagePart();
        payload0.setMimeType("multipart/alternative");
        payload0.setHeaders(Arrays.asList(header("Subject", "Multipart test")));
        payload0.setParts(Arrays.asList(plainPart0, htmlPart0));

        Message message0 = new Message();
        message0.setId("msg-2");
        message0.setPayload(payload0);

        GmailMessage parsed0 = GmailClient.parseMessage(message0);

        assertEquals("Plain preferred body.", parsed0.bodyText, "bodyText should prefer plain text");
    }

    private static void testNestedMultipartHtmlOnlyFallback() throws Exception
    {
        MessagePart htmlPart0 = new MessagePart();
        htmlPart0.setMimeType("text/html");
        htmlPart0.setBody(bodyOf("<div>Line one</div><div>Line two &amp; more</div>"));

        MessagePart innerMultipart0 = new MessagePart();
        innerMultipart0.setMimeType("multipart/related");
        innerMultipart0.setParts(Arrays.asList(htmlPart0));

        MessagePart payload0 = new MessagePart();
        payload0.setMimeType("multipart/mixed");
        payload0.setHeaders(Arrays.asList(header("Subject", "Nested HTML test")));
        payload0.setParts(Arrays.asList(innerMultipart0));

        Message message0 = new Message();
        message0.setId("msg-3");
        message0.setPayload(payload0);

        GmailMessage parsed0 = GmailClient.parseMessage(message0);

        if (parsed0.bodyText.contains("<div>") || parsed0.bodyText.contains("&amp;"))
        {
            throw new Exception("HTML was not stripped: " + parsed0.bodyText);
        }

        if (!parsed0.bodyText.contains("Line one") || !parsed0.bodyText.contains("Line two & more"))
        {
            throw new Exception("Stripped text missing expected content: " + parsed0.bodyText);
        }
    }

    private static void testBase64UrlDecoding() throws Exception
    {
        String original0 = "line-a>>line-b??line-c";
        String encoded0 = Base64.getUrlEncoder().withoutPadding().encodeToString(
            original0.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        if (!encoded0.contains("-") && !encoded0.contains("_"))
        {
            throw new Exception("Test fixture encoding did not exercise url-safe chars: " + encoded0);
        }

        MessagePartBody body0 = new MessagePartBody();
        body0.setData(encoded0);

        MessagePart payload0 = new MessagePart();
        payload0.setMimeType("text/plain");
        payload0.setBody(body0);

        String decoded0 = GmailClient.extractBodyText(payload0);

        assertEquals(original0, decoded0, "base64url decoded body");
    }

    private static void testMissingSubjectHeader() throws Exception
    {
        MessagePart payload0 = new MessagePart();
        payload0.setMimeType("text/plain");
        payload0.setHeaders(new ArrayList<MessagePartHeader>());
        payload0.setBody(bodyOf("No subject here."));

        Message message0 = new Message();
        message0.setId("msg-4");
        message0.setPayload(payload0);

        GmailMessage parsed0 = GmailClient.parseMessage(message0);

        assertEquals("", parsed0.subject, "missing Subject header should be empty string");
    }

    private static void testBodyTruncation() throws Exception
    {
        StringBuilder longBody0 = new StringBuilder();

        for (int i0 = 0; i0 < 45000; i0++)
        {
            longBody0.append('x');
        }

        MessagePart payload0 = new MessagePart();
        payload0.setMimeType("text/plain");
        payload0.setBody(bodyOf(longBody0.toString()));

        Message message0 = new Message();
        message0.setId("msg-5");
        message0.setPayload(payload0);

        GmailMessage parsed0 = GmailClient.parseMessage(message0);

        if (parsed0.bodyText.length() != 40000)
        {
            throw new Exception("Expected truncated length 40000, got " + parsed0.bodyText.length());
        }
    }

    private static MessagePartHeader header(String name0, String value0)
    {
        MessagePartHeader header0 = new MessagePartHeader();
        header0.setName(name0);
        header0.setValue(value0);
        return header0;
    }

    private static MessagePartBody bodyOf(String text0)
    {
        MessagePartBody body0 = new MessagePartBody();
        body0.setData(Base64.getUrlEncoder().withoutPadding().encodeToString(
            text0.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ));
        return body0;
    }

    private static void assertEquals(Object expected0, Object actual0, String label0) throws Exception
    {
        if (expected0 == null ? actual0 != null : !expected0.equals(actual0))
        {
            throw new Exception(label0 + " mismatch: expected [" + expected0 + "] but got [" + actual0 + "]");
        }
    }
}
