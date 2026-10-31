package com.ravenemu.app.ui.common;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.ImageView;
import androidx.core.graphics.PathParser;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** RavenEmu's icons, drawn for it on a 24 × 24 grid: one 2-unit line with
 * round ends and corners, and a few filled details, all in the accent colour
 * (the owner's choice; gold only marks an unlock in the banner), so that
 * every screen draws the same crisp shapes at any density, never a font's
 * symbol. The console pictograms follow the beta's ConsoleIcons (portrait
 * handheld, the same with a colour screen, landscape handheld, a grid for
 * every game); no manufacturer logo or artwork. */
public final class RavenIcon extends Drawable {
    public enum Glyph {
        SETTINGS, ADD, MORE, SEARCH, BACK, CHEVRON, EXTERNAL,
        ALL, GB, GBC, GBA,
        TROPHY, SLIDERS, SPEAKER, MONITOR, GAMEPAD, INFO,
        STAR, RADIO_ON, RADIO_OFF,
    }

    private static final float STROKE = 2f;
    private static final int[] COLOR_SCREEN = {0xffe5566e, 0xfff2c14e, 0xff4ec9e0};
    private static final Map<Glyph, String[]> SPECS = new EnumMap<>(Glyph.class);
    private static final Map<Glyph, Path[]> PATHS = new EnumMap<>(Glyph.class);

    static {
        // {stroked outline, filled details}; either may be empty.
        SPECS.put(Glyph.SETTINGS, new String[]{
                "M10.16,5.14L10.55,2.81L13.45,2.81L13.84,5.14A7.1,7.1 0 0 1 15.55,5.85L17.47,4.48L19.52,6.53"
                        + "L18.15,8.45A7.1,7.1 0 0 1 18.86,10.16L21.19,10.55L21.19,13.45L18.86,13.84"
                        + "A7.1,7.1 0 0 1 18.15,15.55L19.52,17.47L17.47,19.52L15.55,18.15A7.1,7.1 0 0 1 13.84,18.86"
                        + "L13.45,21.19L10.55,21.19L10.16,18.86A7.1,7.1 0 0 1 8.45,18.15L6.53,19.52L4.48,17.47"
                        + "L5.85,15.55A7.1,7.1 0 0 1 5.14,13.84L2.81,13.45L2.81,10.55L5.14,10.16"
                        + "A7.1,7.1 0 0 1 5.85,8.45L4.48,6.53L6.53,4.48L8.45,5.85A7.1,7.1 0 0 1 10.16,5.14Z"
                        + circle(12, 12, 3), ""});
        SPECS.put(Glyph.ADD, new String[]{"M12,5V19M5,12H19", ""});
        SPECS.put(Glyph.MORE, new String[]{"", circle(12, 5, 1.75f) + circle(12, 12, 1.75f) + circle(12, 19, 1.75f)});
        SPECS.put(Glyph.SEARCH, new String[]{circle(10.5f, 10.5f, 6.5f) + "M15.3,15.3L20,20", ""});
        SPECS.put(Glyph.BACK, new String[]{"M15,5L8,12L15,19", ""});
        SPECS.put(Glyph.CHEVRON, new String[]{"M9,5L16,12L9,19", ""});
        SPECS.put(Glyph.EXTERNAL, new String[]{"M14,4H20V10M20,4L11,13"
                + "M19,14V18A2,2 0 0 1 17,20H6A2,2 0 0 1 4,18V7A2,2 0 0 1 6,5H10", ""});
        SPECS.put(Glyph.ALL, new String[]{rounded(3.5f, 3.5f, 7, 7, 1.8f) + rounded(13.5f, 3.5f, 7, 7, 1.8f)
                + rounded(3.5f, 13.5f, 7, 7, 1.8f) + rounded(13.5f, 13.5f, 7, 7, 1.8f), ""});
        String handheld = rounded(6, 2, 12, 20, 2.6f);
        String handheldDetails = cross(9.6f, 16.2f, 2.4f, 1.3f) + circle(14.6f, 17, 1.05f) + circle(16.3f, 15.2f, 1.05f);
        SPECS.put(Glyph.GB, new String[]{handheld, rounded(8.5f, 4.5f, 7, 6, 1) + handheldDetails});
        // Its screen is drawn in colour, apart.
        SPECS.put(Glyph.GBC, new String[]{handheld, handheldDetails});
        SPECS.put(Glyph.GBA, new String[]{rounded(1.8f, 6, 20.4f, 12, 4),
                rounded(7.6f, 8.4f, 8.8f, 7.2f, 1) + cross(4.9f, 12, 2, 1.2f)
                        + circle(18.3f, 13.1f, 1) + circle(19.8f, 11.3f, 1)});
        SPECS.put(Glyph.TROPHY, new String[]{"M8,4H16V9A4,4 0 0 1 8,9Z"
                + "M8,6H6.5A2,2 0 0 0 6.5,10H8M16,6H17.5A2,2 0 0 1 17.5,10H16M12,13V17M10,17H14M8.5,20H15.5", ""});
        SPECS.put(Glyph.SLIDERS, new String[]{"M4,7H8M12,7H20M4,12H14M18,12H20M4,17H6M10,17H20"
                + circle(10, 7, 2) + circle(16, 12, 2) + circle(8, 17, 2), ""});
        SPECS.put(Glyph.SPEAKER, new String[]{"M4,9.5H7.5L12,5.5V18.5L7.5,14.5H4Z"
                + "M15.5,9.5A3.5,3.5 0 0 1 15.5,14.5M18,7A7,7 0 0 1 18,17", ""});
        SPECS.put(Glyph.MONITOR, new String[]{rounded(3, 4, 18, 12, 2) + "M12,16V20M8,20H16", ""});
        SPECS.put(Glyph.GAMEPAD, new String[]{"M7.5,7H16.5A4.5,4.5 0 0 1 21,11.5V13.5A3.5,3.5 0 0 1 14.9,15.7L14,14.5"
                + "H10L9.1,15.7A3.5,3.5 0 0 1 3,13.5V11.5A4.5,4.5 0 0 1 7.5,7ZM7.5,9.6V12.8M5.9,11.2H9.1",
                circle(15.8f, 10.4f, 1) + circle(17.6f, 12.2f, 1)});
        SPECS.put(Glyph.INFO, new String[]{circle(12, 12, 9) + "M12,11V16.5", circle(12, 7.6f, 1.2f)});
        // Filled and outlined alike: the line rounds its points.
        String star = "M12,4L14.29,9.44L20.18,9.94L15.71,13.81L17.05,19.56L12,16.5L6.95,19.56L8.29,13.81"
                + "L3.82,9.94L9.71,9.44Z";
        SPECS.put(Glyph.STAR, new String[]{star, star});
        SPECS.put(Glyph.RADIO_ON, new String[]{circle(12, 12, 8), circle(12, 12, 4)});
        SPECS.put(Glyph.RADIO_OFF, new String[]{circle(12, 12, 8), ""});
    }

    private final Glyph glyph;
    private final int size;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint screen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int tint, screenAlpha = 255;

    /** `sizePx` is the drawable's intrinsic size, the 24-unit grid's. */
    public RavenIcon(Glyph glyph, int tint, int sizePx) {
        this.glyph = glyph;
        this.size = sizePx;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        screen.setShader(new LinearGradient(8.5f, 0, 15.5f, 0, COLOR_SCREEN, null, Shader.TileMode.CLAMP));
        setTint(tint);
    }

    public Glyph glyph() { return glyph; }
    public int tint() { return tint; }

    @Override public void setTint(int color) {
        tint = color;
        line.setColor(color);
        fill.setColor(color);
        invalidateSelf();
    }

    /** The GBC's colour screen, full on its active page, half on the others (the beta's). */
    public void setScreenAlpha(int alpha) {
        screenAlpha = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        Rect box = getBounds();
        if (box.isEmpty()) return;
        Path[] paths = paths(glyph);
        canvas.save();
        float scale = Math.min(box.width(), box.height()) / 24f;
        canvas.translate(box.left + (box.width() - 24 * scale) / 2f, box.top + (box.height() - 24 * scale) / 2f);
        canvas.scale(scale, scale);
        if (paths[1] != null) canvas.drawPath(paths[1], fill);
        if (glyph == Glyph.GBC) {
            screen.setAlpha(screenAlpha);
            canvas.drawPath(paths(Glyph.GB)[2], screen);
        }
        if (paths[0] != null) canvas.drawPath(paths[0], line);
        canvas.restore();
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) {
        line.setAlpha(alpha);
        fill.setAlpha(alpha);
        invalidateSelf();
    }
    @Override public void setColorFilter(ColorFilter filter) {
        line.setColorFilter(filter);
        fill.setColorFilter(filter);
        invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    /** A square, clickable icon: `iconDp` drawn in a `boxDp` touch target,
     * with a soft accent ripple and its spoken label. */
    public static ImageView button(Context context, Glyph glyph, int tint, int iconDp, String description) {
        float density = context.getResources().getDisplayMetrics().density;
        ImageView view = new ImageView(context);
        view.setImageDrawable(new RavenIcon(glyph, tint, Math.round(iconDp * density)));
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setContentDescription(description);
        view.setClickable(true);
        view.setFocusable(true);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33a78bfa), null, null));
        return view;
    }

    /** A decorative icon, silent for accessibility. */
    public static ImageView image(Context context, Glyph glyph, int tint, int iconDp) {
        ImageView view = button(context, glyph, tint, iconDp, null);
        view.setClickable(false);
        view.setFocusable(false);
        view.setBackground(null);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private static synchronized Path[] paths(Glyph glyph) {
        Path[] paths = PATHS.get(glyph);
        if (paths == null) {
            String[] spec = SPECS.get(glyph);
            paths = new Path[]{parse(spec[0]), parse(spec[1]),
                    glyph == Glyph.GB ? parse(rounded(8.5f, 4.5f, 7, 6, 1)) : null};
            PATHS.put(glyph, paths);
        }
        return paths;
    }

    private static Path parse(String data) {
        return data.isEmpty() ? null : PathParser.createPathFromPathData(data);
    }

    /** The path data of every glyph, {stroke, fill}: for the preview and tests. */
    static String[] spec(Glyph glyph) { return SPECS.get(glyph).clone(); }

    static String circle(float cx, float cy, float r) {
        return String.format(Locale.ROOT, "M%s,%sA%s,%s 0 1 1 %s,%sA%s,%s 0 1 1 %s,%sZ",
                n(cx - r), n(cy), n(r), n(r), n(cx + r), n(cy), n(r), n(r), n(cx - r), n(cy));
    }

    static String rounded(float x, float y, float w, float h, float r) {
        return String.format(Locale.ROOT, "M%s,%sH%sA%s,%s 0 0 1 %s,%sV%sA%s,%s 0 0 1 %s,%sH%sA%s,%s 0 0 1 %s,%sV%s"
                        + "A%s,%s 0 0 1 %s,%sZ",
                n(x + r), n(y), n(x + w - r), n(r), n(r), n(x + w), n(y + r), n(y + h - r), n(r), n(r),
                n(x + w - r), n(y + h), n(x + r), n(r), n(r), n(x), n(y + h - r), n(y + r), n(r), n(r),
                n(x + r), n(y));
    }

    /** A filled cross (a d-pad): arms `reach` from the centre, `thick` wide. */
    static String cross(float cx, float cy, float reach, float thick) {
        float t = thick / 2f;
        return String.format(Locale.ROOT, "M%s,%sH%sV%sH%sV%sH%sV%sH%sV%sH%sV%sH%sZ",
                n(cx - t), n(cy - reach), n(cx + t), n(cy - t), n(cx + reach), n(cy + t), n(cx + t), n(cy + reach),
                n(cx - t), n(cy + t), n(cx - reach), n(cy - t), n(cx - t));
    }

    private static String n(float value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        return text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
    }
}
