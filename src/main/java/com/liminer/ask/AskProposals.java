package com.liminer.ask;

// The one place a proposed CRM write is recorded for the ask flow. Every staging
// caller (propose_cell_update and the programmatic interaction recorder) goes
// through here so row identity lookup and same-cell de-duplication behave
// identically, and so nothing in com.liminer.ask can write to a sheet by accident.
public class AskProposals
{
    // Stages one cell change and returns it. A second stage for the same
    // (row, column) replaces the first rather than appending a duplicate; the
    // original beforeValue is kept so the table still shows the true CRM value.
    public static ProposedChange stage(
        AskContext context,
        int row0,
        String header0,
        String beforeValue0,
        String newValue0) throws Exception
    {
        String fundName0 = readIdentityCell(context, row0, context.fundNameHeader);
        String contactFirstName0 = readIdentityCell(context, row0, context.contactFirstNameHeader);

        ProposedChange change0 = new ProposedChange(
            row0,
            fundName0,
            contactFirstName0,
            header0,
            beforeValue0 == null ? "" : beforeValue0,
            newValue0 == null ? "" : newValue0
        );

        for (int i0 = 0; i0 < context.proposals.size(); i0++)
        {
            ProposedChange existing0 = context.proposals.get(i0);

            if (existing0.row == row0 && existing0.column.equals(header0))
            {
                change0.beforeValue = existing0.beforeValue;
                context.proposals.set(i0, change0);
                return change0;
            }
        }

        context.proposals.add(change0);
        return change0;
    }

    private static String readIdentityCell(AskContext context, int row0, String header0) throws Exception
    {
        if (header0 == null || header0.trim().isEmpty())
        {
            return "";
        }

        Integer col0 = context.headerMap.get(header0);

        if (col0 == null)
        {
            return "";
        }

        String value0 = context.port.readCell(row0, col0);
        return value0 == null ? "" : value0;
    }
}
