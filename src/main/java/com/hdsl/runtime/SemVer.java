package com.hdsl.runtime;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.regex.Pattern;

/** SemVer precedence with deterministic build-metadata tie breaking. */
public final class SemVer {
    private static final Pattern VALID = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");
    public static final Comparator<String> DESCENDING = (left, right) -> compare(right, left);
    private SemVer() { }
    public static boolean isVersion(String version) { return version != null && version.length() < 160 && VALID.matcher(version).matches(); }
    public static int compare(String a, String b) {
        var x = VALID.matcher(a); var y = VALID.matcher(b);
        if (!x.matches() || !y.matches()) return a.compareTo(b);
        for (int i = 1; i <= 3; i++) {
            int c = new BigInteger(x.group(i)).compareTo(new BigInteger(y.group(i)));
            if (c != 0) return c;
        }
        String px = x.group(4), py = y.group(4);
        if (px == null && py != null) return 1;
        if (px != null && py == null) return -1;
        if (px != null) {
            String[] xs = px.split("\\."), ys = py.split("\\.");
            for (int i = 0; i < Math.min(xs.length, ys.length); i++) {
                boolean xn = xs[i].matches("\\d+"), yn = ys[i].matches("\\d+");
                int c = xn && yn ? new BigInteger(xs[i]).compareTo(new BigInteger(ys[i]))
                        : xn != yn ? (xn ? -1 : 1) : xs[i].compareTo(ys[i]);
                if (c != 0) return c;
            }
            int c = Integer.compare(xs.length, ys.length); if (c != 0) return c;
        }
        return a.compareTo(b);
    }
}
