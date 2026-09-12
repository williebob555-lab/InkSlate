import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The InkSlate mark, and every file that has to carry it.
 *
 * One mark, described once. The tablet's launcher icon, the Windows application and its installer,
 * and the picture on the project page were three separate drawings before this, which is how an app
 * ends up looking like a different app depending on where you meet it.
 *
 * Run it with the JDK's single-file mode - no build step, no dependency, nothing to keep in step:
 *
 *     java brand/Brand.java
 *
 * It rewrites the generated files in place; they are committed, so a checkout builds without
 * needing to run this at all. Change the geometry or the colours below and run it again.
 */
public final class Brand {

    // ---- the mark -------------------------------------------------------------
    //
    // Drawn in a 108x108 box, which is the tablet's adaptive-icon grid: the middle 72x72 is what
    // survives every mask a launcher might apply, so nothing that matters strays outside it.
    //
    // Curves rather than arcs for the rounded corners, so one small parser can serve both the
    // raster written here and the vector handed to Android. Two ways of saying the same shape is
    // how the two of them drift.

    /** The page: a sheet with its top-right corner turned over. */
    private static final String PAGE =
        "M42,30 h22 l12,12 v36 c0,2.2 -1.8,4 -4,4 h-30 c-2.2,0 -4,-1.8 -4,-4 "
            + "v-44 c0,-2.2 1.8,-4 4,-4 z";

    /** The turned corner itself, so the sheet reads as paper rather than a rectangle. */
    private static final String FOLD = "M64,30 v8 c0,2.2 1.8,4 4,4 h8 z";

    /** A line of handwriting across it. */
    private static final String INK =
        "M44,68 c4,-10 8,-16 12,-16 c4,0 2,10 6,10 c3,0 5,-4 6,-8";

    private static final Color BACKGROUND = new Color(0x10, 0x14, 0x18);
    private static final Color PAPER = Color.WHITE;
    private static final Color FOLD_SHADE = new Color(0xC8, 0xD2, 0xDC);
    private static final Color INK_COLOUR = new Color(0x1B, 0x6F, 0xE0);
    private static final float INK_WIDTH = 3.2f;

    /** Windows draws the icon as given, so the mark carries its own rounded tile. */
    private static final float TILE_INSET = 6f;
    private static final float TILE_RADIUS = 22f;

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".");

        // Windows: the application, its Start menu entry, its taskbar button and the installer.
        writeIco(root.resolve("brand/inkslate.ico"), new int[] { 16, 24, 32, 48, 64, 128, 256 });

        // The project page, and anywhere else a plain picture is wanted.
        ImageIO.write(render(512, true), "png", root.resolve("brand/inkslate-512.png").toFile());

        // Android: the same geometry as vectors, which is what a launcher wants.
        writeAndroidVector(root.resolve("app/src/main/res/drawable/ic_launcher_foreground.xml"));
        writeAndroidColour(root.resolve("app/src/main/res/values/ic_launcher_background.xml"));

        System.out.println("Brand assets written under " + root.toAbsolutePath());
    }

    // ---- drawing --------------------------------------------------------------

    private static BufferedImage render(int size, boolean tile) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(
            RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        double scale = size / 108.0;
        g.transform(AffineTransform.getScaleInstance(scale, scale));

        if (tile) {
            g.setColor(BACKGROUND);
            g.fill(new RoundRectangle2D.Float(
                TILE_INSET, TILE_INSET,
                108 - TILE_INSET * 2, 108 - TILE_INSET * 2,
                TILE_RADIUS, TILE_RADIUS));
        }

        g.setColor(PAPER);
        g.fill(parse(PAGE));
        g.setColor(FOLD_SHADE);
        g.fill(parse(FOLD));

        g.setColor(INK_COLOUR);
        g.setStroke(new BasicStroke(INK_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(parse(INK));

        g.dispose();
        return image;
    }

    /**
     * Enough of the SVG path language for this mark: move, line, horizontal, vertical, cubic and
     * close, in both absolute and relative forms.
     *
     * Deliberately small. A general parser would be a dependency in all but name, and the point of
     * this file is that there is nothing to install before the icons can be rebuilt.
     */
    private static Path2D.Float parse(String d) {
        Path2D.Float path = new Path2D.Float();
        float x = 0, y = 0, startX = 0, startY = 0;
        int i = 0;
        char command = 0;
        while (i < d.length()) {
            char c = d.charAt(i);
            if (Character.isLetter(c)) {
                command = c;
                i++;
                continue;
            }
            if (c == ' ' || c == ',') {
                i++;
                continue;
            }
            float[] n = new float[6];
            int taken = 0;
            int needed = switch (Character.toLowerCase(command)) {
                case 'm', 'l' -> 2;
                case 'h', 'v' -> 1;
                case 'c' -> 6;
                case 'z' -> 0;
                default -> throw new IllegalArgumentException("Unsupported command " + command);
            };
            while (taken < needed) {
                while (i < d.length() && (d.charAt(i) == ' ' || d.charAt(i) == ',')) i++;
                int start = i;
                if (i < d.length() && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
                while (i < d.length() && (Character.isDigit(d.charAt(i)) || d.charAt(i) == '.')) i++;
                n[taken++] = Float.parseFloat(d.substring(start, i));
            }
            boolean relative = Character.isLowerCase(command);
            switch (Character.toLowerCase(command)) {
                case 'm' -> {
                    x = relative ? x + n[0] : n[0];
                    y = relative ? y + n[1] : n[1];
                    path.moveTo(x, y);
                    startX = x;
                    startY = y;
                    command = relative ? 'l' : 'L';
                }
                case 'l' -> {
                    x = relative ? x + n[0] : n[0];
                    y = relative ? y + n[1] : n[1];
                    path.lineTo(x, y);
                }
                case 'h' -> {
                    x = relative ? x + n[0] : n[0];
                    path.lineTo(x, y);
                }
                case 'v' -> {
                    y = relative ? y + n[0] : n[0];
                    path.lineTo(x, y);
                }
                case 'c' -> {
                    float x1 = relative ? x + n[0] : n[0];
                    float y1 = relative ? y + n[1] : n[1];
                    float x2 = relative ? x + n[2] : n[2];
                    float y2 = relative ? y + n[3] : n[3];
                    float x3 = relative ? x + n[4] : n[4];
                    float y3 = relative ? y + n[5] : n[5];
                    path.curveTo(x1, y1, x2, y2, x3, y3);
                    x = x3;
                    y = y3;
                }
                default -> { }
            }
            if (Character.toLowerCase(command) == 'z') {
                path.closePath();
                x = startX;
                y = startY;
                i++;
            }
        }
        return path;
    }

    // ---- the files ------------------------------------------------------------

    /**
     * A Windows icon holding several sizes.
     *
     * Every entry is a PNG rather than a bitmap, which Windows has understood since Vista and
     * which keeps a 256-pixel icon from costing a quarter of a megabyte of uncompressed pixels.
     */
    private static void writeIco(Path target, int[] sizes) throws IOException {
        Files.createDirectories(target.getParent());
        byte[][] pngs = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(render(sizes[i], true), "png", bytes);
            pngs[i] = bytes.toByteArray();
        }

        try (OutputStream out = new FileOutputStream(target.toFile())) {
            DataOutputStream d = new DataOutputStream(out);
            writeShortLE(d, 0);               // reserved
            writeShortLE(d, 1);               // an icon, rather than a cursor
            writeShortLE(d, sizes.length);

            int offset = 6 + 16 * sizes.length;
            for (int i = 0; i < sizes.length; i++) {
                d.writeByte(sizes[i] >= 256 ? 0 : sizes[i]);   // 0 means 256
                d.writeByte(sizes[i] >= 256 ? 0 : sizes[i]);
                d.writeByte(0);               // colours in the palette: none, it is truecolour
                d.writeByte(0);               // reserved
                writeShortLE(d, 1);           // colour planes
                writeShortLE(d, 32);          // bits per pixel
                writeIntLE(d, pngs[i].length);
                writeIntLE(d, offset);
                offset += pngs[i].length;
            }
            for (byte[] png : pngs) d.write(png);
            d.flush();
        }
    }

    private static void writeShortLE(DataOutputStream d, int v) throws IOException {
        d.writeByte(v & 0xFF);
        d.writeByte((v >> 8) & 0xFF);
    }

    private static void writeIntLE(DataOutputStream d, int v) throws IOException {
        d.writeByte(v & 0xFF);
        d.writeByte((v >> 8) & 0xFF);
        d.writeByte((v >> 16) & 0xFF);
        d.writeByte((v >> 24) & 0xFF);
    }

    /**
     * The tablet's launcher icon, as vectors.
     *
     * No tile: Android masks the icon into whatever shape the launcher uses, and drawing our own
     * rounded square inside that would put a corner inside a corner.
     */
    private static void writeAndroidVector(Path target) throws IOException {
        String xml = String.join("\n", List.of(
            "<!-- Generated by brand/Brand.java. Edit the mark there, not here. -->",
            "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"",
            "    android:width=\"108dp\" android:height=\"108dp\"",
            "    android:viewportWidth=\"108\" android:viewportHeight=\"108\">",
            "    <path android:fillColor=\"" + hex(PAPER) + "\"",
            "        android:pathData=\"" + PAGE + "\" />",
            "    <path android:fillColor=\"" + hex(FOLD_SHADE) + "\"",
            "        android:pathData=\"" + FOLD + "\" />",
            "    <path android:strokeColor=\"" + hex(INK_COLOUR) + "\"",
            "        android:strokeWidth=\"" + INK_WIDTH + "\" android:fillColor=\"#00000000\"",
            "        android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\"",
            "        android:pathData=\"" + INK + "\" />",
            "</vector>",
            ""
        ));
        Files.writeString(target, xml, StandardCharsets.UTF_8);
    }

    private static void writeAndroidColour(Path target) throws IOException {
        String xml = String.join("\n", List.of(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<!-- Generated by brand/Brand.java. Edit the mark there, not here. -->",
            "<resources>",
            "    <color name=\"ic_launcher_background\">" + hex(BACKGROUND) + "</color>",
            "</resources>",
            ""
        ));
        Files.writeString(target, xml, StandardCharsets.UTF_8);
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private Brand() { }
}
