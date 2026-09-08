package com.liminer.embed;

/*
 * RbfEncoder is the one Gaussian-over-index primitive shared by every numeric ladder
 * block and every ordinal block. Ladders are non-uniform in log10 space, so the
 * Gaussian runs over an interpolated bucket index rather than over log10 directly --
 * that is what lets one function serve both continuous ladders and discrete ordinals.
 *
 * Missing data (value <= 0, NaN, or rank <= 0) encodes as the all-zero vector, never as
 * the lowest bucket -- a value we failed to observe must contribute exactly 0.0 to the
 * dot product, not masquerade as a confirmed small value.
 */
public class RbfEncoder
{
    public static float[] encodeLadder(double value0, double[] ladder0)
    {
        if (value0 <= 0 || Double.isNaN(value0))
        {
            return new float[ladder0.length];
        }

        double log10Value0 = Math.log10(value0);
        double idx0 = interpolatedIndex(log10Value0, ladder0);
        idx0 = Math.max(0, Math.min(ladder0.length - 1, idx0));

        return gaussianOverIndex(idx0, ladder0.length);
    }

    public static float[] encodeOrdinal(int rank1Based0, int rankCount0)
    {
        if (rank1Based0 <= 0)
        {
            return new float[rankCount0];
        }

        double idx0 = rank1Based0 - 1;

        return gaussianOverIndex(idx0, rankCount0);
    }

    private static float[] gaussianOverIndex(double idx0, int n0)
    {
        float[] out0 = new float[n0];

        for (int i = 0; i < n0; i++)
        {
            double d0 = (i - idx0) / ProfileVectorLayout.SIGMA;
            out0[i] = (float) Math.exp(-0.5 * d0 * d0);
        }

        return VectorMath.normalize(out0);
    }

    /*
     * Piecewise-linear interpolation of log10Value0 onto a continuous bucket index,
     * using the ladder's own log10 positions. Clamps to the nearest end bucket if
     * log10Value0 falls outside the ladder's range.
     */
    static double interpolatedIndex(double log10Value0, double[] ladder0)
    {
        int n0 = ladder0.length;
        double[] log10Ladder0 = new double[n0];

        for (int i = 0; i < n0; i++)
        {
            log10Ladder0[i] = Math.log10(ladder0[i]);
        }

        if (log10Value0 <= log10Ladder0[0])
        {
            return 0;
        }

        if (log10Value0 >= log10Ladder0[n0 - 1])
        {
            return n0 - 1;
        }

        for (int i = 0; i < n0 - 1; i++)
        {
            if (log10Value0 >= log10Ladder0[i] && log10Value0 <= log10Ladder0[i + 1])
            {
                double frac0 = (log10Value0 - log10Ladder0[i]) / (log10Ladder0[i + 1] - log10Ladder0[i]);
                return i + frac0;
            }
        }

        return n0 - 1;
    }
}
