package com.liminer.intake;

import java.util.ArrayList;

public class GmailMessage
{
    public String messageId = "";
    public String threadId = "";
    public long internalDate = 0L;
    public String from = "";
    public String to = "";
    public String subject = "";
    public ArrayList<String> labelIds = new ArrayList<>();
    public String bodyText = "";
    public String snippet = "";
}
