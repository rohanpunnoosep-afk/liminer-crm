package com.liminer.sheets;

// Seam over Thread.sleep so retry-backoff tests can assert the delay schedule
// without actually waiting ~62 seconds. Production uses Thread::sleep.
public interface RetrySleeper
{
    void sleep(long millis0) throws InterruptedException;
}
