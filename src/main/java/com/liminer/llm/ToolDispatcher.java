package com.liminer.llm;

import com.liminer.sheets.SheetCall;

public class ToolDispatcher
{
    public static String dispatch(ToolCall toolCall0, SheetCall sheetCall0) throws Exception
    {
        ToolSpec tool0 = ToolRegistry.getToolByName(toolCall0.toolName);

        if (tool0 == null)
        {
            return "ERROR: Unknown tool: " + toolCall0.toolName;
        }

        return tool0.executor.execute(toolCall0, sheetCall0);
    }
}