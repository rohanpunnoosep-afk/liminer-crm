package com.liminer.sheets;

// Seam over a single Google Sheets API call so SheetsApp.executeWithRetry can be
// exercised offline. Production callers pass an AbstractGoogleClientRequest via the
// overload in SheetsApp; tests pass a fake that throws scripted exceptions.
public interface SheetsCall<T>
{
    T execute() throws Exception;
}
