package com.hiit.steps;

public class Conversion {
    public static long intsToLong(int a, int b) {
        return (long) a << 32 | b & 0xffffffffl;
    }

    public static int longToIntA(long l) {
        return (int) (l >> 32);
    }

    public static int longToIntB(long l) {
        return (int) l;
    }

    public static void longToIntArray(long l, int[] dst, int dstPos) {
        dst[dstPos] = longToIntA(l);
        dst[dstPos + 1] = longToIntB(l);
    }

    public static long intArrayToLong(int[] src, int srcPos) {
        return intsToLong(src[srcPos], src[srcPos + 1]);
    }
}
