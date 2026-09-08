package com.liminer.embed;

import java.util.Arrays;

/*
 * VectorMath is plain arithmetic over flat float[] vectors -- no I/O, no dependency
 * on any block semantics beyond ProfileVectorLayout.ALPHA / independentDims() used by
 * similarity().
 */
public class VectorMath
{
    public static double dot(float[] a0, float[] b0)
    {
        if (a0.length != b0.length)
        {
            throw new IllegalArgumentException(
                "Vector length mismatch: " + a0.length + " vs " + b0.length);
        }

        double sum0 = 0.0;

        for (int i = 0; i < a0.length; i++)
        {
            sum0 += (double) a0[i] * (double) b0[i];
        }

        return sum0;
    }

    public static double l2Norm(float[] v0)
    {
        double sumSq0 = 0.0;

        for (float value0 : v0)
        {
            sumSq0 += (double) value0 * (double) value0;
        }

        return Math.sqrt(sumSq0);
    }

    public static float[] normalize(float[] v0)
    {
        double norm0 = l2Norm(v0);

        if (norm0 == 0.0)
        {
            return Arrays.copyOf(v0, v0.length);
        }

        float[] out0 = new float[v0.length];

        for (int i = 0; i < v0.length; i++)
        {
            out0[i] = (float) (v0[i] / norm0);
        }

        return out0;
    }

    public static float[] scale(float[] v0, double factor0)
    {
        float[] out0 = new float[v0.length];

        for (int i = 0; i < v0.length; i++)
        {
            out0[i] = (float) (v0[i] * factor0);
        }

        return out0;
    }

    public static float[] slice(float[] v0, int fromInclusive0, int toExclusive0)
    {
        return Arrays.copyOfRange(v0, fromInclusive0, toExclusive0);
    }

    /*
     * Similarity over the independent (GP-agnostic) prefix only, renormalized back to
     * unit length by dividing out (1 - ALPHA) -- the total weight mass of that slice.
     */
    public static double similarity(float[] a0, float[] b0)
    {
        int independentDims0 = ProfileVectorLayout.independentDims();

        float[] aIndependent0 = slice(a0, 0, independentDims0);
        float[] bIndependent0 = slice(b0, 0, independentDims0);

        return dot(aIndependent0, bIndependent0) / (1.0 - ProfileVectorLayout.ALPHA);
    }
}
