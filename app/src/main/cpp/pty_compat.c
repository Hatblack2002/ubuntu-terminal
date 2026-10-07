/*
 * pty_compat.c
 * --------------------------------------------------------------------
 * Thin compatibility layer over Android's openpty/forkpty.
 *
 * Android's bionic libc provides openpty() and forkpty() since API 23
 * via <pty.h>, but on some NDK releases the declarations are guarded
 * behind feature macros. This file normalises the API surface for the
 * rest of the native code.
 *
 * Per project spec (section 4): do NOT reinvent PTY — use the existing
 * libc implementation. This file is only a thin shim, not a reimplementation.
 */

#include "pty_compat.h"

#include <unistd.h>
#include <pty.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <fcntl.h>

int pty_compat_openpty(int *master_fd, int *slave_fd,
                       char *name, size_t name_size,
                       int cols, int rows) {
    struct winsize ws = {0};
    ws.ws_col = cols > 0 ? cols : 80;
    ws.ws_row = rows > 0 ? rows : 24;
    ws.ws_xpixel = ws.ws_col * 8;
    ws.ws_ypixel = ws.ws_row * 16;

    /* openpty() is provided by bionic's <pty.h>. */
    int rc = openpty(master_fd, slave_fd, NULL, NULL, &ws);
    if (rc != 0) {
        return -1;
    }

    /* Enable non-blocking reads on the master fd so the Kotlin side
     * can poll without blocking the UI thread. */
    int flags = fcntl(*master_fd, F_GETFL, 0);
    fcntl(*master_fd, F_SETFL, flags | O_NONBLOCK);

    if (name && name_size > 0) {
        char slave_name[64] = {0};
        if (ttyname_r(*slave_fd, slave_name, sizeof(slave_name)) == 0) {
            strncpy(name, slave_name, name_size - 1);
            name[name_size - 1] = '\0';
        }
    }

    return 0;
}

int pty_compat_set_size(int master_fd, int cols, int rows) {
    if (cols <= 0 || rows <= 0) return -1;
    struct winsize ws = {0};
    ws.ws_col = cols;
    ws.ws_row = rows;
    ws.ws_xpixel = cols * 8;
    ws.ws_ypixel = rows * 16;
    return ioctl(master_fd, TIOCSWINSZ, &ws);
}

int pty_compat_close(int fd) {
    if (fd < 0) return 0;
    return close(fd);
}
