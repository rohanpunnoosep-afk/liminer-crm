package com.liminer.ask;

import org.json.JSONObject;

public interface AskToolExecutor
{
    String execute(JSONObject args, AskContext context) throws Exception;
}
