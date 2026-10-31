package com.ravenemu.app.data.skins;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Skins made by the tests: no real skin, no real PDF. A fake "PDF" says its
 * page count and size in text, and the fake inspector reads it back. */
public final class DeltaSkinFixtures {
    public static final String IDENTIFIER = "com.ravenemu.tests.skin";
    /** The portrait mapping size; the fake PDF keeps its aspect. */
    public static final double WIDTH = 400, HEIGHT = 300;

    private DeltaSkinFixtures() {}

    public static final DeltaSkinArchive.PdfInspector INSPECTOR = pdf -> {
        String[] parts = new String(Files.readAllBytes(pdf.toPath()), StandardCharsets.US_ASCII).trim().split(" ");
        if (parts.length != 4 || !parts[0].equals("PDF")) throw new IOException("pas un PDF de test");
        return new DeltaSkinArchive.PdfInfo(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]));
    };

    public static byte[] pdf(int pages, int width, int height) {
        return ("PDF " + pages + " " + width + " " + height).getBytes(StandardCharsets.US_ASCII);
    }

    /** A GB/GBC skin: a D-pad, A (with a wider touch area), B, Start+Select, a
     * menu button, and an "x" RavenCore does not have. */
    public static String manifest(String name, String console) {
        return "{\"name\":\"" + name + "\",\"identifier\":\"" + IDENTIFIER + "\",\"gameTypeIdentifier\":\""
                + console + "\",\"debug\":false,\"representations\":{\"iphone\":{\"standard\":{\"portrait\":"
                + representation("skin.pdf") + "}}}}";
    }

    public static String manifest(String name) {
        return manifest(name, DeltaSkin.Console.GB_GBC.gameType);
    }

    static String representation(String pdf) {
        return "{\"assets\":{\"resizable\":\"" + pdf + "\"},\"mappingSize\":{\"width\":400,\"height\":300},"
                + "\"extendedEdges\":{\"top\":5,\"bottom\":5,\"left\":5,\"right\":5},\"items\":["
                + "{\"inputs\":{\"up\":\"up\",\"down\":\"down\",\"left\":\"left\",\"right\":\"right\"},"
                + "\"frame\":{\"x\":20,\"y\":60,\"width\":120,\"height\":120}},"
                + "{\"inputs\":[\"a\"],\"frame\":{\"x\":320,\"y\":80,\"width\":60,\"height\":60},"
                + "\"extendedEdges\":{\"left\":15}},"
                + "{\"inputs\":[\"b\"],\"frame\":{\"x\":240,\"y\":130,\"width\":60,\"height\":60}},"
                + "{\"inputs\":[\"menu\"],\"frame\":{\"x\":175,\"y\":10,\"width\":50,\"height\":30}},"
                + "{\"inputs\":[\"start\",\"select\"],\"frame\":{\"x\":175,\"y\":240,\"width\":50,\"height\":30}},"
                + "{\"inputs\":[\"x\"],\"frame\":{\"x\":360,\"y\":10,\"width\":20,\"height\":20}}]}";
    }

    /** The entries in this order, stored or deflated by the default. */
    public static File zip(File folder, String fileName, Map<String, byte[]> entries) throws IOException {
        File archive = new File(folder, fileName);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                if (entry.getValue() != null) zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return archive;
    }

    public static Map<String, byte[]> skin(String manifest) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("info.json", manifest.getBytes(StandardCharsets.UTF_8));
        entries.put("skin.pdf", pdf(1, 800, 600));
        return entries;
    }

    /** A checked, installed skin in this root. */
    public static DeltaSkinRepository.Installed install(File root, File work, String name) throws Exception {
        File archive = zip(work, name.replace(' ', '_') + ".deltaskin", skin(manifest(name)));
        DeltaSkinArchive.Prepared prepared = new DeltaSkinArchive(root, INSPECTOR).prepare(archive, archive.getName());
        DeltaSkinRepository.Result result = new DeltaSkinRepository(root).install(prepared, true);
        return result.skin;
    }

    /** The central directory's entry made a Unix symbolic link. */
    public static void markSymbolicLink(File archive, String name) throws IOException {
        byte[] bytes = Files.readAllBytes(archive.toPath());
        byte[] wanted = name.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + 46 + wanted.length <= bytes.length; i++) {
            if (bytes[i] != 0x50 || bytes[i + 1] != 0x4b || bytes[i + 2] != 1 || bytes[i + 3] != 2) continue;
            int nameLength = (bytes[i + 28] & 0xff) | (bytes[i + 29] & 0xff) << 8;
            if (nameLength != wanted.length) continue;
            boolean same = true;
            for (int j = 0; j < wanted.length; j++) same &= bytes[i + 46 + j] == wanted[j];
            if (!same) continue;
            bytes[i + 5] = 3;                                  // made by Unix
            int mode = 0xa1ff;                                 // lrwxrwxrwx
            bytes[i + 40] = (byte) mode;
            bytes[i + 41] = (byte) (mode >> 8);
        }
        Files.write(archive.toPath(), bytes);
    }

    public static byte[] zeros(int size) { return new byte[size]; }

    public static byte[] nestedZip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("x"));
            zip.write(1);
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
