/*
 * exechook — makes a downloaded rootfs runnable on modern Android.
 *
 * Since Android 10, an app targeting API 29+ may not execve() a file inside its
 * own data directory: those files are labelled app_data_file, and untrusted_app
 * has no execute permission on that label. Measured on API 36 (see
 * docs/SELF_BOOTSTRAP_PLAN.md):
 *
 *     direct execve(<datadir>/prog)          -> EACCES
 *     execve("/system/bin/linker64", {prog}) -> runs
 *
 * Invoking the dynamic linker explicitly is not an execve of the target file,
 * so the SELinux rule never applies. That covers processes we launch ourselves,
 * but not their children: once a shell is running, its own execve("cc", ...)
 * goes straight to the kernel and is refused again.
 *
 * This library is LD_PRELOADed into the first process. It interposes the exec
 * family and rewrites calls that target the rootfs so they go through the
 * linker. LD_PRELOAD is inherited, so one hook covers the whole process tree.
 *
 * Only paths under $OPENVSCODE_ROOTFS are rewritten. System binaries are
 * executable already and are passed through untouched — rewriting those would
 * be pointless and would break anything that inspects its own argv[0].
 */

#define _GNU_SOURCE

#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <android/log.h>

#define TAG "exechook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#if defined(__LP64__)
static const char *const kLinker = "/system/bin/linker64";
#else
static const char *const kLinker = "/system/bin/linker";
#endif

extern char **environ;

typedef int (*execve_fn)(const char *, char *const[], char *const[]);

static execve_fn real_execve(void) {
    static execve_fn fn = NULL;
    if (fn == NULL) {
        fn = (execve_fn) dlsym(RTLD_NEXT, "execve");
    }
    return fn;
}

/*
 * True when `path` lives under the rootfs and therefore cannot be exec'd
 * directly. The rootfs location arrives in the environment rather than being
 * compiled in, so the same library works for any install path.
 */
static int needs_linker(const char *path) {
    if (path == NULL || path[0] == '\0') {
        return 0;
    }
    const char *root = getenv("OPENVSCODE_ROOTFS");
    if (root == NULL || root[0] == '\0') {
        return 0;
    }
    size_t len = strlen(root);
    if (strncmp(path, root, len) != 0) {
        return 0;
    }
    /* Never rewrite the linker itself; that would recurse forever. */
    if (strcmp(path, kLinker) == 0) {
        return 0;
    }
    return 1;
}

/*
 * Rewrites <path> <args...> into <linker> <path> <args...>.
 *
 * The linker shifts argv by one, so the program still sees its own path as
 * argv[0]. A caller that deliberately passed a *different* argv[0] loses it —
 * uncommon outside of login shells and busybox-style multi-call dispatch, and
 * noted as a known limitation rather than silently ignored.
 */
static int exec_via_linker(const char *path, char *const argv[], char *const envp[]) {
    int argc = 0;
    while (argv != NULL && argv[argc] != NULL) {
        argc++;
    }

    char **rewritten = (char **) calloc((size_t) argc + 3, sizeof(char *));
    if (rewritten == NULL) {
        errno = ENOMEM;
        return -1;
    }

    int n = 0;
    rewritten[n++] = (char *) kLinker;
    rewritten[n++] = (char *) path;
    for (int i = 1; i < argc; i++) {
        rewritten[n++] = argv[i];
    }
    rewritten[n] = NULL;

    if (argc > 0 && argv[0] != NULL && strcmp(argv[0], path) != 0) {
        LOGW("argv[0] %s replaced by %s (linker sets it)", argv[0], path);
    }

    execve_fn real = real_execve();
    if (real == NULL) {
        free(rewritten);
        errno = ENOSYS;
        return -1;
    }

    int rc = real(kLinker, rewritten, envp);
    /* Only reached when exec failed; on success this process is gone. */
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    if (needs_linker(path)) {
        return exec_via_linker(path, argv, envp);
    }
    execve_fn real = real_execve();
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real(path, argv, envp);
}

int execv(const char *path, char *const argv[]) {
    return execve(path, argv, environ);
}

/*
 * bionic resolves execvp() internally, and that internal call does not go
 * through the PLT — so interposing execve() alone would not catch it. The PATH
 * search is reimplemented here so the rewrite still happens.
 */
static int exec_path_search(const char *file, char *const argv[], char *const envp[]) {
    if (strchr(file, '/') != NULL) {
        return execve(file, argv, envp);
    }

    const char *path = getenv("PATH");
    if (path == NULL || path[0] == '\0') {
        path = "/system/bin:/system/xbin";
    }

    char *copy = strdup(path);
    if (copy == NULL) {
        errno = ENOMEM;
        return -1;
    }

    int saved_errno = ENOENT;
    char *state = NULL;
    for (char *dir = strtok_r(copy, ":", &state);
         dir != NULL;
         dir = strtok_r(NULL, ":", &state)) {
        char candidate[PATH_MAX];
        if (dir[0] == '\0') {
            dir = (char *) ".";
        }
        int written = snprintf(candidate, sizeof(candidate), "%s/%s", dir, file);
        if (written <= 0 || (size_t) written >= sizeof(candidate)) {
            continue;
        }
        if (access(candidate, X_OK) != 0 && access(candidate, F_OK) != 0) {
            continue;
        }
        execve(candidate, argv, envp);
        /* Keep looking on the errors execvp() is defined to skip. */
        if (errno != ENOENT && errno != EACCES && errno != ENOEXEC) {
            saved_errno = errno;
            break;
        }
        saved_errno = errno;
    }

    free(copy);
    errno = saved_errno;
    return -1;
}

int execvp(const char *file, char *const argv[]) {
    return exec_path_search(file, argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    return exec_path_search(file, argv, envp);
}

/* Reports that the library is loaded, which makes a failed preload obvious. */
__attribute__((constructor))
static void exechook_init(void) {
    const char *root = getenv("OPENVSCODE_ROOTFS");
    LOGI("loaded; rootfs=%s", root != NULL ? root : "(unset)");
}
