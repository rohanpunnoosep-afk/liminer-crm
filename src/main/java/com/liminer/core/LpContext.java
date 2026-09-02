package com.liminer.core;

import com.liminer.scout.IdentityResolver;
import com.liminer.sheets.SnapshotStore;

import java.util.ArrayList;

/*
 * LpContext is the per-row input bundle handed to every Indicator.fetch(). It
 * carries: (a) the LP's raw row inputs (fund name, website, address, contacts),
 * (b) the existing LPEnrichmentProcessor tags already on the row (sector /
 * microsector / geography / allocator type / prior backed funds + the enrichment
 * date), (c) the GP's CRM interaction history for the CRM-tie leaf, (d) the
 * resolved IdentityKeys, and (e) a handle to THIS GP's own profile for fit
 * comparison.
 *
 * Plain data holder: LPScoreProcessor populates it once per row before fanning the
 * indicators out. Treat instances as read-only inside indicator threads (do not
 * mutate after construction).
 */
public class LpContext
{
    // --- Raw row inputs ---
    public int crmRowNumber = -1;
    public String fundName = "";
    public String website = "";
    public String address = "";
    public String companyLinkedInUrl = "";

    // One LP contact (CIO / Head of Private Markets / partner, etc.).
    public static class Contact
    {
        public String firstName = "";
        public String lastName = "";
        public String position = "";
        public String linkedInUrl = "";

        public Contact() {}

        public Contact(String firstName0, String lastName0, String position0, String linkedInUrl0)
        {
            this.firstName = safe(firstName0);
            this.lastName = safe(lastName0);
            this.position = safe(position0);
            this.linkedInUrl = safe(linkedInUrl0);
        }
    }

    public ArrayList<Contact> contacts = new ArrayList<Contact>();

    // --- Existing enrichment tags already on the row (LPEnrichmentProcessor) ---
    public String sectorTags = "";
    public String microsectorTags = "";
    public String geography = "";
    public String allocatorType = "";
    public String priorBackedFunds = "";
    // The date the row was last enriched — the honest asOfDate for tag-based leaves.
    public String lastEnrichedAt = "";

    // --- CRM relationship signal (for the CRM-tie leaf; zero external cost) ---
    public String interactionHistory = "";
    public String interactionRecordsJson = "";
    public String conversationStatus = "";
    public String lastContactDate = "";

    // --- Resolved identity backbone (Theme 9) ---
    public IdentityResolver.IdentityKeys identityKeys = new IdentityResolver.IdentityKeys();

    // --- THIS GP's own profile, for fit alignment scoring ---
    // Lightweight self-contained holder so indicators can compare the LP's
    // sector/stage/geo against the GP's without coupling to a heavier profile type.
    public static class GpProfile
    {
        public String fundName = "";
        public String sectors = "";
        public String microsectorTags = "";
        public String stages = "";
        public String geographies = "";
        public String investmentThesis = "";

        public GpProfile() {}
    }

    public GpProfile gpProfile = new GpProfile();

    // --- Snapshot + DealVelocity support (Task 18) ---
    // The shared SnapshotStore instance for the batch (set by LPScoreProcessor).
    public SnapshotStore snapshotStore = null;
    // The spreadsheet ID for snapshot reads (set by LPScoreProcessor).
    public String spreadsheetId = "";
    // Latest leaf results carried forward for snapshot queuing.
    public String latestRaumValue = "";
    public String latestRaumDate = "";
    public String latestRaumSourceUrl = "";
    public String latestFundCloseValue = "";
    public String latestFundCloseDate = "";
    public String latestFundCloseSourceUrl = "";

    public LpContext() {}

    private static String safe(String s0)
    {
        return s0 == null ? "" : s0;
    }
}
