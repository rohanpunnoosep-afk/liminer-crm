package com.liminer.embed;

import com.liminer.core.ConnectionPoint;
import com.liminer.core.LpContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/*
 * Offline verification of ProfileVectorEncoder / GpPreferenceVector (task 0173). Uses a
 * stubbed EmbedderSeam returning deterministic synthetic unit vectors -- no network call,
 * no OPENAI_API_KEY required.
 */
public class ProfileVectorEncoderTestMain
{
    private static int failures0 = 0;

    public static void main(String[] args0) throws Exception
    {
        testFullyPopulatedProfileIsUnitNorm();
        testIndependentAndConnectionPointNormSplit();
        testSelfSimilarityIsOne();
        testSparseStaysSparse();
        testWeightRecovery();
        testBatchingIsSingleCallAndNoBlanks();
        testConnectionPointIsolation();
        testGpPreferenceIsNotAMirror();

        if (failures0 > 0)
        {
            System.out.println("PROFILE_ENCODER_FAILED: " + failures0 + " check(s) failed");
            System.exit(1);
        }

        System.out.println("PROFILE_ENCODER_OK");
    }

    // ---------- checks ----------

    private static void testFullyPopulatedProfileIsUnitNorm() throws Exception
    {
        ProfileVectorEncoder encoder0 = new ProfileVectorEncoder(new StubEmbedder());
        CanonicalProfile profile0 = fullyPopulatedProfile();

        float[] v0 = encoder0.encode(profile0, ConnectionPoint.PRIOR_LP);

        check("full profile has TOTAL_DIMS length", v0.length == ProfileVectorLayout.TOTAL_DIMS);
        check("full profile is unit norm", closeTo(VectorMath.l2Norm(v0), 1.0, 1e-5));
    }

    private static void testIndependentAndConnectionPointNormSplit() throws Exception
    {
        ProfileVectorEncoder encoder0 = new ProfileVectorEncoder(new StubEmbedder());
        CanonicalProfile profile0 = fullyPopulatedProfile();

        float[] v0 = encoder0.encode(profile0, ConnectionPoint.PRIOR_LP);

        float[] independent0 = VectorMath.slice(v0, 0, ProfileVectorLayout.independentDims());
        float[] cp0 = VectorMath.slice(v0, ProfileVectorLayout.independentDims(), ProfileVectorLayout.TOTAL_DIMS);

        double independentSq0 = VectorMath.dot(independent0, independent0);
        double cpSq0 = VectorMath.dot(cp0, cp0);

        check("independent prefix squared norm is 1-ALPHA",
            closeTo(independentSq0, 1.0 - ProfileVectorLayout.ALPHA, 1e-5));
        check("connection point squared norm is ALPHA",
            closeTo(cpSq0, ProfileVectorLayout.ALPHA, 1e-5));
    }

    private static void testSelfSimilarityIsOne() throws Exception
    {
        ProfileVectorEncoder encoder0 = new ProfileVectorEncoder(new StubEmbedder());
        CanonicalProfile profile0 = fullyPopulatedProfile();

        float[] v0 = encoder0.encode(profile0, ConnectionPoint.PRIOR_LP);

        check("similarity(v,v) is 1.0", closeTo(VectorMath.similarity(v0, v0), 1.0, 1e-5));
    }

    private static void testSparseStaysSparse() throws Exception
    {
        ProfileVectorEncoder encoder0 = new ProfileVectorEncoder(new StubEmbedder());
        CanonicalProfile profile0 = new CanonicalProfile();
        profile0.thesis.add("climate tech seed investor");

        float[] v0 = encoder0.encode(profile0);

        check("sparse profile norm < 1", VectorMath.l2Norm(v0) < 1.0 - 1e-6);

        int aumOffset0 = ProfileVectorLayout.offsetOf("AUM");
        float[] aumBlock0 = VectorMath.slice(v0, aumOffset0, aumOffset0 + ProfileVectorLayout.AUM_LADDER.length);
        boolean allZero0 = true;

        for (float f0 : aumBlock0)
        {
            if (f0 != 0.0f)
            {
                allZero0 = false;
                break;
            }
        }

        check("sparse profile AUM block is all zero", allZero0);
    }

    private static void testWeightRecovery() throws Exception
    {
        ProfileVectorEncoder encoder0 = new ProfileVectorEncoder(new StubEmbedder());

        CanonicalProfile a0 = fullyPopulatedProfile();
        CanonicalProfile b0 = fullyPopulatedProfile();
        b0.aumUsd = 250e6;

        float[] va0 = encoder0.encode(a0);
        float[] vb0 = encoder0.encode(b0);

        float[] aumRawA0 = RbfEncoder.encodeLadder(a0.aumUsd, ProfileVectorLayout.AUM_LADDER);
        float[] aumRawB0 = RbfEncoder.encodeLadder(b0.aumUsd, ProfileVectorLayout.AUM_LADDER);
        double cosAum0 = VectorMath.dot(VectorMath.normalize(aumRawA0), VectorMath.normalize(aumRawB0));

        double wAumEff0 = 0.16 * (1.0 - ProfileVectorLayout.ALPHA);
        double expectedDot0 = (1.0 - ProfileVectorLayout.ALPHA) - wAumEff0 + wAumEff0 * cosAum0;

        double actualDot0 = VectorMath.dot(va0, vb0);

        check("weight recovery: AUM-only diff matches weight*cosine-gap",
            closeTo(actualDot0, expectedDot0, 1e-4));
    }

    private static void testBatchingIsSingleCallAndNoBlanks() throws Exception
    {
        StubEmbedder stub0 = new StubEmbedder();
        ProfileVectorEncoder encoder0 = new ProfileVectorEncoder(stub0);

        CanonicalProfile profile0 = fullyPopulatedProfile();
        encoder0.encode(profile0);

        check("embed called exactly once", stub0.callCount == 1);

        boolean anyBlank0 = false;

        for (String text0 : stub0.allTextsSeen)
        {
            if (text0 == null || text0.trim().isEmpty())
            {
                anyBlank0 = true;
            }
        }

        check("no blank string passed to embed", !anyBlank0);
        check("all three text modules were batched", stub0.allTextsSeen.size() == 3);
    }

    private static void testConnectionPointIsolation() throws Exception
    {
        ProfileVectorEncoder encoder0 = new ProfileVectorEncoder(new StubEmbedder());

        CanonicalProfile profile0 = fullyPopulatedProfile();

        float[] v1 = encoder0.encode(profile0, ConnectionPoint.COLD_OUTBOUND);
        float[] v2 = encoder0.encode(profile0, ConnectionPoint.PRIOR_LP);

        check("connection point cannot leak into LP-to-LP similarity",
            closeTo(VectorMath.similarity(v1, v2), 1.0, 1e-5));
    }

    private static void testGpPreferenceIsNotAMirror() throws Exception
    {
        ProfileVectorEncoder encoder0 = new ProfileVectorEncoder(new StubEmbedder());

        LpContext.GpProfile smallFundGp0 = new LpContext.GpProfile();
        smallFundGp0.investmentThesis = "early stage climate seed fund";
        smallFundGp0.sectors = "climate, energy";

        LpContext.GpProfile bigFundGp0 = new LpContext.GpProfile();
        bigFundGp0.investmentThesis = "growth stage climate fund";
        bigFundGp0.sectors = "climate, energy";

        float[] gpSmall0 = GpPreferenceVector.build(smallFundGp0, null, encoder0);
        float[] gpBig0 = GpPreferenceVector.build(bigFundGp0, null, encoder0);

        CanonicalProfile lpLargeAum0 = new CanonicalProfile();
        lpLargeAum0.aumUsd = 100e9;

        CanonicalProfile lpSmallAum0 = new CanonicalProfile();
        lpSmallAum0.aumUsd = 5e6;

        float[] vLargeAum0 = encoder0.encode(lpLargeAum0);
        float[] vSmallAum0 = encoder0.encode(lpSmallAum0);

        double dotSmallGpWithLargeLp0 = VectorMath.dot(gpSmall0, vLargeAum0);
        double dotSmallGpWithSmallLp0 = VectorMath.dot(gpSmall0, vSmallAum0);
        double dotBigGpWithLargeLp0 = VectorMath.dot(gpBig0, vLargeAum0);
        double dotBigGpWithSmallLp0 = VectorMath.dot(gpBig0, vSmallAum0);

        check("default AUM preference rewards LP magnitude",
            dotSmallGpWithLargeLp0 > dotSmallGpWithSmallLp0);

        check("a small-fund GP does not score the small-AUM LP higher",
            dotSmallGpWithSmallLp0 <= dotSmallGpWithLargeLp0);

        check("preference is independent of the GP's own thesis text (not mirrored)",
            dotBigGpWithLargeLp0 > dotBigGpWithSmallLp0);
    }

    // ---------- fixtures ----------

    private static CanonicalProfile fullyPopulatedProfile()
    {
        CanonicalProfile p0 = new CanonicalProfile();
        p0.thesis.add("climate tech seed investor");
        p0.pastInvestments.add("acme robotics");
        p0.newInvestmentAreas.add("carbon removal");
        p0.aumUsd = 500e6;
        p0.capitalAllocatableUsd = 2e6;
        p0.pastInvestmentAmountUsd = 1e6;
        p0.allocatorType = ProfileVectorLayout.ALLOCATOR_TYPE_ORDER[2];
        p0.timingMonthsSinceLastClose = 5;
        p0.canonicalize();
        return p0;
    }

    private static class StubEmbedder implements ProfileVectorEncoder.EmbedderSeam
    {
        int callCount = 0;
        List<String> allTextsSeen = new ArrayList<String>();
        Map<String, float[]> cache = new HashMap<String, float[]>();

        @Override
        public List<float[]> embed(List<String> texts0, int dims0) throws Exception
        {
            callCount++;
            allTextsSeen.addAll(texts0);

            List<float[]> out0 = new ArrayList<float[]>();

            for (String text0 : texts0)
            {
                out0.add(syntheticUnitVector(text0, dims0));
            }

            return out0;
        }

        private float[] syntheticUnitVector(String text0, int dims0)
        {
            float[] cached0 = cache.get(text0);

            if (cached0 != null)
            {
                return cached0;
            }

            Random rnd0 = new Random(text0.hashCode());
            float[] raw0 = new float[dims0];

            for (int i0 = 0; i0 < dims0; i0++)
            {
                raw0[i0] = (float) rnd0.nextGaussian();
            }

            float[] unit0 = VectorMath.normalize(raw0);
            cache.put(text0, unit0);
            return unit0;
        }
    }

    // ---------- helpers ----------

    private static boolean closeTo(double actual0, double expected0, double tolerance0)
    {
        return Math.abs(actual0 - expected0) <= tolerance0;
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
