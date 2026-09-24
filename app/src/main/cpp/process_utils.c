/*
 * process_utils.c
 * --------------------------------------------------------------------
 * v0.1.16: REWRITTEN to use forkpty() — the same function that
 * Termux, tmux, and every Unix terminal emulator uses.
 *
 * forkpty() does ALL of this in one call:
 *   - posix_openpt + grantpt + unlockpt + ptsname + open(slave)
 *   - fork()
 *   - child: setsid(), TIOCSCTTY, dup2(slave, 0/1/2), close(slave), close(master)
 *   - parent: close(slave), return master_fd + pid
 *
 * No manual setsid/dup2/TIOCSCTTY needed. This eliminates the entire
 * class of bugs where a step was missing or out of order.
 *
 * Per project spec (section 7): NO HTTP, NO REST. PTY + fork + execve only.
 */

#include "process_utils.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <errno.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <pty.h>
#include <android/log.h>

#define TAG "process_utils"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ----------------------------------------------------------------- */
/* spawn_with_pty                                                     */
/* ----------------------------------------------------------------- */
/*
 * Spawns a child process attached to a PTY using forkpty().
 *
 * Returns:
 *   child PID (>0) on success, sets *master_fd_out to the master fd
 *   -1 on error (errno preserved)
 */
pid_t spawn_with_pty(const char *cwd,
                     char *const argv[],
                     char *const envp[],
                     int cols, int rows,
                     int *master_fd_out)
{
    int master_fd = -1;
    struct winsize ws = {0};
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    ws.ws_xpixel = ws.ws_col * 8;
    ws.ws_ypixel = ws.ws_row * 16;

    /*
     * forkpty() does EVERYTHING:
     *   - Creates PTY pair (master + slave)
     *   - fork()
     *   - In child: setsid(), TIOCSCTTY, dup2(slave, 0/1/2), close(slave), close(master)
     *   - In parent: close(slave), sets master_fd
     *
     * Returns: pid in parent, 0 in child, -1 on error.
     */
    pid_t pid = forkpty(&master_fd, NULL, NULL, &ws);

    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        return -1;
    }

    if (pid == 0) {
        /* ====================== CHILD PROCESS ====================== */
        /* forkpty already did: setsid, TIOCSCTTY, dup2(slave, 0/1/2),
         * close(slave), close(master). We only need to exec. */

        /* Set terminal to raw mode */
        struct termios t;
        if (tcgetattr(0, &t) == 0) {
            cfmakeraw(&t);
            tcsetattr(0, TCSANOW, &t);
        }

        /* Close all other inherited fds (best-effort) */
        int max_fd = (int) sysconf(_SC_OPEN_MAX);
        if (max_fd < 256) max_fd = 256;
        for (int fd = STDERR_FILENO + 1; fd < max_fd; fd++) {
            close(fd);
        }

        /* Reset signal handlers */
        signal(SIGPIPE, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);

        /* DEBUG PROBE — confirm slave is connected to stdout */
        const char *probe = "PTY_SLAVE_OK\n";
        write(1, probe, 13);

        /* cd into the requested working directory */
        if (cwd && *cwd) {
            if (chdir(cwd) != 0) {
                /* Not fatal — fall back to inherited cwd */
            }
        }

        /* Execute the requested binary (proot → /bin/bash inside Ubuntu) */
        execve(argv[0], argv, envp);

        /* If we reach here, execve failed. */
        const char *msg = "execve failed: ";
        write(STDERR_FILENO, msg, strlen(msg));
        const char *err = strerror(errno);
        write(STDERR_FILENO, err, strlen(err));
        write(STDERR_FILENO, "\n", 1);
        _exit(127);
    }

    /* ====================== PARENT PROCESS ====================== */
    /* forkpty already closed the slave fd in the parent. */

    *master_fd_out = master_fd;
    LOGI("forkpty OK: pid=%d master_fd=%d", (int) pid, master_fd);
    return pid;
}

/* ----------------------------------------------------------------- */
/* send_signal                                                        */
/* ----------------------------------------------------------------- */
int send_signal(pid_t pid, int signo) {
    if (pid <= 0) return -1;
    if (kill(pid, signo) != 0) {
        LOGE("kill(%d, %d) failed: %s", (int) pid, signo, strerror(errno));
        return -1;
    }
    return 0;
}

/* ----------------------------------------------------------------- */
/* wait_for_exit                                                      */
/* ----------------------------------------------------------------- */
int wait_for_exit(pid_t pid) {
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) {
        return -1;
    }
    if (WIFEXITED(status))   return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

/* ----------------------------------------------------------------- */
/* wait_nohang                                                        */
/* ----------------------------------------------------------------- */
int wait_nohang(pid_t pid) {
    int status = 0;
    pid_t r = waitpid(pid, &status, WNOHANG);
    if (r == 0)   return -2;        /* still running */
    if (r < 0)    return -1;        /* error */
    if (WIFEXITED(status))   return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

/* ----------------------------------------------------------------- */
/* build_environment                                                  */
/* ----------------------------------------------------------------- */
char **build_environment(const char *path_extra,
                         const char *home,
                         const char *user,
                         const char *term,
                         const char *lang) {
    size_t count = 8;
    char **env = (char **) calloc(count, sizeof(char *));
    if (!env) return NULL;

    size_t i = 0;
    #define PUT(k, v) do { \
        if (asprintf(&env[i++], "%s=%s", (k), (v)) < 0) goto fail; \
    } while (0)

    const char *path = (path_extra && *path_extra)
        ? path_extra
        : "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    const char *h = (home && *home) ? home : "/root";
    const char *u = (user && *user) ? user : "root";
    const char *t = (term && *term) ? term : "xterm-256color";
    const char *l = (lang && *lang) ? lang : "C.UTF-8";

    PUT("PATH", path);
    PUT("HOME", h);
    PUT("USER", u);
    PUT("LOGNAME", u);
    PUT("TERM", t);
    PUT("LANG", l);
    PUT("LC_ALL", l);
    PUT("SHELL", "/bin/bash");

    #undef PUT
    return env;

fail:
    for (size_t j = 0; j < i; j++) free(env[j]);
    free(env);
    return NULL;
}

void free_environment(char **env) {
    if (!env) return;
    for (size_t i = 0; env[i] != NULL; i++) {
        free(env[i]);
    }
    free(env);
}
