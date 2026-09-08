package com.liminer.embed;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/*
 * VectorCodec packs float[] vectors into base64-encoded IEEE-754 binary16 (half
 * precision), little-endian. Hand-rolled because Float.floatToFloat16 /
 * Float.float16ToFloat only exist from Java 20 onward, and this project targets
 * Java 11. Subnormal halves are flushed to zero; overflow clamps to the half-precision
 * max instead of emitting Infinity.
 */
public class VectorCodec
{
    private static final int HALF_SIGN_MASK = 0x8000;
    private static final int HALF_MAX_MAGNITUDE = 0x7bff;

    public static String encodeBase64(float[] v0)
    {
        ByteBuffer buffer0 = ByteBuffer.allocate(v0.length * 2).order(ByteOrder.LITTLE_ENDIAN);

        for (float value0 : v0)
        {
            buffer0.putShort(floatToHalfBits(value0));
        }

        return Base64.getEncoder().encodeToString(buffer0.array());
    }

    public static float[] decodeBase64(String encoded0)
    {
        byte[] bytes0 = Base64.getDecoder().decode(encoded0);
        ByteBuffer buffer0 = ByteBuffer.wrap(bytes0).order(ByteOrder.LITTLE_ENDIAN);

        float[] out0 = new float[bytes0.length / 2];

        for (int i = 0; i < out0.length; i++)
        {
            out0[i] = halfBitsToFloat(buffer0.getShort());
        }

        return out0;
    }

    static short floatToHalfBits(float value0)
    {
        int bits0 = Float.floatToRawIntBits(value0);
        int sign0 = (bits0 >>> 16) & HALF_SIGN_MASK;
        int exp0 = (bits0 >>> 23) & 0xff;
        int mantissa0 = bits0 & 0x7fffff;

        if (exp0 == 0xff)
        {
            if (mantissa0 != 0)
            {
                return (short) (sign0 | 0x7c00 | 0x200);
            }

            return (short) (sign0 | HALF_MAX_MAGNITUDE);
        }

        int unbiasedExp0 = exp0 - 127;

        if (unbiasedExp0 > 15)
        {
            return (short) (sign0 | HALF_MAX_MAGNITUDE);
        }

        if (unbiasedExp0 < -14)
        {
            return (short) sign0;
        }

        int halfExp0 = unbiasedExp0 + 15;
        int halfMantissa0 = mantissa0 >>> 13;
        int roundBit0 = (mantissa0 >>> 12) & 1;
        halfMantissa0 += roundBit0;

        if (halfMantissa0 == 0x400)
        {
            halfMantissa0 = 0;
            halfExp0 += 1;

            if (halfExp0 >= 31)
            {
                return (short) (sign0 | HALF_MAX_MAGNITUDE);
            }
        }

        return (short) (sign0 | (halfExp0 << 10) | halfMantissa0);
    }

    static float halfBitsToFloat(short half0)
    {
        int bits0 = half0 & 0xffff;
        int sign0 = (bits0 & 0x8000) << 16;
        int exp0 = (bits0 >>> 10) & 0x1f;
        int mantissa0 = bits0 & 0x3ff;

        if (exp0 == 0)
        {
            if (mantissa0 == 0)
            {
                return Float.intBitsToFloat(sign0);
            }

            int shift0 = 0;
            int m0 = mantissa0;

            while ((m0 & 0x400) == 0)
            {
                m0 <<= 1;
                shift0++;
            }

            m0 &= 0x3ff;
            int floatExp0 = 127 - 15 - shift0;
            int floatBits0 = sign0 | (floatExp0 << 23) | (m0 << 13);

            return Float.intBitsToFloat(floatBits0);
        }

        if (exp0 == 0x1f)
        {
            if (mantissa0 == 0)
            {
                return Float.intBitsToFloat(sign0 | 0x7f800000);
            }

            return Float.intBitsToFloat(sign0 | 0x7f800000 | (mantissa0 << 13));
        }

        int floatExp0 = exp0 - 15 + 127;
        int floatBits0 = sign0 | (floatExp0 << 23) | (mantissa0 << 13);

        return Float.intBitsToFloat(floatBits0);
    }
}
