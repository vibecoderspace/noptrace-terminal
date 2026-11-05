package com.vibecoderspace.noptrace.terminal;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.IOException;

/**
 * The engine will not keep private state in a directory anyone but its owner
 * can write to. This lives under {@code filesDir} (not {@code cacheDir}) so it
 * survives storage pressure, and is mode 0700 so the guest gets a usable
 * {@code /dev/shm} stand-in and a private {@code --shared-proc} registry.
 */
public final class TmpDir {

    private TmpDir() {
    }

    public static File ensure(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), "tmp");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create " + dir);
        }
        try {
            Os.chmod(dir.getAbsolutePath(), 0700);
        } catch (Exception e) {
            throw new IOException("Cannot chmod " + dir, e);
        }
        return dir;
    }
}
