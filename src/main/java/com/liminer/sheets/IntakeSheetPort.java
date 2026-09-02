package com.liminer.sheets;

import java.util.List;
import java.util.Map;

// Seam over CRM sheet access so GmailIntakeSync can be exercised offline.
// Production implementation is SheetsIntakeSheetPort (SheetsApp-backed, header-map-first,
// column-by-column); tests use an in-memory fake.
public interface IntakeSheetPort
{
    // Reads every existing value in the sheet column registered under fieldKey0
    // (a CRMFieldRegistry key, e.g. "intakeTabGmailMessageIdCol" or
    // "mainTabContact1EmailCol"). Single-column read only.
    List<String> readColumnValues(String fieldKey0) throws Exception;

    // Appends new intake rows below the existing data, one write per column
    // (never a wide row rectangle). Each row map is keyed by intake CRMFieldRegistry key.
    void appendIntakeRows(List<Map<String, String>> rows0) throws Exception;
}
