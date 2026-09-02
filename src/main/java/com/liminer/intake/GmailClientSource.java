package com.liminer.intake;

import java.util.List;

// Production GmailSource backed by GmailClient's readonly OAuth flow.
public class GmailClientSource implements GmailSource
{
    private final String userId;

    public GmailClientSource(String userId0)
    {
        this.userId = userId0;
    }

    @Override
    public List<String> listMessageIds(String query0) throws Exception
    {
        return GmailClient.listMessageIds(userId, query0, null);
    }

    @Override
    public GmailMessage fetchMessage(String messageId0) throws Exception
    {
        return GmailClient.fetchMessage(userId, messageId0);
    }
}
