package com.vibecoderspace.noptrace.terminal;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the argv for an embedded noptrace session.
 *
 * <p>The argv[0] is the engine's own name, not a filesystem path: the engine is
 * linked into {@code libtermux.so} and entered in the fork()ed child. The
 * first positional argument is the rootfs, the second is the guest program.</p>
 */
public final class NoptraceCommand {

    private NoptraceCommand() {
    }

    public static String[] argv(File rootfs) {
        List<String> args = new ArrayList<>();

        args.add("noptrace");
        args.add("--link2symlink");
        args.add("--fake-id");
        args.add("0:0");
        args.add("--shared-proc");
        args.add("--work-dir");
        args.add("/root");

        // The engine synthesizes /proc, /dev, /dev/shm and /dev/fd itself.
        // Only /sys is bound, matching the upstream integration.
        args.add("-b");
        args.add("/sys:/sys");

        env(args, "HOME", "/root");
        env(args, "USER", "root");
        env(args, "LOGNAME", "root");
        env(args, "SHELL", "/bin/sh");
        env(args, "TERM", "xterm-256color");
        env(args, "COLORTERM", "truecolor");
        env(args, "LANG", "C.UTF-8");
        env(args, "PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");

        args.add(rootfs.getAbsolutePath());
        args.add("/bin/sh");
        args.add("-l");

        return args.toArray(new String[0]);
    }

    /**
     * The host environment given to the engine process. Guest variables are
     * passed as {@code -E} arguments above; noptrace does not forward this
     * array to the guest.
     */
    public static String[] hostEnvironment(File tmpDir) {
        return new String[] {
            "PATH=/system/bin",
            "TMPDIR=" + tmpDir.getAbsolutePath(),
        };
    }

    private static void env(List<String> args, String key, String value) {
        args.add("-E");
        args.add(key + "=" + value);
    }
}
