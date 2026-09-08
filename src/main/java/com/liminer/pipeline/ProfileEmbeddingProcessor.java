package com.liminer.pipeline;

import com.liminer.core.CRMFieldRegistry;
import com.liminer.core.ConnectionPoint;
import com.liminer.core.SessionContext;
import com.liminer.embed.CanonicalProfile;
import com.liminer.embed.CanonicalProfileBuilder;
import com.liminer.embed.ProfileVectorEncoder;
import com.liminer.embed.ProfileVectorLayout;
import com.liminer.embed.VectorCodec;
import com.liminer.intake.ConnectionPointExtractor;
import com.liminer.llm.OpenAIClient;
import com.liminer.sheets.SheetsApp;
import com.liminer.sheets.SheetsIOPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * ProfileEmbeddingProcessor is the "embed lps" / "embed-lps" workflow (task chain
 * 0170-0176): for each eligible LP row it builds a CanonicalProfile, extracts and
 * merges the connection point, encodes the weighted block vector, and writes the
 * five profile-embedding columns back column-by-column. It skips rows whose
 * canonical_hash + encoder_version already match the stored Profile Vector Meta,
 * which is the main cost control on re-runs.
 *
 * Follows the LPEnrichmentProcessor shape: build header map -> resolve columns ->
 * read each column separately -> bounded thread pool per-row work -> write each
 * column separately, carrying through the existing value for any row this run did
 * not touch so column-by-column writes never blank untouched rows.
 */
public class ProfileEmbeddingProcessor
{
    private static final int MAX_CRM_ROWS0 = 500;
    private static final int MAX_COLUMNS0 = 200;
    private static final int MAX_ROWS_TO_EMBED0 = 25;
    private static final int ROW_POOL_SIZE0 = 8;
    private static final int JSON_MAX0 = 49_000;

    private static final int OUT_CONNECTION_POINT0 = 0;
    private static final int OUT_CONNECTION_POINT_JSON0 = 1;
    private static final int OUT_CANONICAL_PROFILE_JSON0 = 2;
    private static final int OUT_PROFILE_VECTOR0 = 3;
    private static final int OUT_PROFILE_VECTOR_META0 = 4;
    private static final int OUT_FIELD_COUNT0 = 5;

    // Test seams: swappable so the workflow can be verified offline against an
    // in-memory fake sheet, a fake LLM and a fake embedder.
    public static SheetsIOPort sheetsPort = SheetsIOPort.live();
    public static CanonicalProfileBuilder profileBuilder = new CanonicalProfileBuilder();
    public static ProfileVectorEncoder vectorEncoder = new ProfileVectorEncoder();
    public static ConnectionPointExtractor connectionExtractor = new ConnectionPointExtractor();

    private static class SourceColumns
    {
        int fundNameCol0;
        int websiteCol0;
        int intelligenceJsonCol0;
        int marketIntelligenceJsonCol0;
        int investmentThesisCol0;
        int priorBackedFundsCol0;
        int sectorTagsCol0;
        int microsectorTagsCol0;
        int typeOfInvestorCol0;
        int fundLinkedInAboutCol0;
        int interactionRecordsCol0;
        int connectionPointCol0;
        int connectionPointJsonCol0;
        int canonicalProfileJsonCol0;
        int profileVectorCol0;
        int profileVectorMetaCol0;
    }

    public static String embedLpRows(SessionContext context0) throws Exception
    {
        return embedLpRows(context0, MAX_ROWS_TO_EMBED0);
    }

    public static String embedLpRows(SessionContext context0, int maxRows0) throws Exception
    {
        if (context0 == null || context0.config == null)
        {
            return "ERROR: Missing session context or config.";
        }

        String spreadsheetId0 = context0.config.spreadsheetId;
        String crmTabName0 = context0.config.mainTabName;
        int headerRow0 = context0.config.mainTabHeaderRow;

        HashMap<String, Integer> headerMap0 = sheetsPort.buildHeaderMap(
            spreadsheetId0, crmTabName0, headerRow0, MAX_COLUMNS0);

        CRMFieldRegistry.ensureProfileEmbeddingColumns(
            context0, spreadsheetId0, crmTabName0, headerRow0, headerMap0);

        SourceColumns cols0 = resolveColumns(context0, headerMap0);

        String missing0 = firstMissingColumn(cols0);
        if (missing0 != null)
        {
            return "ERROR: Missing required LP profile embedding column: " + missing0;
        }

        int startRow0 = context0.config.mainTabDataStartRow;

        String[][] fundNameColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.fundNameCol0);
        String[][] websiteColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.websiteCol0);
        String[][] intelligenceJsonColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.intelligenceJsonCol0);
        String[][] marketIntelligenceJsonColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.marketIntelligenceJsonCol0);
        String[][] investmentThesisColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.investmentThesisCol0);
        String[][] priorBackedFundsColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.priorBackedFundsCol0);
        String[][] sectorTagsColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.sectorTagsCol0);
        String[][] microsectorTagsColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.microsectorTagsCol0);
        String[][] typeOfInvestorColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.typeOfInvestorCol0);
        String[][] fundLinkedInAboutColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.fundLinkedInAboutCol0);
        String[][] interactionRecordsColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.interactionRecordsCol0);
        String[][] connectionPointColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.connectionPointCol0);
        String[][] connectionPointJsonColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.connectionPointJsonCol0);
        String[][] canonicalProfileJsonColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.canonicalProfileJsonCol0);
        String[][] profileVectorColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.profileVectorCol0);
        String[][] profileVectorMetaColumn0 = readColumn(spreadsheetId0, crmTabName0, startRow0, cols0.profileVectorMetaCol0);

        LinkedHashMap<Integer, Integer> eligibleRows0 = selectEligibleRows(fundNameColumn0, startRow0, maxRows0);

        if (eligibleRows0.size() == 0)
        {
            return "LP profile embedding complete. No eligible rows found.";
        }

        ConcurrentHashMap<Integer, String[]> rowOutputsConcurrent0 = new ConcurrentHashMap<Integer, String[]>();
        AtomicInteger embeddedCounter0 = new AtomicInteger(0);
        AtomicInteger skippedCounter0 = new AtomicInteger(0);
        AtomicInteger failedCounter0 = new AtomicInteger(0);

        ExecutorService pool0 = Executors.newFixedThreadPool(ROW_POOL_SIZE0);
        ArrayList<Future<?>> futures0 = new ArrayList<Future<?>>();

        for (Map.Entry<Integer, Integer> entry0 : eligibleRows0.entrySet())
        {
            final int rowNumber0 = entry0.getKey();
            final int localIndex0 = entry0.getValue();

            futures0.add(pool0.submit(() ->
            {
                try
                {
                    CanonicalProfileBuilder.Input input0 = buildInput(
                        localIndex0,
                        intelligenceJsonColumn0,
                        marketIntelligenceJsonColumn0,
                        investmentThesisColumn0,
                        priorBackedFundsColumn0,
                        sectorTagsColumn0,
                        microsectorTagsColumn0,
                        typeOfInvestorColumn0,
                        fundLinkedInAboutColumn0
                    );

                    CanonicalProfile profile0 = profileBuilder.build(input0);
                    String freshHash0 = profile0.canonicalHash();

                    String storedMetaText0 = getLocalColumnValue(profileVectorMetaColumn0, localIndex0);
                    JSONObject storedMeta0 = safeParseJson(storedMetaText0);
                    String storedHash0 = storedMeta0 == null ? "" : storedMeta0.optString("canonical_hash", "");
                    String storedVersion0 = storedMeta0 == null ? "" : storedMeta0.optString("encoder_version", "");

                    if (freshHash0.equals(storedHash0) && ProfileVectorLayout.ENCODER_VERSION.equals(storedVersion0))
                    {
                        // Unchanged since the last embed: carry every existing value
                        // through unchanged rather than re-embedding or blanking it.
                        String[] carried0 = new String[OUT_FIELD_COUNT0];
                        carried0[OUT_CONNECTION_POINT0] = getLocalColumnValue(connectionPointColumn0, localIndex0);
                        carried0[OUT_CONNECTION_POINT_JSON0] = getLocalColumnValue(connectionPointJsonColumn0, localIndex0);
                        carried0[OUT_CANONICAL_PROFILE_JSON0] = getLocalColumnValue(canonicalProfileJsonColumn0, localIndex0);
                        carried0[OUT_PROFILE_VECTOR0] = getLocalColumnValue(profileVectorColumn0, localIndex0);
                        carried0[OUT_PROFILE_VECTOR_META0] = storedMetaText0;

                        rowOutputsConcurrent0.put(rowNumber0, carried0);
                        skippedCounter0.incrementAndGet();
                        return;
                    }

                    ConnectionPointExtractor.Result storedConnection0 =
                        ConnectionPointExtractor.Result.fromJson(
                            safeParseJson(getLocalColumnValue(connectionPointJsonColumn0, localIndex0)));

                    ConnectionPointExtractor.Result freshConnection0 = connectionExtractor
                        .extractFromInteractionRecords(getLocalColumnValue(interactionRecordsColumn0, localIndex0));

                    ConnectionPointExtractor.Result merged0 =
                        ConnectionPointExtractor.merge(storedConnection0, freshConnection0);

                    float[] vector0 = vectorEncoder.encode(profile0, merged0.category);

                    JSONObject metaJson0 = new JSONObject();
                    metaJson0.put("encoder_version", ProfileVectorLayout.ENCODER_VERSION);
                    metaJson0.put("embed_model", OpenAIClient.EMBED_MODEL0);
                    metaJson0.put("dims", ProfileVectorLayout.TOTAL_DIMS);
                    metaJson0.put("block_layout", buildBlockLayoutJson());
                    metaJson0.put("canonical_hash", freshHash0);
                    metaJson0.put("embedded_at", Instant.now().toString());

                    String[] computed0 = new String[OUT_FIELD_COUNT0];
                    computed0[OUT_CONNECTION_POINT0] = merged0.category == null
                        ? ConnectionPoint.UNKNOWN.name() : merged0.category.name();
                    computed0[OUT_CONNECTION_POINT_JSON0] = truncate(merged0.toJson().toString());
                    computed0[OUT_CANONICAL_PROFILE_JSON0] = truncate(profile0.toJson().toString());
                    computed0[OUT_PROFILE_VECTOR0] = truncate(VectorCodec.encodeBase64(vector0));
                    computed0[OUT_PROFILE_VECTOR_META0] = truncate(metaJson0.toString());

                    rowOutputsConcurrent0.put(rowNumber0, computed0);
                    embeddedCounter0.incrementAndGet();
                }
                catch (Exception exception0)
                {
                    failedCounter0.incrementAndGet();
                    System.out.println("Failed row " + rowNumber0 + ": " + exception0.getMessage());
                }
            }));
        }

        for (Future<?> f0 : futures0)
        {
            try { f0.get(); }
            catch (Exception exception0)
            {
                System.out.println("Profile embedding future error: " + exception0.getMessage());
            }
        }
        pool0.shutdown();

        if (rowOutputsConcurrent0.size() == 0)
        {
            return "LP profile embedding complete. No updates prepared.";
        }

        int[] outputCols0 = new int[]
        {
            cols0.connectionPointCol0,
            cols0.connectionPointJsonCol0,
            cols0.canonicalProfileJsonCol0,
            cols0.profileVectorCol0,
            cols0.profileVectorMetaCol0
        };

        executeColumnWrites(spreadsheetId0, crmTabName0, outputCols0, rowOutputsConcurrent0);

        return "LP profile embedding complete. Embedded: "
            + embeddedCounter0.get()
            + ", Skipped (unchanged): "
            + skippedCounter0.get()
            + ", Failed: "
            + failedCounter0.get()
            + ".";
    }

    private static SourceColumns resolveColumns(SessionContext context0, HashMap<String, Integer> headerMap0)
    {
        SourceColumns cols0 = new SourceColumns();

        cols0.fundNameCol0 = findCol(headerMap0, context0, "mainTabFundNameCol");
        cols0.websiteCol0 = findCol(headerMap0, context0, "mainTabWebsiteCol");
        cols0.intelligenceJsonCol0 = findCol(headerMap0, context0, "mainTabIntelligenceJsonCol");
        cols0.marketIntelligenceJsonCol0 = findCol(headerMap0, context0, "mainTabMarketIntelligenceJsonCol");
        cols0.investmentThesisCol0 = findCol(headerMap0, context0, "mainTabInvestmentThesisCol");
        cols0.priorBackedFundsCol0 = findCol(headerMap0, context0, "mainTabPriorBackedFundsCol");
        cols0.sectorTagsCol0 = findCol(headerMap0, context0, "mainTabSectorTagsCol");
        cols0.microsectorTagsCol0 = findCol(headerMap0, context0, "mainTabMicrosectorTagsCol");
        cols0.typeOfInvestorCol0 = findCol(headerMap0, context0, "mainTabTypeOfInvestorCol");
        cols0.fundLinkedInAboutCol0 = findCol(headerMap0, context0, "mainTabFundLinkedInAboutCol");
        cols0.interactionRecordsCol0 = findCol(headerMap0, context0, "mainTabInteractionRecordsCol");
        cols0.connectionPointCol0 = findCol(headerMap0, context0, "mainTabConnectionPointCol");
        cols0.connectionPointJsonCol0 = findCol(headerMap0, context0, "mainTabConnectionPointJsonCol");
        cols0.canonicalProfileJsonCol0 = findCol(headerMap0, context0, "mainTabCanonicalProfileJsonCol");
        cols0.profileVectorCol0 = findCol(headerMap0, context0, "mainTabProfileVectorCol");
        cols0.profileVectorMetaCol0 = findCol(headerMap0, context0, "mainTabProfileVectorMetaCol");

        return cols0;
    }

    private static int findCol(HashMap<String, Integer> headerMap0, SessionContext context0, String key0)
    {
        return SheetsApp.findColumnInHeaderMap(headerMap0, context0.config.getCol(key0));
    }

    private static String firstMissingColumn(SourceColumns cols0)
    {
        if (cols0.fundNameCol0 == -1) return "mainTabFundNameCol";
        if (cols0.websiteCol0 == -1) return "mainTabWebsiteCol";
        if (cols0.intelligenceJsonCol0 == -1) return "mainTabIntelligenceJsonCol";
        if (cols0.marketIntelligenceJsonCol0 == -1) return "mainTabMarketIntelligenceJsonCol";
        if (cols0.investmentThesisCol0 == -1) return "mainTabInvestmentThesisCol";
        if (cols0.priorBackedFundsCol0 == -1) return "mainTabPriorBackedFundsCol";
        if (cols0.sectorTagsCol0 == -1) return "mainTabSectorTagsCol";
        if (cols0.microsectorTagsCol0 == -1) return "mainTabMicrosectorTagsCol";
        if (cols0.typeOfInvestorCol0 == -1) return "mainTabTypeOfInvestorCol";
        if (cols0.fundLinkedInAboutCol0 == -1) return "mainTabFundLinkedInAboutCol";
        if (cols0.interactionRecordsCol0 == -1) return "mainTabInteractionRecordsCol";
        if (cols0.connectionPointCol0 == -1) return "mainTabConnectionPointCol";
        if (cols0.connectionPointJsonCol0 == -1) return "mainTabConnectionPointJsonCol";
        if (cols0.canonicalProfileJsonCol0 == -1) return "mainTabCanonicalProfileJsonCol";
        if (cols0.profileVectorCol0 == -1) return "mainTabProfileVectorCol";
        if (cols0.profileVectorMetaCol0 == -1) return "mainTabProfileVectorMetaCol";
        return null;
    }

    private static String[][] readColumn(
        String spreadsheetId0, String crmTabName0, int startRow0, int col0) throws Exception
    {
        return sheetsPort.readRangeMatrix(spreadsheetId0, crmTabName0, startRow0, col0, MAX_CRM_ROWS0, col0);
    }

    private static LinkedHashMap<Integer, Integer> selectEligibleRows(
        String[][] fundNameColumn0, int startRow0, int maxRows0)
    {
        LinkedHashMap<Integer, Integer> eligibleRows0 = new LinkedHashMap<Integer, Integer>();

        for (int i = 0; i < fundNameColumn0.length; i++)
        {
            if (eligibleRows0.size() >= maxRows0)
            {
                break;
            }

            String fundName0 = getLocalColumnValue(fundNameColumn0, i);

            if (isBlank(fundName0))
            {
                continue;
            }

            eligibleRows0.put(startRow0 + i, i);
        }

        return eligibleRows0;
    }

    private static CanonicalProfileBuilder.Input buildInput(
        int localIndex0,
        String[][] intelligenceJsonColumn0,
        String[][] marketIntelligenceJsonColumn0,
        String[][] investmentThesisColumn0,
        String[][] priorBackedFundsColumn0,
        String[][] sectorTagsColumn0,
        String[][] microsectorTagsColumn0,
        String[][] typeOfInvestorColumn0,
        String[][] fundLinkedInAboutColumn0)
    {
        String intelligenceJsonText0 = getLocalColumnValue(intelligenceJsonColumn0, localIndex0);
        JSONObject intelligenceJson0 = safeParseJson(intelligenceJsonText0);

        CanonicalProfileBuilder.Input input0 = new CanonicalProfileBuilder.Input();

        input0.investmentThesis = getLocalColumnValue(investmentThesisColumn0, localIndex0);
        input0.intelligenceJsonThesisSummary = extractThesisSummary(intelligenceJson0);
        input0.fundLinkedInAbout = getLocalColumnValue(fundLinkedInAboutColumn0, localIndex0);

        input0.priorBackedFunds = getLocalColumnValue(priorBackedFundsColumn0, localIndex0);
        input0.intelligenceJsonPriorBackedFunds = extractPriorBackedFunds(intelligenceJson0);

        input0.sectorTags = getLocalColumnValue(sectorTagsColumn0, localIndex0);
        input0.microsectorTags = getLocalColumnValue(microsectorTagsColumn0, localIndex0);
        input0.intelligenceJsonSectorFocus = extractSectorFocus(intelligenceJson0);
        input0.intelligenceJsonMicrosectorFocus = extractMicrosectorFocus(intelligenceJson0);

        input0.marketIntelligenceJson = getLocalColumnValue(marketIntelligenceJsonColumn0, localIndex0);
        input0.intelligenceJson = intelligenceJsonText0;

        input0.typeOfInvestor = getLocalColumnValue(typeOfInvestorColumn0, localIndex0);
        input0.intelligenceJsonAllocatorType = extractAllocatorType(intelligenceJson0);

        return input0;
    }

    private static JSONArray buildBlockLayoutJson()
    {
        JSONArray blocks0 = new JSONArray();

        for (ProfileVectorLayout.Block block0 : ProfileVectorLayout.BLOCKS)
        {
            JSONObject b0 = new JSONObject();
            b0.put("name", block0.name);
            b0.put("kind", block0.kind);
            b0.put("dims", block0.dims);
            b0.put("offset", block0.offset);
            blocks0.put(b0);
        }

        return blocks0;
    }

    private static void executeColumnWrites(
        String spreadsheetId0,
        String crmTabName0,
        int[] outputCols0,
        ConcurrentHashMap<Integer, String[]> rowOutputs0) throws Exception
    {
        LinkedHashMap<Integer, String[]> sortedOutputs0 = new LinkedHashMap<Integer, String[]>();
        ArrayList<Integer> sortedRows0 = new ArrayList<Integer>(rowOutputs0.keySet());
        java.util.Collections.sort(sortedRows0);
        for (int rowNum0 : sortedRows0)
        {
            sortedOutputs0.put(rowNum0, rowOutputs0.get(rowNum0));
        }

        int minRow0 = findMinKey(sortedOutputs0);
        int maxRow0 = findMaxKey(sortedOutputs0);
        int rowCount0 = maxRow0 - minRow0 + 1;

        String[][][] columnUpdateData0 = new String[OUT_FIELD_COUNT0][rowCount0][1];

        for (int columnIndex0 = 0; columnIndex0 < OUT_FIELD_COUNT0; columnIndex0++)
        {
            columnUpdateData0[columnIndex0] = sheetsPort.readRangeMatrix(
                spreadsheetId0,
                crmTabName0,
                minRow0,
                outputCols0[columnIndex0],
                maxRow0,
                outputCols0[columnIndex0]
            );
        }

        for (Map.Entry<Integer, String[]> entry0 : sortedOutputs0.entrySet())
        {
            int rowNumber0 = entry0.getKey();
            String[] outputValues0 = entry0.getValue();

            int localRowIndex0 = rowNumber0 - minRow0;

            for (int fieldIndex0 = 0; fieldIndex0 < outputValues0.length; fieldIndex0++)
            {
                columnUpdateData0[fieldIndex0][localRowIndex0][0] =
                    outputValues0[fieldIndex0] == null ? "" : outputValues0[fieldIndex0];
            }
        }

        for (int columnIndex0 = 0; columnIndex0 < OUT_FIELD_COUNT0; columnIndex0++)
        {
            sheetsPort.updateRangeMatrix(
                spreadsheetId0,
                crmTabName0,
                minRow0,
                outputCols0[columnIndex0],
                columnUpdateData0[columnIndex0]
            );
        }
    }

    private static String extractAllocatorType(JSONObject intelligenceJson0)
    {
        if (intelligenceJson0 == null) return "";
        JSONObject allocatorProfile0 = intelligenceJson0.optJSONObject("allocator_profile");
        if (allocatorProfile0 == null) return "";
        JSONObject allocatorType0 = allocatorProfile0.optJSONObject("allocator_type");
        return allocatorType0 == null ? "" : allocatorType0.optString("value", "");
    }

    private static String extractSectorFocus(JSONObject intelligenceJson0)
    {
        if (intelligenceJson0 == null) return "";
        JSONObject sectorFocus0 = intelligenceJson0.optJSONObject("sector_focus");
        if (sectorFocus0 == null) return "";
        return joinJsonArrayValues(sectorFocus0.optJSONArray("sector_tags"), "value");
    }

    private static String extractMicrosectorFocus(JSONObject intelligenceJson0)
    {
        if (intelligenceJson0 == null) return "";
        JSONObject microsectorFocus0 = intelligenceJson0.optJSONObject("microsector_focus");
        if (microsectorFocus0 == null) return "";
        return joinJsonArrayValues(microsectorFocus0.optJSONArray("microsector_tags"), "value");
    }

    private static String extractPriorBackedFunds(JSONObject intelligenceJson0)
    {
        if (intelligenceJson0 == null) return "";
        JSONObject priorRelationships0 = intelligenceJson0.optJSONObject("prior_relationships");
        if (priorRelationships0 == null) return "";
        return joinJsonArrayValues(priorRelationships0.optJSONArray("prior_backed_funds"), "name");
    }

    private static String extractThesisSummary(JSONObject intelligenceJson0)
    {
        if (intelligenceJson0 == null) return "";
        JSONObject investmentThesis0 = intelligenceJson0.optJSONObject("investment_thesis");
        return investmentThesis0 == null ? "" : investmentThesis0.optString("summary", "");
    }

    private static String joinJsonArrayValues(JSONArray array0, String key0)
    {
        if (array0 == null) return "";

        ArrayList<String> values0 = new ArrayList<String>();

        for (int i = 0; i < array0.length(); i++)
        {
            JSONObject object0 = array0.optJSONObject(i);
            if (object0 == null) continue;

            String value0 = object0.optString(key0, "").trim();
            if (!isBlank(value0))
            {
                values0.add(value0);
            }
        }

        return String.join("|", values0);
    }

    private static JSONObject safeParseJson(String text0)
    {
        if (text0 == null || text0.trim().isEmpty())
        {
            return null;
        }

        try
        {
            return new JSONObject(text0);
        }
        catch (Exception exception0)
        {
            return null;
        }
    }

    private static String truncate(String text0)
    {
        if (text0 == null)
        {
            return "";
        }

        return text0.length() > JSON_MAX0 ? text0.substring(0, JSON_MAX0) : text0;
    }

    private static String getLocalColumnValue(String[][] columnData0, int rowIndex0)
    {
        if (columnData0 == null ||
            rowIndex0 < 0 ||
            rowIndex0 >= columnData0.length ||
            columnData0[rowIndex0].length == 0 ||
            columnData0[rowIndex0][0] == null)
        {
            return "";
        }

        return columnData0[rowIndex0][0].trim();
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }

    private static int findMinKey(LinkedHashMap<Integer, String[]> map0)
    {
        int min0 = Integer.MAX_VALUE;
        for (Integer key0 : map0.keySet())
        {
            if (key0 < min0) min0 = key0;
        }
        return min0;
    }

    private static int findMaxKey(LinkedHashMap<Integer, String[]> map0)
    {
        int max0 = -1;
        for (Integer key0 : map0.keySet())
        {
            if (key0 > max0) max0 = key0;
        }
        return max0;
    }
}
