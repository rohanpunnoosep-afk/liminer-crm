package com.liminer.ask;

import com.liminer.core.SessionContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class AskContext
{
    public SessionContext session;
    public AskSheetPort port;
    public HashMap<String, Integer> headerMap;
    public String fundNameHeader;
    public String contactFirstNameHeader;
    public List<ProposedChange> proposals;

    public AskContext(
        SessionContext session,
        AskSheetPort port,
        HashMap<String, Integer> headerMap,
        String fundNameHeader,
        String contactFirstNameHeader)
    {
        this.session = session;
        this.port = port;
        this.headerMap = headerMap;
        this.fundNameHeader = fundNameHeader;
        this.contactFirstNameHeader = contactFirstNameHeader;
        this.proposals = new ArrayList<>();
    }
}
