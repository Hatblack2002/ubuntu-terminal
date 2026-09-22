/*
 * process_utils.h
 */
#ifndef UBUNTU_TERMINAL_PROCESS_UTILS_H
#define UBUNTU_TERMINAL_PROCESS_UTILS_H

#include <sys/types.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Forks and execs argv[0] in the child, with stdin/stdout/stderr
 * attached to a freshly created PTY.
 *
 * @param cwd             Working directory (may be NULL → inherited)
 * @param argv            NULL-terminated argv array; argv[0] = binary path
 * @param envp            NULL-terminated envp array (may be NULL → use build_environment)
 * @param cols, rows      Initial PTY dimensions
 * @param master_fd_out   Out: master fd for parent to read/write
 * @return                child pid on success, -1 on error
 */
pid_t spawn_with_pty(const char *cwd,
                     char *const argv[],
                     char *const envp[],
                     int cols, int rows,
                     int *master_fd_out);

int send_signal(pid_t pid, int signo);

int wait_for_exit(pid_t pid);

/**
 * Returns:
 *   0..255  child exited
 *   128+sig child was killed by signal
 *   -1      error / no such child
 *   -2      child still running
 */
int wait_nohang(pid_t pid);

char **build_environment(const char *path_extra,
                         const char *home,
                         const char *user,
                         const char *term,
                         const char *lang);

void free_environment(char **env);

#ifdef __cplusplus
}
#endif

#endif /* UBUNTU_TERMINAL_PROCESS_UTILS_H */
