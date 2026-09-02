package com.liminer.intake;

import java.util.List;

// Seam over Gmail access so GmailIntakeSync can be exercised offline.
// Production implementation is GmailClientSource (wraps GmailClient); tests use a fake.
public interface GmailSource
{
    List<String> listMessageIds(String query0) throws Exception;

    GmailMessage fetchMessage(String messageId0) throws Exception;
}
