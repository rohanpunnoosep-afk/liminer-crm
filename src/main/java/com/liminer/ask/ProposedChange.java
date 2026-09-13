package com.liminer.ask;

import org.json.JSONObject;

public class ProposedChange
{
    public int row;
    public String fundName;
    public String contactFirstName;
    public String column;
    public String beforeValue;
    public String afterValue;

    public ProposedChange(
        int row,
        String fundName,
        String contactFirstName,
        String column,
        String beforeValue,
        String afterValue)
    {
        this.row = row;
        this.fundName = fundName;
        this.contactFirstName = contactFirstName;
        this.column = column;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
    }

    public JSONObject toJson()
    {
        JSONObject json0 = new JSONObject();
        json0.put("row", row);
        json0.put("fundName", fundName);
        json0.put("contactFirstName", contactFirstName);
        json0.put("column", column);
        json0.put("beforeValue", beforeValue);
        json0.put("afterValue", afterValue);
        return json0;
    }
}
