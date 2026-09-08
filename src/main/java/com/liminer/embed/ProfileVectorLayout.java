package com.liminer.embed;

/*
 * ProfileVectorLayout is the single source of truth for the LP profile block-vector
 * geometry: which blocks exist, how many dims each occupies, what weight each carries,
 * and where each block sits inside the flat 1590-float vector.
 *
 * Each block is unit-normalized on its own, then scaled by sqrt(effective weight),
 * then concatenated. Blocks 1-8 are "independent" (shareable across GPs) and occupy
 * the contiguous prefix 0..1579; their table weights sum to 1.00 and are then scaled
 * by sqrt(1 - ALPHA) to become their effective vector weight. Block 9 (CONNECTION_POINT)
 * is GP-dependent and its table weight already IS its effective vector weight (ALPHA).
 */
public class ProfileVectorLayout
{
    public static final String ENCODER_VERSION = "lp_block_v1";
    public static final double ALPHA = 0.25;
    public static final int TEXT_BLOCK_DIMS = 512;
    public static final int TOTAL_DIMS = 1590;
    public static final double SIGMA = 1.0;

    public static final String CONNECTION_POINT_BLOCK_NAME = "CONNECTION_POINT";

    public static final double[] CHECK_SIZE_LADDER =
        { 50e3, 100e3, 200e3, 500e3, 1e6, 2.5e6, 5e6, 10e6, 20e6 };

    public static final double[] AUM_LADDER =
        { 5e6, 10e6, 25e6, 50e6, 100e6, 250e6, 500e6, 2e9, 10e9, 50e9, 250e9 };

    public static final String[] CONNECTION_POINT_ORDER =
    {
        "COLD_OUTBOUND", "SHARED_INSTITUTION", "PLATFORM_INTRODUCTION", "EVENT_ENCOUNTER",
        "INBOUND", "WEAK_REFERRAL", "PAST_WORK_COLLEAGUE", "STRONG_REFERRAL",
        "PAST_COINVESTOR", "PRIOR_LP"
    };

    public static final String[] ALLOCATOR_TYPE_ORDER =
    {
        "ANGEL_INVESTOR", "FAMILY_OFFICE", "NONPROFIT", "FOUNDATION", "CORPORATION",
        "VENTURE_CAPITAL", "ENDOWMENT", "FUND_OF_FUNDS", "PENSION_GOVERNMENT"
    };

    public static final String[] TIMING_ORDER =
    {
        "MONTHS_0_3", "MONTHS_3_6", "MONTHS_6_12", "MONTHS_12_24", "MONTHS_24_48",
        "MONTHS_48_PLUS"
    };

    public static class Block
    {
        public final String name;
        public final String kind;
        public final int dims;
        public final double weight;
        public final int offset;

        public Block(String name0, String kind0, int dims0, double weight0, int offset0)
        {
            name = name0;
            kind = kind0;
            dims = dims0;
            weight = weight0;
            offset = offset0;
        }

        /*
         * The weight actually used to scale this block inside the concatenated vector.
         * For the eight independent blocks the table weight is scaled down by
         * sqrt(1 - ALPHA) worth of variance (1 - ALPHA in dot-product terms) to make
         * room for CONNECTION_POINT, which already carries its final weight (ALPHA).
         */
        public double vectorWeight()
        {
            if (CONNECTION_POINT_BLOCK_NAME.equals(name))
            {
                return weight;
            }

            return weight * (1.0 - ALPHA);
        }
    }

    public static final Block[] BLOCKS = buildBlocks();

    private static Block[] buildBlocks()
    {
        Block[] blocks0 = new Block[9];
        int offset0 = 0;

        blocks0[0] = new Block("THESIS", "text", TEXT_BLOCK_DIMS, 0.23, offset0);
        offset0 += blocks0[0].dims;

        blocks0[1] = new Block("PAST_INVESTMENTS", "text", TEXT_BLOCK_DIMS, 0.16, offset0);
        offset0 += blocks0[1].dims;

        blocks0[2] = new Block("AUM", "ladder", AUM_LADDER.length, 0.16, offset0);
        offset0 += blocks0[2].dims;

        blocks0[3] = new Block("CAPITAL_ALLOCATABLE", "ladder", CHECK_SIZE_LADDER.length, 0.14, offset0);
        offset0 += blocks0[3].dims;

        blocks0[4] = new Block("PAST_INVESTMENT_AMOUNTS", "ladder", CHECK_SIZE_LADDER.length, 0.09, offset0);
        offset0 += blocks0[4].dims;

        blocks0[5] = new Block("ALLOCATOR_TYPE", "ordinal", ALLOCATOR_TYPE_ORDER.length, 0.09, offset0);
        offset0 += blocks0[5].dims;

        blocks0[6] = new Block("TIMING", "ordinal", TIMING_ORDER.length, 0.09, offset0);
        offset0 += blocks0[6].dims;

        blocks0[7] = new Block("NEW_INVESTMENT_AREAS", "text", TEXT_BLOCK_DIMS, 0.04, offset0);
        offset0 += blocks0[7].dims;

        blocks0[8] = new Block(CONNECTION_POINT_BLOCK_NAME, "ordinal", CONNECTION_POINT_ORDER.length, ALPHA, offset0);
        offset0 += blocks0[8].dims;

        return blocks0;
    }

    public static int offsetOf(String blockName0)
    {
        for (Block block0 : BLOCKS)
        {
            if (block0.name.equals(blockName0))
            {
                return block0.offset;
            }
        }

        throw new IllegalArgumentException("Unknown block: " + blockName0);
    }

    public static int independentDims()
    {
        return TOTAL_DIMS - CONNECTION_POINT_ORDER.length;
    }

    static
    {
        double independentWeightSum0 = 0.0;

        for (Block block0 : BLOCKS)
        {
            if (!CONNECTION_POINT_BLOCK_NAME.equals(block0.name))
            {
                independentWeightSum0 += block0.weight;
            }
        }

        if (Math.abs(independentWeightSum0 - 1.0) > 1e-9)
        {
            throw new ExceptionInInitializerError(
                "Independent block weights must sum to 1.0, got " + independentWeightSum0);
        }

        int totalOffset0 = 0;

        for (Block block0 : BLOCKS)
        {
            if (block0.offset != totalOffset0)
            {
                throw new ExceptionInInitializerError(
                    "Block " + block0.name + " has offset " + block0.offset
                        + " but expected " + totalOffset0);
            }

            totalOffset0 += block0.dims;
        }

        if (totalOffset0 != TOTAL_DIMS)
        {
            throw new ExceptionInInitializerError(
                "Block offsets total " + totalOffset0 + " but TOTAL_DIMS is " + TOTAL_DIMS);
        }

        if (independentDims() != totalOffset0 - CONNECTION_POINT_ORDER.length)
        {
            throw new ExceptionInInitializerError("independentDims() mismatch");
        }
    }
}
