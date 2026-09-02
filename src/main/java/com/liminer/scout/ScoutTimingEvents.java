package com.liminer.scout;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/*
 * ScoutTimingEvents — dated, typed timing/dry-powder events for one candidate,
 * consumed by ScoutSignalScorer.score() to compute PROBABILITY_NOW. Every event
 * carries the date(s) needed to compute age-based decay; the scorer never uses
 * "today" as a proxy for an event's own asOfDate (ADV amendments bunch in Q1 —
 * March data must not read as fresh in November).
 */
public class ScoutTimingEvents
{
    public List<Event> events;

    public ScoutTimingEvents()
    {
        events = new ArrayList<Event>();
    }

    public enum EventType { FORM_D, NEW_REGISTRANT, NEW_FOF_FUND, RAUM_JUMP }

    public static class Event
    {
        public EventType type;

        // FORM_D: filed on one of the adviser's fund vehicles.
        public String fundName;
        public LocalDate filedDate;
        public double offeringAmount;
        public double soldAmount;
        public LocalDate firstSaleDate;

        // NEW_REGISTRANT / NEW_FOF_FUND: month of the ScoutUniverseStore diff (YYYY-MM).
        public String month;

        // RAUM_JUMP: month of the diff (YYYY-MM) and the signed pct change (0.20 = +20%).
        public double pctChange;

        public Event() {}

        public Event(EventType type0) { this.type = type0; }

        public static Event formD(String fundName0, LocalDate filedDate0,
            double offeringAmount0, double soldAmount0, LocalDate firstSaleDate0)
        {
            Event e0 = new Event(EventType.FORM_D);
            e0.fundName = fundName0;
            e0.filedDate = filedDate0;
            e0.offeringAmount = offeringAmount0;
            e0.soldAmount = soldAmount0;
            e0.firstSaleDate = firstSaleDate0;
            return e0;
        }

        public static Event newRegistrant(String month0)
        {
            Event e0 = new Event(EventType.NEW_REGISTRANT);
            e0.month = month0;
            return e0;
        }

        public static Event newFoFFund(String month0, String fundName0)
        {
            Event e0 = new Event(EventType.NEW_FOF_FUND);
            e0.month = month0;
            e0.fundName = fundName0;
            return e0;
        }

        public static Event raumJump(String month0, double pctChange0)
        {
            Event e0 = new Event(EventType.RAUM_JUMP);
            e0.month = month0;
            e0.pctChange = pctChange0;
            return e0;
        }
    }
}
