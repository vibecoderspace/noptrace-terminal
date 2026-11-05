/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Embedded entry for noptrace, linked into libtermux.so and called in the
 * fork()ed child (never in the app process itself).
 *
 * The standalone binary enters through _start (src/rt/start.S), which hands
 * cng_bootstrap the kernel-built initial stack to carve argc/argv/envp/auxv
 * out of. Linked into libtermux.so there is no such stack, so this shim
 * reconstructs the same contract: argv comes from Java, envp is the process
 * environ, and the auxv is read back from /proc/self/auxv — the kernel's own
 * record of it, which the loader consults for AT_RANDOM and AT_UID..AT_EGID
 * when synthesizing the guest's auxv.
 *
 * Bionic is safe here: this runs before any guest code exists, on the child's
 * own TLS. Only past cng_main() does the no-libc rule apply.
 */
#include <fcntl.h>
#include <unistd.h>

int cng_main(int argc, char **argv, char **envp, unsigned long *auxv);
extern unsigned long cng_page_size;
extern char **environ;

int noptrace_main(int argc, char **argv) {
    /* Static: zero-initialized, and read() is capped short of the end so a
     * terminating AT_NULL pair always remains. */
    static unsigned long auxv[130];

    int fd = open("/proc/self/auxv", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        ssize_t n = read(fd, auxv, sizeof(auxv) - 2 * sizeof(auxv[0]));
        close(fd);
        if (n > 0) {
            size_t words = (size_t)n / sizeof(auxv[0]);
            auxv[words & ~(size_t)1] = 0;
            auxv[(words & ~(size_t)1) + 1] = 0;
        }
    }

    for (unsigned long *a = auxv; a[0] != 0; a += 2) {
        if (a[0] == 6 /* AT_PAGESZ */ && a[1])
            cng_page_size = a[1];
    }

    return cng_main(argc, argv, environ, auxv);
}
