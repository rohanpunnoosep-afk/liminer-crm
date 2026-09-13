package com.liminer.ask;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class AskResult
{
    public String answer;
    public List<ProposedChange> proposals;

    public AskResult(String answer, List<ProposedChange> proposals)
    {
        this.answer = answer;
        this.proposals = proposals == null ? new ArrayList<>() : proposals;
    }

    public JSONObject toJson()
    {
        JSONObject json0 = new JSONObject();
        json0.put("answer", answer);

        JSONArray proposalsJson0 = new JSONArray();

        for (ProposedChange change0 : proposals)
        {
            proposalsJson0.put(change0.toJson());
        }

        json0.put("proposals", proposalsJson0);

        return json0;
    }
}
