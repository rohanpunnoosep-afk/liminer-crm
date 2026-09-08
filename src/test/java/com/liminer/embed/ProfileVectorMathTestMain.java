package com.liminer.embed;

import com.liminer.core.ConnectionPoint;

/*
 * Offline verification of the LP profile block-vector geometry (task 0170).
 * Pure arithmetic -- no I/O, no network, no Google Sheets, no OpenAI.
 */
public class ProfileVectorMathTestMain
{
    private static int failures0 = 0;

    public static void main(String[] args0)
    {
        testFullyPopulatedVectorIsUnitNorm();
        testSelfDotIsOne();
        testWeightRecovery();
        testLadderMonotonicity();
        testMissingEncodesToZero();
        testOrdinalAdjacency();
        testCodecRoundTrip();
        testConnectionPointFromLabel();

        if (failures0 > 0)
        {
            System.out.println("PROFILE_VECTOR_MATH_FAILED: " + failures0 + " check(s) failed");
            System.exit(1);
        }

        System.out.println("PROFILE_VECTOR_MATH_OK");
    }

    // ---------- checks ----------

    private static void testFullyPopulatedVectorIsUnitNorm()
    {
        float[] v0 = buildFullyPopulatedVector();
        check("fully populated vector has unit norm", Math.abs(VectorMath.l2Norm(v0) - 1.0) < 1e-5);
    }

    private static void testSelfDotIsOne()
    {
        float[] v0 = buildFullyPopulatedVector();
        check("dot(v, v) == 1.0", Math.abs(VectorMath.dot(v0, v0) - 1.0) < 1e-5);
    }

    private static void testWeightRecovery()
    {
        float[] unitA0 = RbfEncoder.encodeLadder(100e6, ProfileVectorLayout.AUM_LADDER);
        float[] unitB0 = RbfEncoder.encodeLadder(500e6, ProfileVectorLayout.AUM_LADDER);

        float[] vectorA0 = buildVectorWithBlock("AUM", unitA0);
        float[] vectorB0 = buildVectorWithBlock("AUM", unitB0);

        double cosGap0 = 1.0 - VectorMath.dot(unitA0, unitB0);
        double aumWeight0 = findBlock("AUM").vectorWeight();
        double expected0 = aumWeight0 * cosGap0;
        double actual0 = 1.0 - VectorMath.dot(vectorA0, vectorB0);

        check("weight recovery for AUM block", Math.abs(actual0 - expected0) < 1e-4);
    }

    private static void testLadderMonotonicity()
    {
        double[] ladder0 = ProfileVectorLayout.CHECK_SIZE_LADDER;
        int refIndex0 = 4;
        float[] ref0 = RbfEncoder.encodeLadder(ladder0[refIndex0], ladder0);

        double prevDot0 = Double.POSITIVE_INFINITY;

        for (int i = refIndex0; i < ladder0.length; i++)
        {
            float[] candidate0 = RbfEncoder.encodeLadder(ladder0[i], ladder0);
            double dot0 = VectorMath.dot(ref0, candidate0);
            check("ladder monotonic decrease moving right at index " + i, dot0 <= prevDot0 + 1e-9);
            prevDot0 = dot0;
        }

        prevDot0 = Double.POSITIVE_INFINITY;

        for (int i = refIndex0; i >= 0; i--)
        {
            float[] candidate0 = RbfEncoder.encodeLadder(ladder0[i], ladder0);
            double dot0 = VectorMath.dot(ref0, candidate0);
            check("ladder monotonic decrease moving left at index " + i, dot0 <= prevDot0 + 1e-9);
            prevDot0 = dot0;
        }
    }

    private static void testMissingEncodesToZero()
    {
        float[] zeroFromZero0 = RbfEncoder.encodeLadder(0, ProfileVectorLayout.AUM_LADDER);
        float[] zeroFromNaN0 = RbfEncoder.encodeLadder(Double.NaN, ProfileVectorLayout.AUM_LADDER);

        check("encodeLadder(0, AUM_LADDER) is all-zero", isAllZero(zeroFromZero0));
        check("encodeLadder(NaN, AUM_LADDER) is all-zero", isAllZero(zeroFromNaN0));

        float[] unitAum0 = RbfEncoder.encodeLadder(100e6, ProfileVectorLayout.AUM_LADDER);
        float[] vectorPresent0 = buildVectorWithBlock("AUM", unitAum0);
        float[] vectorMissing0 = buildVectorWithBlock("AUM", zeroFromZero0);

        ProfileVectorLayout.Block aumBlock0 = findBlock("AUM");
        float[] aumSlicePresent0 = VectorMath.slice(
            vectorPresent0, aumBlock0.offset, aumBlock0.offset + aumBlock0.dims);
        float[] aumSliceMissing0 = VectorMath.slice(
            vectorMissing0, aumBlock0.offset, aumBlock0.offset + aumBlock0.dims);

        check("missing AUM block contributes exactly 0.0 to dot",
            VectorMath.dot(aumSlicePresent0, aumSliceMissing0) == 0.0);
    }

    private static void testOrdinalAdjacency()
    {
        int rankCount0 = ProfileVectorLayout.CONNECTION_POINT_ORDER.length;

        float[] priorLp0 = RbfEncoder.encodeOrdinal(ConnectionPoint.PRIOR_LP.rank(), rankCount0);
        float[] strongReferral0 = RbfEncoder.encodeOrdinal(ConnectionPoint.STRONG_REFERRAL.rank(), rankCount0);
        float[] coldOutbound0 = RbfEncoder.encodeOrdinal(ConnectionPoint.COLD_OUTBOUND.rank(), rankCount0);

        check("PRIOR_LP is closer to STRONG_REFERRAL than to COLD_OUTBOUND",
            VectorMath.dot(priorLp0, strongReferral0) > VectorMath.dot(priorLp0, coldOutbound0));

        check("encodeOrdinal(0, 10) is all-zero", isAllZero(RbfEncoder.encodeOrdinal(0, rankCount0)));
    }

    private static void testCodecRoundTrip()
    {
        float[] v0 = buildFullyPopulatedVector();
        String encoded0 = VectorCodec.encodeBase64(v0);
        float[] decoded0 = VectorCodec.decodeBase64(encoded0);

        check("codec round-trip length matches", decoded0.length == v0.length);

        boolean allWithinTolerance0 = true;

        for (int i = 0; i < v0.length; i++)
        {
            double diff0 = Math.abs(decoded0[i] - v0[i]);
            double tolerance0 = Math.max(1e-3, Math.abs(v0[i]) * 1e-3);

            if (diff0 > tolerance0)
            {
                allWithinTolerance0 = false;
                break;
            }
        }

        check("codec round-trip within float16 tolerance", allWithinTolerance0);
    }

    private static void testConnectionPointFromLabel()
    {
        ConnectionPoint a0 = ConnectionPoint.fromLabel("strong referral");
        ConnectionPoint b0 = ConnectionPoint.fromLabel("STRONG_REFERRAL");
        ConnectionPoint c0 = ConnectionPoint.fromLabel("Strong-Referral");
        ConnectionPoint unknown0 = ConnectionPoint.fromLabel("nonsense");

        check("fromLabel lowercase spaces", a0 == ConnectionPoint.STRONG_REFERRAL);
        check("fromLabel uppercase underscores", b0 == ConnectionPoint.STRONG_REFERRAL);
        check("fromLabel mixed case hyphens", c0 == ConnectionPoint.STRONG_REFERRAL);
        check("fromLabel unrecognized maps to UNKNOWN", unknown0 == ConnectionPoint.UNKNOWN);
    }

    // ---------- helpers ----------

    private static float[] buildFullyPopulatedVector()
    {
        float[] v0 = new float[ProfileVectorLayout.TOTAL_DIMS];

        for (ProfileVectorLayout.Block block0 : ProfileVectorLayout.BLOCKS)
        {
            float[] unit0 = deterministicUnitVectorFor(block0);
            placeBlock(v0, block0, unit0);
        }

        return v0;
    }

    /*
     * Builds a fully-populated vector but overrides the unit vector for one named
     * block, keeping every other block identical -- used by tests that isolate a
     * single block's contribution to the total dot product.
     */
    private static float[] buildVectorWithBlock(String blockName0, float[] overrideUnit0)
    {
        float[] v0 = new float[ProfileVectorLayout.TOTAL_DIMS];

        for (ProfileVectorLayout.Block block0 : ProfileVectorLayout.BLOCKS)
        {
            float[] unit0 = block0.name.equals(blockName0)
                ? overrideUnit0
                : deterministicUnitVectorFor(block0);
            placeBlock(v0, block0, unit0);
        }

        return v0;
    }

    private static void placeBlock(float[] v0, ProfileVectorLayout.Block block0, float[] unit0)
    {
        double scale0 = Math.sqrt(block0.vectorWeight());

        for (int j = 0; j < block0.dims; j++)
        {
            v0[block0.offset + j] = (float) (unit0[j] * scale0);
        }
    }

    private static float[] deterministicUnitVectorFor(ProfileVectorLayout.Block block0)
    {
        switch (block0.kind)
        {
            case "text":
                return deterministicTextUnitVector(block0.dims, block0.name.hashCode());
            case "ladder":
                double[] ladder0 = block0.dims == ProfileVectorLayout.AUM_LADDER.length
                    ? ProfileVectorLayout.AUM_LADDER
                    : ProfileVectorLayout.CHECK_SIZE_LADDER;
                return RbfEncoder.encodeLadder(ladder0[ladder0.length / 2], ladder0);
            case "ordinal":
                return RbfEncoder.encodeOrdinal(block0.dims / 2 + 1, block0.dims);
            default:
                throw new IllegalStateException("Unknown block kind: " + block0.kind);
        }
    }

    private static float[] deterministicTextUnitVector(int dims0, long seed0)
    {
        float[] v0 = new float[dims0];

        for (int i = 0; i < dims0; i++)
        {
            v0[i] = (float) Math.sin(seed0 * 0.7 + i * 0.13);
        }

        return VectorMath.normalize(v0);
    }

    private static ProfileVectorLayout.Block findBlock(String name0)
    {
        for (ProfileVectorLayout.Block block0 : ProfileVectorLayout.BLOCKS)
        {
            if (block0.name.equals(name0))
            {
                return block0;
            }
        }

        throw new IllegalArgumentException("Unknown block: " + name0);
    }

    private static boolean isAllZero(float[] v0)
    {
        for (float value0 : v0)
        {
            if (value0 != 0.0f)
            {
                return false;
            }
        }

        return true;
    }

    private static void check(String label0, boolean condition0)
    {
        if (condition0)
        {
            System.out.println("  ok   " + label0);
            return;
        }

        System.out.println("  FAIL " + label0);
        failures0++;
    }
}
