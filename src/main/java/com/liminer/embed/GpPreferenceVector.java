package com.liminer.embed;

import com.liminer.core.LpContext;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * GpPreferenceVector builds the GP's side of the dot product against ProfileVectorEncoder's
 * LP vectors. It is NOT a mirrored LP: the three match blocks (THESIS, PAST_INVESTMENTS,
 * NEW_INVESTMENT_AREAS) run the GP's own profile text through the identical encoder because
 * for those blocks we want LP-close-to-GP. The other six blocks are preference blocks --
 * AUM, CAPITAL_ALLOCATABLE and PAST_INVESTMENT_AMOUNTS reward LP magnitude (not similarity to
 * the GP's own size), ALLOCATOR_TYPE/TIMING/CONNECTION_POINT are read from an explicit
 * preference vector rather than mirrored, because the GP has no "own" value for any of them
 * that would mean the same thing on the LP side.
 *
 * GpProfile has no past-investments/new-investment-areas fields of its own (only
 * fundName/sectors/microsectorTags/stages/geographies/investmentThesis), so both of those
 * match blocks are built from the same sectors+microsectorTags focus text: a GP does not
 * distinguish "what I've backed before" from "what I'm newly open to" the way an LP does --
 * it just has a current thesis/focus, and both blocks measure LP alignment against that.
 */
public class GpPreferenceVector
{
    private static final String PREFERENCE_VECTOR_KEY = "preference_vector";

    public static float[] build(LpContext.GpProfile gp0, JSONObject clientProfileJson0,
        ProfileVectorEncoder encoder0) throws Exception
    {
        CanonicalProfile gpAsProfile0 = gpProfileToCanonical(gp0);
        float[] gpEncoded0 = encoder0.encode(gpAsProfile0);

        float[] out0 = new float[ProfileVectorLayout.TOTAL_DIMS];

        JSONObject preferenceVector0 = clientProfileJson0 == null
            ? null : clientProfileJson0.optJSONObject(PREFERENCE_VECTOR_KEY);

        for (ProfileVectorLayout.Block block0 : ProfileVectorLayout.BLOCKS)
        {
            if (isMatchBlock(block0.name))
            {
                System.arraycopy(gpEncoded0, block0.offset, out0, block0.offset, block0.dims);
                continue;
            }

            float[] raw0 = readPreferenceBlock(preferenceVector0, block0.name, block0.dims);
            ProfileVectorEncoder.writeBlock(out0, block0, raw0);
        }

        // TODO(outcome-centroid): a later iteration should blend these preference blocks
        // toward the centroid of LPs at InteractionSignalExtractor.stageRank() >= 2
        // (First Interest or better) instead of relying solely on the static defaults /
        // explicit preference_vector below. Out of scope for this task.

        return out0;
    }

    // The shipped defaults, exposed standalone so they are inspectable/testable without a
    // full GP profile. Ladder-shaped preferences (AUM, CAPITAL_ALLOCATABLE,
    // PAST_INVESTMENT_AMOUNTS) and CONNECTION_POINT ramp monotonically increasing across the
    // bucket/rank index (larger / warmer is better); ALLOCATOR_TYPE and TIMING are flat
    // (no preference) until a GP sets them explicitly.
    public static float[] defaultPreferenceBlock(String blockName0)
    {
        int dims0 = dimsOf(blockName0);
        float[] raw0 = new float[dims0];

        if ("ALLOCATOR_TYPE".equals(blockName0) || "TIMING".equals(blockName0))
        {
            java.util.Arrays.fill(raw0, 1.0f);
        }
        else
        {
            for (int i0 = 0; i0 < dims0; i0++)
            {
                raw0[i0] = i0 + 1;
            }
        }

        return VectorMath.normalize(raw0);
    }

    private static float[] readPreferenceBlock(JSONObject preferenceVector0, String blockName0, int dims0)
    {
        if (preferenceVector0 != null)
        {
            JSONArray arr0 = preferenceVector0.optJSONArray(blockName0);

            if (arr0 != null && arr0.length() == dims0)
            {
                float[] raw0 = new float[dims0];
                boolean valid0 = true;

                for (int i0 = 0; i0 < dims0; i0++)
                {
                    double v0 = arr0.optDouble(i0, Double.NaN);

                    if (Double.isNaN(v0))
                    {
                        valid0 = false;
                        break;
                    }

                    raw0[i0] = (float) v0;
                }

                if (valid0)
                {
                    return raw0;
                }
            }
        }

        return defaultPreferenceBlock(blockName0);
    }

    private static boolean isMatchBlock(String blockName0)
    {
        return "THESIS".equals(blockName0)
            || "PAST_INVESTMENTS".equals(blockName0)
            || "NEW_INVESTMENT_AREAS".equals(blockName0);
    }

    private static int dimsOf(String blockName0)
    {
        for (ProfileVectorLayout.Block block0 : ProfileVectorLayout.BLOCKS)
        {
            if (block0.name.equals(blockName0))
            {
                return block0.dims;
            }
        }

        throw new IllegalArgumentException("Unknown block: " + blockName0);
    }

    private static CanonicalProfile gpProfileToCanonical(LpContext.GpProfile gp0)
    {
        CanonicalProfile p0 = new CanonicalProfile();

        if (gp0.investmentThesis != null && !gp0.investmentThesis.trim().isEmpty())
        {
            p0.thesis.add(gp0.investmentThesis.trim());
        }

        List<String> focusAtoms0 = splitAtoms(gp0.sectors);
        focusAtoms0.addAll(splitAtoms(gp0.microsectorTags));

        p0.pastInvestments.addAll(focusAtoms0);
        p0.newInvestmentAreas.addAll(focusAtoms0);

        p0.canonicalize();
        return p0;
    }

    private static List<String> splitAtoms(String raw0)
    {
        List<String> out0 = new ArrayList<String>();

        if (raw0 == null || raw0.trim().isEmpty())
        {
            return out0;
        }

        for (String part0 : raw0.split("[,;]"))
        {
            String trimmed0 = part0.trim();

            if (!trimmed0.isEmpty())
            {
                out0.add(trimmed0);
            }
        }

        return out0;
    }
}
