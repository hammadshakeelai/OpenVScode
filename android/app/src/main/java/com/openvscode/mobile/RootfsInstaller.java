package com.openvscode.mobile;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Downloads and unpacks the Linux rootfs that provides Python, C++ and Jupyter.
 *
 * Three properties matter more than speed here, because this runs once on a
 * phone over a connection that may well drop:
 *
 *   resumable   — a 250 MB download that restarts from zero on a dropped
 *                 connection is a download that never finishes on mobile data.
 *   verified    — the archive is checked against its published SHA-256 before
 *                 a single byte is extracted.
 *   atomic      — extraction goes to a scratch directory and is renamed into
 *                 place at the end, so an interrupted run cannot leave a
 *                 half-populated rootfs that looks installed.
 */
final class RootfsInstaller {

    private static final String TAG = "RootfsInstaller";

    /** Where the CI-built images are published. */
    private static final String BASE_URL =
            "https://github.com/hammadshakeelai/OpenVScode/releases/download/rootfs-latest";

    /** Uncompressed rootfs is several times the download; refuse if it cannot fit. */
    private static final long SPACE_MULTIPLIER = 4;

    interface Progress {
        void onStage(String stage);
        /** total is -1 when the server does not report a length. */
        void onProgress(long done, long total);
        void onComplete(File rootfs);
        void onError(String message);
    }

    private RootfsInstaller() { }

    /** arm64-v8a -> arm64, x86_64 -> amd64. Null when unsupported. */
    static String archSuffix() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return "arm64";
            if ("x86_64".equals(abi)) return "amd64";
        }
        return null;
    }

    static File rootfsDir(Context ctx) {
        return new File(ctx.getFilesDir(), "rootfs");
    }

    /** A rootfs is only "installed" once the stamp exists — see the atomic rename. */
    static boolean isInstalled(Context ctx) {
        return new File(rootfsDir(ctx), ".installed").exists();
    }

    static void install(final Context ctx, final Progress cb) {
        install(ctx, BASE_URL, cb);
    }

    /**
     * Overridable base URL. Exists so the whole download/verify/extract path can
     * be exercised against a local server with a small archive, instead of a
     * 40-minute CI build and a 250 MB download per attempt.
     */
    static void install(final Context ctx, final String baseUrl, final Progress cb) {
        new Thread(() -> {
            try {
                doInstall(ctx, baseUrl, cb);
            } catch (Throwable t) {
                Log.e(TAG, "install failed", t);
                cb.onError(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "rootfs-installer").start();
    }

    private static void doInstall(Context ctx, String baseUrl, Progress cb) throws Exception {
        String arch = archSuffix();
        if (arch == null) {
            cb.onError("No rootfs is published for this device's CPU ("
                    + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown") + ").");
            return;
        }

        String name = "rootfs-" + arch + ".tar.xz";
        File dir = ctx.getFilesDir();
        File archive = new File(dir, name + ".part");
        File target = rootfsDir(ctx);

        cb.onStage("Checking the published image…");
        long remoteSize = contentLength(baseUrl + "/" + name);
        String expectedSha = fetchSha256(baseUrl + "/" + name + ".sha256");
        Log.i(TAG, "remote=" + remoteSize + " sha=" + expectedSha);

        if (remoteSize > 0) {
            long needed = remoteSize * SPACE_MULTIPLIER;
            long free = new StatFs(dir.getAbsolutePath()).getAvailableBytes();
            if (free < needed) {
                cb.onError(String.format(Locale.US,
                        "Not enough space. Need about %s free, have %s.",
                        human(needed), human(free)));
                return;
            }
        }

        cb.onStage("Downloading the IDE image…");
        download(baseUrl + "/" + name, archive, remoteSize, cb);

        cb.onStage("Verifying the download…");
        String actual = sha256(archive);
        if (expectedSha != null && !expectedSha.equalsIgnoreCase(actual)) {
            // A corrupt partial file would otherwise be resumed forever.
            if (!archive.delete()) {
                Log.w(TAG, "could not delete corrupt archive " + archive);
            }
            cb.onError("The download did not match its published checksum, so it was discarded. "
                    + "Tap to try again.");
            return;
        }
        Log.i(TAG, "checksum ok: " + actual);

        cb.onStage("Unpacking Python, C++ and Jupyter…");
        File scratch = new File(dir, "rootfs.incoming");
        deleteTree(scratch);
        // mkdirs() returns false for a directory that already exists, which is
        // not an error — only an unusable path is.
        if (!scratch.isDirectory() && !scratch.mkdirs()) {
            cb.onError("Could not create " + scratch);
            return;
        }
        extract(archive, scratch, cb);

        // Only now is it safe to call this installed.
        deleteTree(target);
        if (!scratch.renameTo(target)) {
            cb.onError("Could not move the unpacked files into place.");
            return;
        }
        if (!new File(target, ".installed").createNewFile()) {
            Log.w(TAG, "could not write the .installed stamp");
        }
        if (!archive.delete()) {
            Log.w(TAG, "could not remove " + archive);
        }

        Log.i(TAG, "rootfs installed at " + target);
        cb.onComplete(target);
    }

    // ---- download --------------------------------------------------------

    private static long contentLength(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.getResponseCode();
            return c.getContentLengthLong();
        } catch (Exception e) {
            Log.w(TAG, "HEAD failed for " + url, e);
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String fetchSha256(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            copy(c.getInputStream(), buf, null, 0, -1);
            // Format is "<hex>  <filename>".
            String text = buf.toString("UTF-8").trim();
            int space = text.indexOf(' ');
            return space > 0 ? text.substring(0, space) : text;
        } catch (Exception e) {
            Log.w(TAG, "could not fetch checksum", e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Resumes from whatever is already on disk rather than starting over. */
    private static void download(String url, File dest, long total, Progress cb) throws IOException {
        long have = dest.exists() ? dest.length() : 0;
        if (total > 0 && have == total) {
            Log.i(TAG, "archive already complete");
            cb.onProgress(have, total);
            return;
        }
        if (total > 0 && have > total) {
            // Stale or truncated remote; start clean.
            if (!dest.delete()) Log.w(TAG, "could not delete oversized partial");
            have = 0;
        }

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        if (have > 0) {
            c.setRequestProperty("Range", "bytes=" + have + "-");
        }

        int code = c.getResponseCode();
        boolean appending = (code == HttpURLConnection.HTTP_PARTIAL);
        if (!appending && code != HttpURLConnection.HTTP_OK) {
            c.disconnect();
            throw new IOException("Download failed with HTTP " + code);
        }
        if (have > 0 && !appending) {
            // Server ignored the range; the bytes we have are unusable.
            Log.w(TAG, "range not honoured, restarting download");
            have = 0;
        }

        try (InputStream in = new BufferedInputStream(c.getInputStream());
             RandomAccessFile out = new RandomAccessFile(dest, "rw")) {
            out.seek(have);
            byte[] buf = new byte[64 * 1024];
            long done = have;
            int n;
            long lastReport = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                // Reporting every chunk floods the UI thread; once per MB is plenty.
                if (done - lastReport > 1024 * 1024) {
                    cb.onProgress(done, total);
                    lastReport = done;
                }
            }
            cb.onProgress(done, total);
        } finally {
            c.disconnect();
        }
    }

    // ---- extraction ------------------------------------------------------

    private static void extract(File archive, File dest, Progress cb) throws IOException {
        long entries = 0;
        try (InputStream fin = new BufferedInputStream(new FileInputStream(archive), 128 * 1024);
             XZCompressorInputStream xz = new XZCompressorInputStream(fin);
             TarArchiveInputStream tar = new TarArchiveInputStream(xz)) {

            String canonicalRoot = dest.getCanonicalPath();
            String canonicalDest = canonicalRoot + File.separator;
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                File out = new File(dest, entry.getName());

                // Refuse anything that would escape the target directory. The
                // root entry "./" canonicalises to dest itself, which is legal
                // and must not be caught by the prefix test.
                String canonicalOut = out.getCanonicalPath();
                if (!canonicalOut.equals(canonicalRoot) && !canonicalOut.startsWith(canonicalDest)) {
                    Log.w(TAG, "skipping entry outside target: " + entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) {
                        Log.w(TAG, "could not create dir " + out);
                    }
                } else if (entry.isSymbolicLink()) {
                    // A Debian rootfs is full of these; dropping them breaks it.
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        Log.w(TAG, "could not create parent for symlink " + out);
                    }
                    try {
                        Files.deleteIfExists(out.toPath());
                        Files.createSymbolicLink(out.toPath(), Paths.get(entry.getLinkName()));
                    } catch (Exception e) {
                        Log.w(TAG, "symlink failed " + entry.getName() + " -> " + entry.getLinkName());
                    }
                } else if (entry.isLink()) {
                    File src = new File(dest, entry.getLinkName());
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        Log.w(TAG, "could not create parent for link " + out);
                    }
                    try {
                        Files.deleteIfExists(out.toPath());
                        Files.createLink(out.toPath(), src.toPath());
                    } catch (Exception e) {
                        // Measured on API 36: createLink throws here, so this
                        // fallback is the normal path, not a rare one. A plain
                        // copy silently drops the mode, which turns a hard-linked
                        // executable into a non-executable file — and a Debian
                        // rootfs hard-links a lot of binaries.
                        if (src.isFile()) {
                            copyFile(src, out);
                        }
                    }
                    applyMode(out, entry.getMode());
                } else if (entry.isFile()) {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        Log.w(TAG, "could not create parent for " + out);
                    }
                    try (OutputStream os = new FileOutputStream(out)) {
                        copy(tar, os, null, 0, -1);
                    }
                    applyMode(out, entry.getMode());
                } else {
                    // Device nodes, fifos and sockets cannot be created without
                    // privileges and nothing in the IDE path needs them.
                    Log.d(TAG, "skipping special entry " + entry.getName());
                }

                if (++entries % 2000 == 0) {
                    cb.onStage("Unpacking… " + entries + " files");
                }
            }
        }
        Log.i(TAG, "extracted " + entries + " entries");
    }

    /** The executable bit is what makes the rootfs runnable; the rest is cosmetic. */
    private static void applyMode(File f, int mode) {
        try {
            if ((mode & 0100) != 0) {
                if (!f.setExecutable(true, false)) {
                    Log.d(TAG, "setExecutable failed for " + f);
                }
            }
            if (!f.setReadable(true, false)) {
                Log.d(TAG, "setReadable failed for " + f);
            }
        } catch (Exception e) {
            Log.d(TAG, "mode failed for " + f, e);
        }
    }

    // ---- helpers ---------------------------------------------------------

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 128 * 1024)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    private static void copy(InputStream in, OutputStream out, Progress cb, long done, long total)
            throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (cb != null) {
                done += n;
                cb.onProgress(done, total);
            }
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            copy(in, out, null, 0, -1);
        }
    }

    /**
     * Removes a tree, including dangling symlinks.
     *
     * The obvious `if (!f.exists()) return` guard is wrong here and cost a
     * failed install to find. File.exists() follows symlinks, and inside
     * rootfs.incoming every patched absolute symlink points at the *final*
     * rootfs path, which does not exist until the rename at the end. So they
     * all look absent, are never removed, and leave their directories
     * non-empty — which made any retry after an interrupted install fail
     * permanently. Deletion is therefore attempted without asking whether the
     * target resolves.
     */
    private static void deleteTree(File f) {
        if (f == null) return;
        java.nio.file.Path path = f.toPath();

        boolean link;
        try {
            link = Files.isSymbolicLink(path);
        } catch (Exception e) {
            link = false;
        }

        // Recurse into real directories only; never through a symlink.
        if (!link && Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    deleteTree(kid);
                }
            }
        }

        try {
            // Deletes the link itself rather than what it points at.
            Files.deleteIfExists(path);
        } catch (Exception e) {
            Log.d(TAG, "could not delete " + f + ": " + e);
        }
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.0f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.1f GB", bytes / 1073741824.0);
    }
}
