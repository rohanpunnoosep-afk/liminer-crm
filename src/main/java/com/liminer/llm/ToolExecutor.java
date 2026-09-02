package com.liminer.llm;

import com.liminer.sheets.SheetCall;

public interface ToolExecutor
{
    String execute(ToolCall toolCall0, SheetCall sheetCall0) throws Exception;
}