package ru.arc.dungeons.api;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * Stateless renderer for ARC's compact compass strip. Its 73 interior cells
 * use the vanilla default font, one degree per cell, centered on the yaw.
 */
public final class CompassStrip {
    /** Interior cell count; the angle brackets are rendered outside these cells. */
    public static final int WIDTH = 73;

    private static final int CENTER = WIDTH / 2;
    private static final double HALF_VIEW_DEGREES = CENTER;
    private static final TextColor ACCENT = TextColor.color(0xff6b6b);
    private static final TextColor SCALE = TextColor.color(0xe8dfd2);
    private static final Key FONT = Key.key("minecraft", "default");

    private CompassStrip() {}

    /**
     * A marker's absolute bearing, one Unicode code point, and 24-bit RGB color.
     * Later markers in a list render over earlier markers in the same cell.
     */
    public record Marker(double bearingDegrees, String glyph, int colorRgb) {
        public Marker {
            Objects.requireNonNull(glyph, "glyph");
            if (glyph.codePointCount(0, glyph.length()) != 1) {
                throw new IllegalArgumentException("A compass marker must be one Unicode code point");
            }
        }
    }

    /**
     * Renders one selected target, adding a turn arrow at the appropriate edge
     * when its bearing is outside the strip's 72-degree span. Yaw and bearing
     * use Bukkit's compass orientation: 0=south, 90=west, 180=north, 270=east.
     */
    public static Component render(float yawDegrees, double targetBearingDegrees, String markerGlyph, int markerColorRgb) {
        return render(yawDegrees, List.of(new Marker(targetBearingDegrees, markerGlyph, markerColorRgb)), true);
    }

    /** Renders visible markers; each bearing is absolute in Bukkit's compass orientation. */
    public static Component render(float yawDegrees, List<Marker> markers) {
        return render(yawDegrees, Objects.requireNonNull(markers, "markers"), false);
    }

    /** Returns the visible cell for a bearing, or -1 when it is invalid or outside the view. */
    public static int cellIndex(float yawDegrees, double bearingDegrees) {
        double relative = relativeBearing(yawDegrees, bearingDegrees);
        if (!Double.isFinite(relative) || relative < -HALF_VIEW_DEGREES || relative > HALF_VIEW_DEGREES) return -1;
        return (int) Math.round(CENTER + relative);
    }

    private static Component render(float yawDegrees, List<Marker> markers, boolean showEdgeCue) {
        String[] glyphs = new String[WIDTH];
        Arrays.fill(glyphs, " ");
        TextColor[] colors = new TextColor[WIDTH];
        Arrays.fill(colors, SCALE);

        for (int bearing = 0; bearing < 360; bearing += 90) {
            int cell = cellIndex(yawDegrees, bearing);
            if (cell < 0) continue;
            glyphs[cell] = switch (bearing) {
                case 0 -> "S";
                case 90 -> "W";
                case 180 -> "N";
                case 270 -> "E";
                default -> throw new IllegalStateException("Unsupported cardinal bearing");
            };
            colors[cell] = ACCENT;
        }

        for (Marker marker : markers) {
            Objects.requireNonNull(marker, "marker");
            int cell = cellIndex(yawDegrees, marker.bearingDegrees());
            if (cell < 0) {
                if (!showEdgeCue) continue;
                double relative = relativeBearing(yawDegrees, marker.bearingDegrees());
                if (!Double.isFinite(relative)) continue;
                cell = relative < -HALF_VIEW_DEGREES ? 0 : WIDTH - 1;
                glyphs[cell] = relative < -HALF_VIEW_DEGREES ? "←" : "→";
            } else {
                glyphs[cell] = marker.glyph();
            }
            colors[cell] = TextColor.color(marker.colorRgb() & 0x00ffffff);
        }

        Component strip = Component.text("<", ACCENT).font(FONT);
        for (int cell = 0; cell < WIDTH; cell++) {
            strip = strip.append(Component.text(glyphs[cell], colors[cell]));
        }
        return strip.append(Component.text(">", ACCENT));
    }

    private static double relativeBearing(float yawDegrees, double bearingDegrees) {
        if (!Double.isFinite(bearingDegrees)) return Double.NaN;
        double heading = Float.isFinite(yawDegrees) ? ((yawDegrees % 360.0 + 360.0) % 360.0) : 0.0;
        double degrees = bearingDegrees - heading;
        return ((degrees % 360.0 + 540.0) % 360.0) - 180.0;
    }
}
