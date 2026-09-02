package com.liminer.core;

public class CRMField
{
    // Divider-layout side markers (dividerlists.md §1).
    //   SIDE_HUMAN   — GP daily-facing column, provisioned LEFT of the divider.
    //   SIDE_MACHINE — Liminer machine-generated enrichment, provisioned RIGHT of the divider.
    //   SIDE_DIVIDER — the single sentinel divider column itself.
    public static final String SIDE_HUMAN   = "human";
    public static final String SIDE_MACHINE = "machine";
    public static final String SIDE_DIVIDER = "divider";

    public final String key;
    public final String columnName;
    public final String displayName;
    public final String fieldType;
    public final String tabGroup;
    public final boolean includeInOnboarding;
    public final boolean includeInAIExtraction;
    public final boolean includeInSummary;
    public final String extractionJsonKey;
    public final String aiExtractionInstruction;
    public final String defaultValue;
    // Which side of the onboarding divider this field belongs to. Defaults to
    // "machine" for backward compatibility (dividerlists.md step 1).
    public final String side;

    // Deprecated columns are kept readable during the priorityscoringv2 shadow-mode
    // migration, then retired. Marking them lets tooling gray them out / skip them
    // without deleting the registration.
    public final boolean deprecated;

    public CRMField(
        String key,
        String columnName,
        String displayName,
        String fieldType,
        String tabGroup,
        boolean includeInOnboarding,
        boolean includeInAIExtraction,
        boolean includeInSummary,
        String extractionJsonKey,
        String aiExtractionInstruction,
        String defaultValue)
    {
        this(key, columnName, displayName, fieldType, tabGroup,
            includeInOnboarding, includeInAIExtraction, includeInSummary,
            extractionJsonKey, aiExtractionInstruction, defaultValue,
            SIDE_MACHINE, false);
    }

    public CRMField(
        String key,
        String columnName,
        String displayName,
        String fieldType,
        String tabGroup,
        boolean includeInOnboarding,
        boolean includeInAIExtraction,
        boolean includeInSummary,
        String extractionJsonKey,
        String aiExtractionInstruction,
        String defaultValue,
        String side,
        boolean deprecated)
    {
        this.key = key;
        this.columnName = columnName;
        this.displayName = displayName;
        this.fieldType = fieldType;
        this.tabGroup = tabGroup;
        this.includeInOnboarding = includeInOnboarding;
        this.includeInAIExtraction = includeInAIExtraction;
        this.includeInSummary = includeInSummary;
        this.extractionJsonKey = extractionJsonKey;
        this.aiExtractionInstruction = aiExtractionInstruction;
        this.defaultValue = defaultValue;
        this.side = (side == null || side.trim().isEmpty()) ? SIDE_MACHINE : side.trim();
        this.deprecated = deprecated;
    }
}
