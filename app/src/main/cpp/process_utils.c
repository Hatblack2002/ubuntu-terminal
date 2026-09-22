/*
 * process_utils.c
 * --------------------------------------------------------------------
 * Helpers for spawning the PRoot + Ubuntu child process attached to
 * a PTY slave, plus signal/exit handling.
 *
 * Per project spec (section 7): NO HTTP, NO REST. The communication
 * between the Android UI and the Ubuntu process uses native IPC
 * primitives only: PTY, pipes, and process signals.
 *
 * Per separation principle: NO Termux. The child process is spawned
 * with the standard POSIX primitives fork()/execve(); no Termux
 * component is used at any layer.
 */

#include "process_utils.h"
#include "pty_compat.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <errno.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <android/log.h>

#define TAG "process_utils"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ----------------------------------------------------------------- */
/* spawn_with_pty                                                     */
/* ----------------------------------------------------------------- */
/*
 * Spawns a child process whose stdin/stdout/stderr are attached to
 * the slave end of a PTY. The child runs argv[0] with environment envp.
 *
 * Returns:
 *   child PID (>0) on success, sets *master_fd_out to the master fd
 *   -1 on error (errno preserved)
 *
 * The caller MUST close *master_fd_out when finished.
 */
pid_t spawn_with_pty(const char *cwd,
                     char *const argv[],
                     char *const envp[],
                     int cols, int rows,
                     int *master_fd_out)
{
    int master_fd = -1, slave_fd = -1;
    char slave_name[64] = {0};

    if (pty_compat_openpty(&master_fd, &slave_fd,
                           slave_name, sizeof(slave_name),
                           cols, rows) != 0) {
        LOGE("openpty failed: %s", strerror(errno));
        return -1;
    }

    /* Use vfork-style fork — we only call execve in the child. */
    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        pty_compat_close(slave_fd);
        pty_compat_close(master_fd);
        return -1;
    }

    if (pid == 0) {
        /* ====================== CHILD PROCESS ====================== */

        /* Become session leader so the PTY becomes our controlling tty */
        setsid();

        /* Reopen slave as our controlling terminal */
        int ctl = open(slave_name, O_RDWR);
        if (ctl >= 0) close(ctl);

        /* Dup slave → stdin/stdout/stderr */
        dup2(slave_fd, STDIN_FILENO);
        dup2(slave_fd, STDOUT_FILENO);
        dup2(slave_fd, STDERR_FILENO);
        if (slave_fd > STDERR_FILENO) close(slave_fd);
        if (master_fd > STDERR_FILENO) close(master_fd);

        /* cd into the requested working directory */
        if (cwd && *cwd) {
            if (chdir(cwd) != 0) {
                /* Not fatal — fall back to inherited cwd */
            }
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
    close(slave_fd);

    *master_fd_out = master_fd;
    LOGI("Spawned child pid=%d master_fd=%d slave=%s",
         (int) pid, master_fd, slave_name);
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
/*
 * Blocks until the child exits. Returns the exit status (0-255) or
 * -1 if killed by a signal (caller can inspect errno).
 *
 * Use WNOHANG externally for non-blocking polls — see wait_nohang().
 */
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
/*
 * Non-blocking wait.
 * Returns:
 *   0..255 if the child has exited normally
 *   128+sig if the child was killed by a signal
 *   -1 with errno==ECHILD if the child is gone (already reaped)
 *   -2 (special) if the child is still running
 */
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
/*
 * Builds a minimal environment for the child process. The PRoot
 * invocation will rewrite this inside the Ubuntu rootfs.
 *
 * Caller is responsible for freeing the returned array and each
 * string inside it. Use free_environment() for that.
 */
char **build_environment(const char *path_extra,
                         const char *home,
                         const char *user,
                         const char *term,
                         const char *lang) {
    /* 7 base vars + path_extra + NULL terminator */
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
