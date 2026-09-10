package com.liminer.scout;

import com.liminer.core.InvestorProfile;
import com.liminer.enrich.BrightDataLinkedInClient;
import com.liminer.enrich.BrightDataSerpClient;
import com.liminer.enrich.LinkedInUrlExtractor;
import com.liminer.enrich.SerpResult;
import com.liminer.pipeline.InvestorProfileExtractor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Candidates are appended to the CRM in small batches while discovery is still running,
 * so a GP does not wait out a fifteen minute run before seeing a single row.
 *
 * These tests drive the real discovery loop with stand-in search collaborators: no
 * network, no spreadsheet. LinkedIn scraping and profile extraction are off, so a
 * candidate is built from the SERP result alone.
 */
public class CandidateBatchWriteTest
{
    private static class FakeQueries extends SearchTermGenerator
    {
        @Override
        public ArrayList<String> generateCandidateDiscoveryQueries(ArrayList<InvestorProfile> seedProfiles0)
        {
            ArrayList<String> queries0 = new ArrayList<String>();
            queries0.add("one query is enough");
            return queries0;
        }
    }

    private static class FakeSerp extends BrightDataSerpClient
    {
        private final int resultCount0;

        FakeSerp(int resultCount0)
        {
            this.resultCount0 = resultCount0;
        }

        @Override
        public ArrayList<SerpResult> search(String query0, int maxResults0)
        {
            ArrayList<SerpResult> results0 = new ArrayList<SerpResult>();

            for (int i = 0; i < resultCount0; i++)
            {
                results0.add(new SerpResult(
                    "Person " + i + " - Partner at Fund " + i + " | LinkedIn",
                    "https://www.linkedin.com/in/person-" + i,
                    "Partner at Fund " + i,
                    i + 1,
                    query0
                ));
            }

            return results0;
        }
    }

    private static CandidateDiscoveryProcessor processorWith(int serpResults0)
    {
        return new CandidateDiscoveryProcessor(
            new FakeQueries(),
            new FakeSerp(serpResults0),
            new LinkedInUrlExtractor(),
            new BrightDataLinkedInClient(),
            new InvestorProfileExtractor()
        );
    }

    private static ArrayList<InvestorProfile> oneSeed()
    {
        ArrayList<InvestorProfile> seeds0 = new ArrayList<InvestorProfile>();
        seeds0.add(new InvestorProfile());
        return seeds0;
    }

    @Test
    public void writesEveryTwoCandidatesInsteadOfOnceAtTheEnd() throws Exception
    {
        ArrayList<Integer> batchSizes0 = new ArrayList<Integer>();

        processorWith(5).discoverCandidatesInBatches(
            oneSeed(), 10, 10, 2,
            batch0 -> batchSizes0.add(batch0.size())
        );

        assertEquals(3, batchSizes0.size(), "five candidates at two per write is three batches");
        assertEquals(2, batchSizes0.get(0).intValue());
        assertEquals(2, batchSizes0.get(1).intValue());
        assertEquals(1, batchSizes0.get(2).intValue(), "the remainder is still written");
    }

    @Test
    public void firstBatchArrivesBeforeTheRunFinishes() throws Exception
    {
        final ArrayList<Integer> seenAfterCandidates0 = new ArrayList<Integer>();
        final int[] built0 = { 0 };

        processorWith(6).discoverCandidatesInBatches(
            oneSeed(), 10, 10, 2,
            batch0 ->
            {
                built0[0] += batch0.size();
                seenAfterCandidates0.add(built0[0]);
            }
        );

        assertTrue(seenAfterCandidates0.size() > 1, "more than one write happened");
        assertEquals(2, seenAfterCandidates0.get(0).intValue(),
            "the first write lands after two candidates, not after all six");
    }

    @Test
    public void zeroFlushSizeKeepsTheSingleWriteAtTheEnd() throws Exception
    {
        ArrayList<Integer> batchSizes0 = new ArrayList<Integer>();

        processorWith(4).discoverCandidatesInBatches(
            oneSeed(), 10, 10, 0,
            batch0 -> batchSizes0.add(batch0.size())
        );

        assertEquals(1, batchSizes0.size(), "no incremental writes were requested");
        assertEquals(4, batchSizes0.get(0).intValue());
    }

    @Test
    public void everyDiscoveredCandidateIsHandedToTheSinkExactlyOnce() throws Exception
    {
        final int[] total0 = { 0 };

        ArrayList<CandidateInvestor> all0 = processorWith(7).discoverCandidatesInBatches(
            oneSeed(), 10, 10, 2,
            batch0 -> total0[0] += batch0.size()
        );

        assertEquals(all0.size(), total0[0], "nothing is dropped or written twice");
    }
}
