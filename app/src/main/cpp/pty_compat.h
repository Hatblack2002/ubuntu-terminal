/*
 * pty_compat.h
 */
#ifndef UBUNTU_TERMINAL_PTY_COMPAT_H
#define UBUNTU_TERMINAL_PTY_COMPAT_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Opens a new PTY pair (master + slave).
 *
 * @param master_fd  Out: master fd (used by the UI side to read/write)
 * @param slave_fd   Out: slave fd  (used by the child process as stdin/stdout/stderr)
 * @param name       Out: optional buffer receiving the slave path (e.g. /dev/pts/3)
 * @param name_size  Size of the name buffer
 * @param cols       Initial terminal width  (0 → default 80)
 * @param rows       Initial terminal height (0 → default 24)
 * @return 0 on success, -1 on error (errno set by openpty)
 */
int pty_compat_openpty(int *master_fd, int *slave_fd,
                       char *name, size_t name_size,
                       int cols, int rows);

/**
 * Resizes the PTY window.
 * Safe to call from any thread once the PTY is open.
 */
int pty_compat_set_size(int master_fd, int cols, int rows);

/**
 * Closes a file descriptor. Safe to call with -1 (returns 0).
 */
int pty_compat_close(int fd);

#ifdef __cplusplus
}
#endif

#endif /* UBUNTU_TERMINAL_PTY_COMPAT_H */
