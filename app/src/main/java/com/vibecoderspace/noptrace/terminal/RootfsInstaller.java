package com.vibecoderspace.noptrace.terminal;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/**
 * Downloads and extracts the Alpine minirootfs on first launch.
 *
 * <p>The tarball is pinned by version, size and SHA-256. Extraction happens in
 * a staging directory and is published with a rename, so a killed download or
 * a failed extraction never leaves a half-installed rootfs behind.</p>
 */
public final class RootfsInstaller {

    public static final String VERSION = "3.24.4";
    public static final String URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/"
                    + "alpine-minirootfs-" + VERSION + "-aarch64.tar.gz";
    public static final long SIZE = 4_029_843L;
    public static final String SHA256 =
            "4b14c18f7e90a063b61462ea1462a5732c154bf6adad594974fe6487cd98a8de";

    private static final String MARKER = ".noptrace-rootfs";
    private static final String ARCHIVE = "alpine-minirootfs-" + VERSION + "-aarch64.tar.gz";

    public interface Progress {
        void onStage(String stage);

        void onProgress(long downloaded, long total);
    }

    private RootfsInstaller() {
    }

    public static File rootfsDir(Context context) {
        return new File(context.getFilesDir(), "rootfs");
    }

    public static boolean isInstalled(Context context) {
        File rootfs = rootfsDir(context);
        // bin/sh is an absolute symlink to /bin/busybox, which resolves
        // outside the rootfs on the host; test the real file instead.
        return new File(rootfs, MARKER).isFile() && new File(rootfs, "bin/busybox").isFile();
    }

    public static void install(Context context, Progress progress) throws Exception {
        File tmpDir = TmpDir.ensure(context);
        File archive = new File(tmpDir, ARCHIVE);

        progress.onStage("Downloading Alpine " + VERSION + "…");
        download(archive, progress);

        progress.onStage("Verifying download…");
        String digest = sha256(archive);
        if (!SHA256.equalsIgnoreCase(digest)) {
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
            throw new IOException("Checksum mismatch: expected " + SHA256 + ", got " + digest);
        }

        File staging = new File(context.getFilesDir(), "rootfs.staging");
        deleteRecursively(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IOException("Cannot create " + staging);
        }

        try {
            progress.onStage("Extracting root filesystem…");
            TarExtractor.extract(archive, staging);
            if (!new File(staging, "bin/busybox").isFile()) {
                throw new IOException("Extracted rootfs is missing /bin/busybox");
            }
            if (!new File(staging, MARKER).createNewFile() && !new File(staging, MARKER).isFile()) {
                throw new IOException("Cannot write install marker");
            }

            File rootfs = rootfsDir(context);
            deleteRecursively(rootfs);
            if (!staging.renameTo(rootfs)) {
                throw new IOException("Cannot publish rootfs to " + rootfs);
            }
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
        } catch (Exception e) {
            deleteRecursively(staging);
            throw e;
        }
    }

    private static void download(File target, Progress progress) throws IOException {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(URL).openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "noptrace-terminal/" + VERSION);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " from " + URL);
            }

            long total = connection.getContentLengthLong();
            if (total > 0 && total != SIZE) {
                throw new IOException("Unexpected archive size: " + total + " (expected " + SIZE + ")");
            }
            if (total <= 0) total = SIZE;

            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                long downloaded = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    progress.onProgress(downloaded, total);
                }
                if (downloaded != SIZE) {
                    throw new IOException("Truncated download: " + downloaded + " of " + SIZE);
                }
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.US, "%02x", value));
        }
        return result.toString();
    }

    private static void deleteRecursively(File file) {
        // lstat, not exists(): the rootfs contains absolute symlinks (bin/sh ->
        // /bin/busybox) that dangle on the host, and File.exists() follows
        // links, so it would both miss them and be able to leave the tree.
        android.system.StructStat stat;
        try {
            stat = android.system.Os.lstat(file.getAbsolutePath());
        } catch (Exception e) {
            return;
        }
        if (android.system.OsConstants.S_ISDIR(stat.st_mode)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
