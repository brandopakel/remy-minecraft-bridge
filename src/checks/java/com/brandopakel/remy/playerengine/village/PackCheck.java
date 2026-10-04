package com.brandopakel.remy.playerengine.village;
public class PackCheck {
    public static void main(String[] a) {
        int[] v = new int[4096];
        for (int i = 0; i < v.length; i++) v[i] = (i * 7 + i / 13) % 21; // palette of 21 -> 5 bits
        StringBuilder sb = new StringBuilder();
        for (long l : GdmcBridge.pack(v, 5)) sb.append(l).append(',');
        System.out.println(sb);
        int[] h = new int[256];
        for (int i = 0; i < 256; i++) h[i] = 60 + (i % 200);
        sb = new StringBuilder();
        for (long l : GdmcBridge.pack(h, 9)) sb.append(l).append(',');
        System.out.println(sb);
    }
}
