package com.liminer.core;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Offline verification of the five LP profile embedding CRM columns (task 0174).
 * Asserts against the in-memory CRMFieldRegistry only — no Sheets connection,
 * no credentials, no network.
 */
public class EmbedColumnsTestMain
{
    private static int failures0 = 0;

    private static final String[] KEYS = {
        "mainTabConnectionPointCol",
        "mainTabConnectionPointJsonCol",
        "mainTabCanonicalProfileJsonCol",
        "mainTabProfileVectorCol",
        "mainTabProfileVectorMetaCol"
    };

    private static final String[] COLUMN_NAMES = {
        "Connection Point",
        "Connection Point JSON",
        "Canonical Profile JSON",
        "Profile Vector",
        "Profile Vector Meta"
    };

    public static void main(String[] args0) throws Exception
    {
        testFieldsRegistered();
        testMachineSideOnly();
        testNoDuplicateKeysOrColumnNames();
        testProvisionerMethodExists();

        if (failures0 > 0)
        {
            System.out.println("EMBED_COLUMNS_FAILED: " + failures0 + " check(s) failed");
            System.exit(1);
        }

        System.out.println("EMBED_COLUMNS_OK");
    }

    private static void testFieldsRegistered()
    {
        for (int i0 = 0; i0 < KEYS.length; i0++)
        {
            CRMField field0 = CRMFieldRegistry.getByKey(KEYS[i0]);
            if (!check("field exists for " + KEYS[i0], field0 != null)) continue;

            check("side machine for " + KEYS[i0], CRMField.SIDE_MACHINE.equals(field0.side));
            check("tabGroup main for " + KEYS[i0], "main".equals(field0.tabGroup));
            check("columnName matches for " + KEYS[i0], COLUMN_NAMES[i0].equals(field0.columnName));
        }
    }

    private static void testMachineSideOnly()
    {
        HashSet<String> humanKeys0 = new HashSet<>();
        for (CRMField field0 : CRMFieldRegistry.getMainHumanFields())
        {
            humanKeys0.add(field0.key);
        }

        HashSet<String> machineKeys0 = new HashSet<>();
        for (CRMField field0 : CRMFieldRegistry.getMainMachineFields())
        {
            machineKeys0.add(field0.key);
        }

        for (String key0 : KEYS)
        {
            check("not human-side: " + key0, !humanKeys0.contains(key0));
            check("is machine-side: " + key0, machineKeys0.contains(key0));
        }
    }

    private static void testNoDuplicateKeysOrColumnNames()
    {
        HashMap<String, Integer> keyCounts0 = new HashMap<>();
        HashMap<String, Integer> columnNameCounts0 = new HashMap<>();

        for (CRMField field0 : CRMFieldRegistry.getAllFields())
        {
            keyCounts0.merge(field0.key, 1, Integer::sum);
            columnNameCounts0.merge(field0.columnName, 1, Integer::sum);
        }

        ArrayList<String> duplicateKeys0 = new ArrayList<>();
        for (HashMap.Entry<String, Integer> entry0 : keyCounts0.entrySet())
        {
            if (entry0.getValue() > 1) duplicateKeys0.add(entry0.getKey());
        }

        ArrayList<String> duplicateColumnNames0 = new ArrayList<>();
        for (HashMap.Entry<String, Integer> entry0 : columnNameCounts0.entrySet())
        {
            if (entry0.getValue() > 1) duplicateColumnNames0.add(entry0.getKey());
        }

        check("no duplicate keys: " + duplicateKeys0, duplicateKeys0.isEmpty());
        check("no duplicate columnNames: " + duplicateColumnNames0, duplicateColumnNames0.isEmpty());
    }

    private static void testProvisionerMethodExists() throws Exception
    {
        Method method0 = CRMFieldRegistry.class.getMethod(
            "ensureProfileEmbeddingColumns",
            SessionContext.class,
            String.class,
            String.class,
            int.class,
            HashMap.class);

        check("ensureProfileEmbeddingColumns returns void", method0.getReturnType() == void.class);
    }

    private static boolean check(String label0, boolean condition0)
    {
        if (condition0)
        {
            System.out.println("  ok   " + label0);
            return true;
        }

        System.out.println("  FAIL " + label0);
        failures0++;
        return false;
    }
}
