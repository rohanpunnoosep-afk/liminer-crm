package com.liminer.embed;

import com.liminer.core.ConnectionPoint;
import com.liminer.llm.OpenAIClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * ProfileVectorEncoder turns a CanonicalProfile into the flat ProfileVectorLayout.TOTAL_DIMS
 * weighted block vector: each block is unit-normalized on its own, scaled by
 * sqrt(block.vectorWeight()), and copied into the block's offset. Missing modules are left
 * as the all-zero sub-vector -- never renormalized to hide the gap.
 *
 * The embedding call sits behind EmbedderSeam so this class (and anything built on it) can
 * be exercised fully offline; production wiring delegates to OpenAIClient.getEmbeddings.
 */
public class ProfileVectorEncoder
{
    private static final String TEXT_ATOM_SEPARATOR = "; ";

    public interface EmbedderSeam
    {
        List<float[]> embed(List<String> texts, int dims) throws Exception;
    }

    private final EmbedderSeam embedderSeam;

    public ProfileVectorEncoder()
    {
        this(new EmbedderSeam()
        {
            @Override
            public List<float[]> embed(List<String> texts0, int dims0) throws Exception
            {
                return OpenAIClient.getEmbeddings(texts0, dims0);
            }
        });
    }

    public ProfileVectorEncoder(EmbedderSeam embedderSeam0)
    {
        embedderSeam = embedderSeam0;
    }

    // The one place the module-to-embedded-string mapping lives.
    public static String blockTextFor(CanonicalProfile profile0, String blockName0)
    {
        List<String> atoms0;

        if ("THESIS".equals(blockName0))
        {
            atoms0 = profile0.thesis;
        }
        else if ("PAST_INVESTMENTS".equals(blockName0))
        {
            atoms0 = profile0.pastInvestments;
        }
        else if ("NEW_INVESTMENT_AREAS".equals(blockName0))
        {
            atoms0 = profile0.newInvestmentAreas;
        }
        else
        {
            throw new IllegalArgumentException("Not a text block: " + blockName0);
        }

        return String.join(TEXT_ATOM_SEPARATOR, atoms0);
    }

    public float[] encode(CanonicalProfile profile0) throws Exception
    {
        return encode(profile0, null);
    }

    public float[] encode(CanonicalProfile profile0, ConnectionPoint cp0) throws Exception
    {
        float[] out0 = new float[ProfileVectorLayout.TOTAL_DIMS];
        Map<String, float[]> textEmbeddings0 = embedTextBlocks(profile0);

        for (ProfileVectorLayout.Block block0 : ProfileVectorLayout.BLOCKS)
        {
            float[] raw0 = rawBlockVector(profile0, cp0, block0, textEmbeddings0);
            writeBlock(out0, block0, raw0);
        }

        return out0;
    }

    // Batches every populated text module for this profile into a single embed() call,
    // filtering out blanks so they never get sent to the API.
    private Map<String, float[]> embedTextBlocks(CanonicalProfile profile0) throws Exception
    {
        List<String> blockNamesNeeded0 = new ArrayList<String>();
        List<String> textsToEmbed0 = new ArrayList<String>();

        for (ProfileVectorLayout.Block block0 : ProfileVectorLayout.BLOCKS)
        {
            if (!"text".equals(block0.kind))
            {
                continue;
            }

            String text0 = blockTextFor(profile0, block0.name);

            if (text0 != null && !text0.trim().isEmpty())
            {
                blockNamesNeeded0.add(block0.name);
                textsToEmbed0.add(text0);
            }
        }

        Map<String, float[]> textEmbeddings0 = new HashMap<String, float[]>();

        if (!textsToEmbed0.isEmpty())
        {
            List<float[]> embedded0 = embedderSeam.embed(textsToEmbed0, ProfileVectorLayout.TEXT_BLOCK_DIMS);

            for (int i0 = 0; i0 < blockNamesNeeded0.size(); i0++)
            {
                textEmbeddings0.put(blockNamesNeeded0.get(i0), embedded0.get(i0));
            }
        }

        return textEmbeddings0;
    }

    private static float[] rawBlockVector(CanonicalProfile profile0, ConnectionPoint cp0,
        ProfileVectorLayout.Block block0, Map<String, float[]> textEmbeddings0)
    {
        if ("text".equals(block0.kind))
        {
            float[] embedded0 = textEmbeddings0.get(block0.name);
            return embedded0 != null ? embedded0 : new float[block0.dims];
        }

        if ("AUM".equals(block0.name))
        {
            return RbfEncoder.encodeLadder(profile0.aumUsd, ProfileVectorLayout.AUM_LADDER);
        }

        if ("CAPITAL_ALLOCATABLE".equals(block0.name))
        {
            return RbfEncoder.encodeLadder(profile0.capitalAllocatableUsd, ProfileVectorLayout.CHECK_SIZE_LADDER);
        }

        if ("PAST_INVESTMENT_AMOUNTS".equals(block0.name))
        {
            return RbfEncoder.encodeLadder(profile0.pastInvestmentAmountUsd, ProfileVectorLayout.CHECK_SIZE_LADDER);
        }

        if ("ALLOCATOR_TYPE".equals(block0.name))
        {
            int rank0 = indexOf(ProfileVectorLayout.ALLOCATOR_TYPE_ORDER, profile0.allocatorType) + 1;
            return RbfEncoder.encodeOrdinal(rank0, ProfileVectorLayout.ALLOCATOR_TYPE_ORDER.length);
        }

        if ("TIMING".equals(block0.name))
        {
            return RbfEncoder.encodeOrdinal(timingRank(profile0.timingMonthsSinceLastClose),
                ProfileVectorLayout.TIMING_ORDER.length);
        }

        if (ProfileVectorLayout.CONNECTION_POINT_BLOCK_NAME.equals(block0.name))
        {
            int rank0 = cp0 == null ? 0 : cp0.rank();
            return RbfEncoder.encodeOrdinal(rank0, ProfileVectorLayout.CONNECTION_POINT_ORDER.length);
        }

        throw new IllegalStateException("Unhandled block: " + block0.name);
    }

    // Unit-normalize, scale by sqrt(effective weight), copy into the block's offset.
    static void writeBlock(float[] out0, ProfileVectorLayout.Block block0, float[] raw0)
    {
        float[] unit0 = VectorMath.normalize(raw0);
        float[] scaled0 = VectorMath.scale(unit0, Math.sqrt(block0.vectorWeight()));

        System.arraycopy(scaled0, 0, out0, block0.offset, block0.dims);
    }

    private static int indexOf(String[] arr0, String value0)
    {
        if (value0 == null || value0.isEmpty())
        {
            return -1;
        }

        for (int i0 = 0; i0 < arr0.length; i0++)
        {
            if (arr0[i0].equals(value0))
            {
                return i0;
            }
        }

        return -1;
    }

    // Buckets a continuous months-since-signal value onto TIMING_ORDER's 1-based rank;
    // NaN/negative (no signal) maps to rank 0, which RbfEncoder.encodeOrdinal treats as missing.
    private static int timingRank(double monthsSinceLastClose0)
    {
        if (Double.isNaN(monthsSinceLastClose0) || monthsSinceLastClose0 < 0)
        {
            return 0;
        }

        if (monthsSinceLastClose0 <= 3) return 1;
        if (monthsSinceLastClose0 <= 6) return 2;
        if (monthsSinceLastClose0 <= 12) return 3;
        if (monthsSinceLastClose0 <= 24) return 4;
        if (monthsSinceLastClose0 <= 48) return 5;
        return 6;
    }
}
