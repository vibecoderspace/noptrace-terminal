#include <dirent.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TERMUX_UNUSED(x) x __attribute__((__unused__))
#ifdef __APPLE__
# define LACKS_PTSNAME_R
#endif

extern char** environ;

#ifdef HAVE_NOPTRACE
/* Embedded noptrace entry (noptrace_embed.c -> native/noptrace). Returns the
 * guest exit code. */
extern int noptrace_main(int argc, char** argv);
#endif

static int throw_runtime_exception(JNIEnv* env, char const* message)
{
    jclass exClass = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, exClass, message);
    return -1;
}

static char** to_cstr_array(JNIEnv* env, jobjectArray array)
{
    jsize size = array ? (*env)->GetArrayLength(env, array) : 0;
    char** result = (char**) calloc((size_t) size + 1, sizeof(char*));
    if (!result) return NULL;

    for (jsize i = 0; i < size; ++i) {
        jstring string = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        if (!string) continue;
        char const* utf8 = (*env)->GetStringUTFChars(env, string, NULL);
        if (!utf8) {
            free(result);
            return NULL;
        }
        result[i] = strdup(utf8);
        (*env)->ReleaseStringUTFChars(env, string, utf8);
        (*env)->DeleteLocalRef(env, string);
    }

    return result;
}

static void free_cstr_array(char** array)
{
    if (!array) return;
    for (char** item = array; *item; ++item) free(*item);
    free(array);
}

static int create_subprocess(JNIEnv* env,
        char const* cmd,
        char const* cwd,
        char* const argv[],
        char** envp,
        int* pProcessId,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height,
        int use_engine)
{
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

#ifdef LACKS_PTSNAME_R
    char* devname;
#else
    char devname[64];
#endif
    if (grantpt(ptm) || unlockpt(ptm) ||
#ifdef LACKS_PTSNAME_R
            (devname = ptsname(ptm)) == NULL
#else
            ptsname_r(ptm, devname, sizeof(devname))
#endif
       ) {
        return throw_runtime_exception(env, "Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx");
    }

    // Enable UTF-8 mode and disable flow control to prevent Ctrl+S from locking up the display.
    struct termios tios;
    tcgetattr(ptm, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(ptm, TCSANOW, &tios);

    /** Set initial winsize. */
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns, .ws_xpixel = (unsigned short) (columns * cell_width), .ws_ypixel = (unsigned short) (rows * cell_height)};
    ioctl(ptm, TIOCSWINSZ, &sz);

    pid_t pid = fork();
    if (pid < 0) {
        return throw_runtime_exception(env, "Fork failed");
    } else if (pid > 0) {
        *pProcessId = (int) pid;
        return ptm;
    } else {
        // Clear signals which the Android java process may have blocked:
        sigset_t signals_to_unblock;
        sigfillset(&signals_to_unblock);
        sigprocmask(SIG_UNBLOCK, &signals_to_unblock, 0);

        // The in-process engine keeps the fork()ed child's inherited ART
        // signal handlers, which would try to build a Java crash report in a
        // process that no longer runs ART. execve() resets handled
        // dispositions, but not ignored ones, so reset both explicitly.
        sigset_t empty_mask;
        sigemptyset(&empty_mask);
        sigprocmask(SIG_SETMASK, &empty_mask, NULL);
        for (int s = 1; s < NSIG; s++) signal(s, SIG_DFL);

        close(ptm);
        setsid();

        int pts = open(devname, O_RDWR);
        if (pts < 0) _exit(127);

        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);
        if (pts > 2) close(pts);

        DIR* self_dir = opendir("/proc/self/fd");
        if (self_dir != NULL) {
            int self_dir_fd = dirfd(self_dir);
            struct dirent* entry;
            while ((entry = readdir(self_dir)) != NULL) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != self_dir_fd) close(fd);
            }
            closedir(self_dir);
        }

        if (chdir(cwd) != 0) {
            char* error_message;
            // No need to free asprintf()-allocated memory since doing execvp() or exit() below.
            if (asprintf(&error_message, "chdir(\"%s\")", cwd) == -1) error_message = "chdir()";
            perror(error_message);
            fflush(stderr);
        }

        if (use_engine) {
#ifdef HAVE_NOPTRACE
            static char* empty_env[] = { NULL };
            environ = envp ? envp : empty_env;

            int argc = 0;
            while (argv[argc] != NULL) argc++;
            if (argc == 0) _exit(127);

            // Never returns: noptrace runs the guest in-process.
            _exit(noptrace_main(argc, argv));
#else
            _exit(127);
#endif
        }

        clearenv();
        if (envp) for (; *envp; ++envp) putenv(*envp);

        execvp(cmd, argv);
        // Show terminal output about failing exec() call:
        char* error_message;
        if (asprintf(&error_message, "exec(\"%s\")", cmd) == -1) error_message = "exec()";
        perror(error_message);
        _exit(1);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jstring cmd,
        jstring cwd,
        jobjectArray args,
        jobjectArray envVars,
        jintArray processIdArray,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height)
{
    char** argv = to_cstr_array(env, args);
    char** envp = to_cstr_array(env, envVars);
    if (!argv || !envp) {
        free_cstr_array(argv);
        free_cstr_array(envp);
        return throw_runtime_exception(env, "Couldn't allocate argv/envp array");
    }

    int procId = 0;
    char const* cmd_cwd = (*env)->GetStringUTFChars(env, cwd, NULL);
    char const* cmd_utf8 = (*env)->GetStringUTFChars(env, cmd, NULL);
    int ptm = create_subprocess(env, cmd_utf8, cmd_cwd, argv, envp, &procId,
            rows, columns, cell_width, cell_height, 0);
    if (cmd_cwd) (*env)->ReleaseStringUTFChars(env, cwd, cmd_cwd);
    if (cmd_utf8) (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);

    free_cstr_array(argv);
    free_cstr_array(envp);

    if (ptm < 0) return ptm;

    int* pProcId = (int*) (*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);
    if (!pProcId) return throw_runtime_exception(env, "JNI call GetPrimitiveArrayCritical(processIdArray, &isCopy) failed");

    *pProcId = procId;
    (*env)->ReleasePrimitiveArrayCritical(env, processIdArray, pProcId, 0);

    return ptm;
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocessEngine(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jstring cwd,
        jobjectArray args,
        jobjectArray envVars,
        jintArray processIdArray,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height)
{
    char** argv = to_cstr_array(env, args);
    char** envp = to_cstr_array(env, envVars);
    if (!argv || !envp) {
        free_cstr_array(argv);
        free_cstr_array(envp);
        return throw_runtime_exception(env, "Couldn't allocate argv/envp array");
    }

    int procId = 0;
    char const* cmd_cwd = (*env)->GetStringUTFChars(env, cwd, NULL);
    int ptm = create_subprocess(env, NULL, cmd_cwd, argv, envp, &procId,
            rows, columns, cell_width, cell_height, 1);
    if (cmd_cwd) (*env)->ReleaseStringUTFChars(env, cwd, cmd_cwd);

    free_cstr_array(argv);
    free_cstr_array(envp);

    if (ptm < 0) return ptm;

    int* pProcId = (int*) (*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);
    if (!pProcId) return throw_runtime_exception(env, "JNI call GetPrimitiveArrayCritical(processIdArray, &isCopy) failed");

    *pProcId = procId;
    (*env)->ReleasePrimitiveArrayCritical(env, processIdArray, pProcId, 0);

    return ptm;
}

JNIEXPORT jboolean JNICALL Java_com_termux_terminal_JNI_hasNoptrace(
        JNIEnv* TERMUX_UNUSED(env),
        jclass TERMUX_UNUSED(clazz))
{
#ifdef HAVE_NOPTRACE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyWindowSize(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd, jint rows, jint cols, jint cell_width, jint cell_height)
{
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols, .ws_xpixel = (unsigned short) (cols * cell_width), .ws_ypixel = (unsigned short) (rows * cell_height) };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyUTF8Mode(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd)
{
    struct termios tios;
    tcgetattr(fd, &tios);
    if ((tios.c_iflag & IUTF8) == 0) {
        tios.c_iflag |= IUTF8;
        tcsetattr(fd, TCSANOW, &tios);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_waitFor(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint pid)
{
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    } else {
        // Should never happen - waitpid(2) says "One of the first three macros will evaluate to a non-zero (true) value".
        return 0;
    }
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_close(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fileDescriptor)
{
    close(fileDescriptor);
}
