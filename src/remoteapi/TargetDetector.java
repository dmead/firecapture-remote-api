package remoteapi;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the bright target (planet, Sun, Moon) in a frame: flux-weighted centroid of pixels above half the peak,
 * on a 4x-downsampled luminance image. Coordinates are frame pixels (same as GET /v1/camera/frame).
 */
final class TargetDetector {
    static final class Target {
        final double x, y;              // centroid
        final int minX, minY, maxX, maxY;
        final int peak;
        final boolean touchesEdge;
        Target(double x, double y, int minX, int minY, int maxX, int maxY, int peak, boolean touchesEdge) {
            this.x = x; this.y = y; this.minX = minX; this.minY = minY; this.maxX = maxX; this.maxY = maxY;
            this.peak = peak; this.touchesEdge = touchesEdge;
        }
        int width() { return maxX - minX; }
        int height() { return maxY - minY; }
        /** Radius of the body: half the smaller bbox side (Saturn's rings widen the bbox horizontally). */
        double radius() { return Math.min(width(), height()) / 2.0; }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", Math.round(x * 10) / 10.0);
            m.put("y", Math.round(y * 10) / 10.0);
            m.put("radius", Math.round(radius() * 10) / 10.0);
            m.put("bbox", List.of(minX, minY, width(), height()));
            m.put("peak", peak);
            m.put("touchesEdge", touchesEdge);
            return m;
        }
    }

    private static final int DS = 4;
    private static final int MIN_PEAK = 20;          // below this (of 255) there's nothing to find
    private static final int MIN_PIXELS = 4;         // downsampled pixels above threshold

    private TargetDetector() { }

    static Target detect(BufferedImage img) {
        if (img == null) return null;
        int w = img.getWidth() / DS, h = img.getHeight() / DS;
        if (w < 2 || h < 2) return null;
        int[] lum = new int[w * h];
        int max = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sum = 0;
                for (int dy = 0; dy < DS; dy++)
                    for (int dx = 0; dx < DS; dx++) {
                        int rgb = img.getRGB(x * DS + dx, y * DS + dy);
                        sum += ((rgb >> 16) & 0xff) * 299 + ((rgb >> 8) & 0xff) * 587 + (rgb & 0xff) * 114;
                    }
                int v = sum / (DS * DS * 1000);
                lum[y * w + x] = v;
                if (v > max) max = v;
            }
        }
        if (max < MIN_PEAK) return null;
        int thr = max / 2;
        double sw = 0, sx = 0, sy = 0;
        int n = 0, x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int v = lum[y * w + x];
                if (v <= thr) continue;
                double k = v - thr;
                sw += k; sx += k * x; sy += k * y; n++;
                x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
            }
        if (n < MIN_PIXELS || sw == 0) return null;
        boolean edge = x0 == 0 || y0 == 0 || x1 == w - 1 || y1 == h - 1;
        return new Target((sx / sw + 0.5) * DS, (sy / sw + 0.5) * DS, x0 * DS, y0 * DS, (x1 + 1) * DS, (y1 + 1) * DS, max, edge);
    }
}
