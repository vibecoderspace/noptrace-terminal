package com.vibecoderspace.noptrace.terminal;

import android.system.Os;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal tar.gz extractor for the Alpine minirootfs.
 *
 * <p>Android apps cannot create hard links reliably, so hard-link entries are
 * copied. Device nodes and FIFOs are skipped. Modes from the tar header are
 * applied with {@link Os#chmod}, which matters: without the execute bit the
 * guest's {@code /bin/busybox} will not run.</p>
 */
public final class TarExtractor {

    private static final int BLOCK = 512;
    private static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;

    private TarExtractor() {
    }

    public static void extract(File archive, File destination) throws IOException {
        if (!destination.isDirectory() && !destination.mkdirs() && !destination.isDirectory()) {
            throw new IOException("Cannot create " + destination);
        }
        String root = destination.getCanonicalPath();

        try (InputStream raw = new BufferedInputStream(new FileInputStream(archive));
             GZIPInputStream tar = new GZIPInputStream(raw)) {

            byte[] header = new byte[BLOCK];
            String longName = null;
            String longLink = null;
            Map<String, String> pax = new HashMap<>();

            while (true) {
                if (!readBlock(tar, header)) break;
                if (isZero(header)) break;

                long size = parseNumber(header, 124, 12);
                if (size < 0 || size > MAX_ENTRY_BYTES) {
                    throw new IOException("Invalid tar entry size: " + size);
                }
                int mode = (int) parseNumber(header, 100, 8);
                char type = (char) (header[156] & 0xff);
                String name = readString(header, 0, 100);
                String prefix = readString(header, 345, 155);
                String link = readString(header, 157, 100);

                if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                    byte[] data = readData(tar, size);
                    if (type == 'L') {
                        longName = stripNul(data);
                    } else if (type == 'K') {
                        longLink = stripNul(data);
                    } else {
                        Map<String, String> values = parsePax(data);
                        if (type == 'g') {
                            pax.putAll(values);
                        } else {
                            pax.putAll(values);
                        }
                    }
                    continue;
                }

                if (longName != null) {
                    name = longName;
                } else if (pax.containsKey("path")) {
                    name = pax.get("path");
                } else if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                if (longLink != null) {
                    link = longLink;
                } else if (pax.containsKey("linkpath")) {
                    link = pax.get("linkpath");
                }

                longName = null;
                longLink = null;
                pax.clear();

                name = stripLeading(name);
                if (name.isEmpty()) {
                    skipData(tar, size);
                    continue;
                }

                File out = resolve(destination, root, name);
                if (out == null) {
                    skipData(tar, size);
                    continue;
                }

                switch (type) {
                    case '5':
                        makeDirectories(out);
                        chmod(out, mode);
                        skipData(tar, size);
                        break;

                    case '2':
                        makeParent(out);
                        deleteRecursively(out);
                        try {
                            Os.symlink(link, out.getAbsolutePath());
                        } catch (Exception e) {
                            throw new IOException("symlink " + out + " -> " + link, e);
                        }
                        skipData(tar, size);
                        break;

                    case '1': {
                        makeParent(out);
                        deleteRecursively(out);
                        File target = resolve(destination, root, stripLeading(link));
                        if (target != null && target.isFile()) {
                            try {
                                Os.link(target.getAbsolutePath(), out.getAbsolutePath());
                            } catch (Exception e) {
                                copyFile(target, out);
                                chmod(out, mode);
                            }
                        }
                        skipData(tar, size);
                        break;
                    }

                    case '0':
                    case '\0':
                    case '7':
                        makeParent(out);
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            copyExactly(tar, fos, size);
                        }
                        chmod(out, mode);
                        break;

                    default:
                        skipData(tar, size);
                        break;
                }
            }
        }
    }

    // ---------------------------------------------------------------- entries

    private static File resolve(File destination, String root, String name) throws IOException {
        File file = new File(destination, name);
        String canonical = file.getCanonicalPath();
        if (canonical.equals(root) || canonical.startsWith(root + File.separator)) {
            return file;
        }
        return null;
    }

    private static void makeParent(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) makeDirectories(parent);
    }

    private static void makeDirectories(File dir) throws IOException {
        if (dir.isDirectory()) return;
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create " + dir);
        }
    }

    private static void chmod(File file, int mode) throws IOException {
        if (mode == 0) return;
        try {
            Os.chmod(file.getAbsolutePath(), mode & 0777);
        } catch (Exception e) {
            throw new IOException("chmod " + file, e);
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    // -------------------------------------------------------------- tar bytes

    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int offset = 0;
        while (offset < block.length) {
            int read = in.read(block, offset, block.length - offset);
            if (read == -1) {
                if (offset == 0) return false;
                throw new IOException("Truncated tar header");
            }
            offset += read;
        }
        return true;
    }

    private static boolean isZero(byte[] block) {
        for (byte value : block) {
            if (value != 0) return false;
        }
        return true;
    }

    private static String readString(byte[] block, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && block[end] != 0) end++;
        return new String(block, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long parseNumber(byte[] block, int offset, int length) {
        if ((block[offset] & 0x80) != 0) {
            // GNU base-256 for large values.
            long value = 0;
            for (int i = 1; i < length; i++) {
                value = (value << 8) | (block[offset + i] & 0xff);
            }
            return value;
        }
        long value = 0;
        int end = offset + length;
        int i = offset;
        while (i < end && (block[i] == ' ' || block[i] == 0)) i++;
        for (; i < end; i++) {
            byte b = block[i];
            if (b == 0 || b == ' ') break;
            if (b < '0' || b > '7') break;
            value = value * 8 + (b - '0');
        }
        return value;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size > Integer.MAX_VALUE) throw new IOException("Tar entry too large: " + size);
        byte[] data = new byte[(int) size];
        int offset = 0;
        while (offset < data.length) {
            int read = in.read(data, offset, data.length - offset);
            if (read == -1) throw new IOException("Truncated tar entry");
            offset += read;
        }
        skipPadding(in, size);
        return data;
    }

    private static void copyExactly(InputStream in, FileOutputStream out, long size) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) throw new IOException("Truncated tar entry");
            out.write(buffer, 0, read);
            remaining -= read;
        }
        skipPadding(in, size);
    }

    private static void skipData(InputStream in, long size) throws IOException {
        skipFully(in, size);
        skipPadding(in, size);
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long padding = (BLOCK - (size % BLOCK)) % BLOCK;
        skipFully(in, padding);
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) throw new IOException("Truncated tar padding");
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static String stripNul(byte[] data) {
        int end = 0;
        while (end < data.length && data[end] != 0) end++;
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    private static String stripLeading(String name) {
        String result = name;
        while (result.startsWith("./")) result = result.substring(2);
        while (result.startsWith("/")) result = result.substring(1);
        return result;
    }

    private static Map<String, String> parsePax(byte[] data) {
        Map<String, String> values = new HashMap<>();
        int position = 0;
        while (position < data.length) {
            int space = indexOf(data, (byte) ' ', position);
            if (space < 0) break;
            int length = 0;
            for (int i = position; i < space; i++) {
                byte b = data[i];
                if (b < '0' || b > '9') {
                    length = -1;
                    break;
                }
                length = length * 10 + (b - '0');
            }
            if (length <= 0 || position + length > data.length) break;

            int equals = indexOf(data, (byte) '=', space + 1);
            int newline = position + length - 1;
            if (equals > space && newline <= data.length && data[newline] == '\n') {
                String key = new String(data, space + 1, equals - space - 1, StandardCharsets.UTF_8);
                String value = new String(data, equals + 1, newline - equals - 1, StandardCharsets.UTF_8);
                values.put(key, value);
            }
            position += length;
        }
        return values;
    }

    private static int indexOf(byte[] data, byte needle, int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == needle) return i;
        }
        return -1;
    }
}
