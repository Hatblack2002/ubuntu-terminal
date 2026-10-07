/*
 * terminal_jni.cpp
 * --------------------------------------------------------------------
 * JNI entry points for com.ubuntuterm.terminal.NativeTerminal.
 *
 * Per project spec (section 7): NO HTTP, NO REST. The communication
 * between the Android UI and the Ubuntu process uses only:
 *   - fork() / execve()
 *   - PTY (openpty + ioctl TIOCSWINSZ)
 *   - read() / write() on the PTY master fd
 *   - kill() / waitpid()
 *
 * Per project spec (section 15): libterminal.so is ONLY the native
 * glue required to attach the UI to a real Ubuntu process. It is NOT
 * a reimplementation of Ubuntu or of any Linux command.
 */

#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <sys/stat.h>

#include "pty_compat.h"
#include "process_utils.h"

#define TAG "terminal_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ----------------------------------------------------------------- */
/* Internal session struct                                            */
/* ----------------------------------------------------------------- */
struct TerminalSession {
    pid_t  pid;
    int    master_fd;
};

/* ----------------------------------------------------------------- */
/* helpers                                                            */
/* ----------------------------------------------------------------- */
static jint throw_io_exception(JNIEnv *env, const char *msg) {
    jclass cls = env->FindClass("java/io/IOException");
    if (!cls) return 0;
    return env->ThrowNew(cls, msg);
}

static jint throw_illegal_state(JNIEnv *env, const char *msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (!cls) return 0;
    return env->ThrowNew(cls, msg);
}

static jint throw_illegal_arg(JNIEnv *env, const char *msg) {
    jclass cls = env->FindClass("java/lang/IllegalArgumentException");
    if (!cls) return 0;
    return env->ThrowNew(cls, msg);
}

/* Convert a Java String[] into a C char** array, NULL-terminated.
 * Each entry is malloc'd; free_jstring_array() releases it. */
static char **to_c_string_array(JNIEnv *env, jobjectArray jarr) {
    if (!jarr) return NULL;
    jsize n = env->GetArrayLength(jarr);
    char **arr = (char **) calloc((size_t) n + 1, sizeof(char *));
    if (!arr) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) env->GetObjectArrayElement(jarr, i);
        const char *cstr = env->GetStringUTFChars(s, nullptr);
        arr[i] = strdup(cstr ? cstr : "");
        env->ReleaseStringUTFChars(s, cstr);
        env->DeleteLocalRef(s);
    }
    arr[n] = nullptr;
    return arr;
}

static void free_c_string_array(char **arr) {
    if (!arr) return;
    for (size_t i = 0; arr[i]; i++) free(arr[i]);
    free(arr);
}

/* ----------------------------------------------------------------- */
/* Native methods                                                     */
/* ----------------------------------------------------------------- */

/*
 * Class:     com_ubuntuterm_terminal_NativeTerminal
 * Method:    nativeSpawn
 * Signature:
 *   (Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;II)
 *   Jcom/ubuntuterm/terminal/SessionHandle;
 *
 * Spawns a child process attached to a PTY.
 *
 * @param cwd          Working directory or null
 * @param argv         argv[0] = executable path, must not be null/empty
 * @param envp         envp = array of "KEY=VALUE" strings, or null to use defaults
 * @param cols, rows   Initial PTY size
 * @return SessionHandle holding pid + master fd
 */
extern "C"
JNIEXPORT jlong JNICALL
Java_com_ubuntuterm_terminal_NativeTerminal_nativeSpawn(
        JNIEnv *env, jclass,
        jstring jcwd,
        jobjectArray jargv,
        jobjectArray jenvp,
        jint cols, jint rows) {

    if (!jargv) {
        throw_illegal_arg(env, "argv must not be null");
        return 0;
    }
    jsize argc = env->GetArrayLength(jargv);
    if (argc == 0) {
        throw_illegal_arg(env, "argv must contain at least one element");
        return 0;
    }

    /* cwd */
    const char *cwd = nullptr;
    if (jcwd) cwd = env->GetStringUTFChars(jcwd, nullptr);

    /* argv → C array */
    char **argv = to_c_string_array(env, jargv);
    if (!argv) {
        if (jcwd) env->ReleaseStringUTFChars(jcwd, cwd);
        throw_io_exception(env, "out of memory building argv");
        return 0;
    }

    /* envp — caller passes "KEY=VALUE" strings */
    char **envp = to_c_string_array(env, jenvp);
    if (!envp) {
        envp = build_environment(nullptr, nullptr, nullptr, nullptr, nullptr);
    }

    int master_fd = -1;
    pid_t pid = spawn_with_pty(cwd, argv, envp, cols, rows, &master_fd);

    /* cleanup */
    if (jcwd) env->ReleaseStringUTFChars(jcwd, cwd);
    free_c_string_array(argv);
    free_c_string_array(envp);

    if (pid < 0) {
        throw_io_exception(env, strerror(errno));
        return 0;
    }

    /* Pack pid (lower 32) + master fd (upper 32) into a single jlong. */
    jlong handle = ((jlong) master_fd << 32) | (jlong) (pid & 0xFFFFFFFFL);
    LOGI("nativeSpawn handle=0x%llx pid=%d master_fd=%d",
         (unsigned long long) handle, (int) pid, master_fd);
    return handle;
}

/*
 * Class:     com_ubuntuterm_terminal_NativeTerminal
 * Method:    nativeWrite
 * Signature: (J[BII)I
 *
 * Writes len bytes from buf[off..off+len) to the PTY master.
 * Returns number of bytes written, or -1 on error.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_ubuntuterm_terminal_NativeTerminal_nativeWrite(
        JNIEnv *env, jclass,
        jlong handle,
        jbyteArray jbuf, jint off, jint len) {

    if (handle == 0) {
        throw_illegal_state(env, "session is not initialised");
        return -1;
    }
    if (!jbuf) {
        throw_illegal_arg(env, "buf is null");
        return -1;
    }
    jsize total = env->GetArrayLength(jbuf);
    if (off < 0 || len < 0 || (jsize)(off + len) > total) {
        throw_illegal_arg(env, "off/len out of bounds");
        return -1;
    }

    int master_fd = (int) ((handle >> 32) & 0xFFFFFFFFL);

    jbyte *bytes = env->GetByteArrayElements(jbuf, nullptr);
    if (!bytes) {
        throw_io_exception(env, "GetByteArrayElements failed");
        return -1;
    }

    ssize_t n = write(master_fd, bytes + off, (size_t) len);
    int err = errno;
    env->ReleaseByteArrayElements(jbuf, bytes, JNI_ABORT);

    if (n < 0) {
        if (err == EAGAIN || err == EWOULDBLOCK) return 0;
        return -1;
    }
    return (jint) n;
}

/*
 * Class:     com_ubuntuterm_terminal_NativeTerminal
 * Method:    nativeRead
 * Signature: (J[BII)I
 *
 * Reads up to len bytes into buf[off..off+len).
 * Returns number of bytes read, 0 if no data available (non-blocking),
 * -1 on error, -2 on EOF (child closed PTY).
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_ubuntuterm_terminal_NativeTerminal_nativeRead(
        JNIEnv *env, jclass,
        jlong handle,
        jbyteArray jbuf, jint off, jint len) {

    if (handle == 0) {
        throw_illegal_state(env, "session is not initialised");
        return -1;
    }
    if (!jbuf) {
        throw_illegal_arg(env, "buf is null");
        return -1;
    }
    jsize total = env->GetArrayLength(jbuf);
    if (off < 0 || len < 0 || (jsize)(off + len) > total) {
        throw_illegal_arg(env, "off/len out of bounds");
        return -1;
    }

    int master_fd = (int) ((handle >> 32) & 0xFFFFFFFFL);

    jbyte *bytes = env->GetByteArrayElements(jbuf, nullptr);
    if (!bytes) {
        throw_io_exception(env, "GetByteArrayElements failed");
        return -1;
    }

    ssize_t n = read(master_fd, bytes + off, (size_t) len);
    int err = errno;
    if (n > 0) {
        env->ReleaseByteArrayElements(jbuf, bytes, 0); /* commit */
        return (jint) n;
    }
    env->ReleaseByteArrayElements(jbuf, bytes, JNI_ABORT);

    if (n == 0) return -2;  /* EOF */
    if (err == EAGAIN || err == EWOULDBLOCK) return 0;
    if (err == EIO) return -2; /* child gone */
    return -1;
}

/*
 * Class:     com_ubuntuterm_terminal_NativeTerminal
 * Method:    nativeSetSize
 * Signature: (JII)V
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_ubuntuterm_terminal_NativeTerminal_nativeSetSize(
        JNIEnv *env, jclass,
        jlong handle,
        jint cols, jint rows) {

    if (handle == 0) {
        throw_illegal_state(env, "session is not initialised");
        return;
    }
    if (cols <= 0 || rows <= 0) {
        throw_illegal_arg(env, "cols and rows must be positive");
        return;
    }
    int master_fd = (int) ((handle >> 32) & 0xFFFFFFFFL);
    if (pty_compat_set_size(master_fd, cols, rows) != 0) {
        throw_io_exception(env, strerror(errno));
    }
}

/*
 * Class:     com_ubuntuterm_terminal_NativeTerminal
 * Method:    nativeSendSignal
 * Signature: (JI)V
 *
 * signo: 2=SIGINT, 3=SIGQUIT, 9=SIGKILL, 15=SIGTERM, 20=SIGTSTP, etc.
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_ubuntuterm_terminal_NativeTerminal_nativeSendSignal(
        JNIEnv *env, jclass,
        jlong handle,
        jint signo) {

    if (handle == 0) {
        throw_illegal_state(env, "session is not initialised");
        return;
    }
    pid_t pid = (pid_t) (handle & 0xFFFFFFFFL);
    if (send_signal(pid, signo) != 0) {
        throw_io_exception(env, strerror(errno));
    }
}

/*
 * Class:     com_ubuntuterm_terminal_NativeTerminal
 * Method:    nativeWaitExit
 * Signature: (JZ)I
 *
 * @param blocking  true  → block until child exits
 *                   false → poll
 * @return exit code 0..255, 128+sig, -2 if still running, -1 if error
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_ubuntuterm_terminal_NativeTerminal_nativeWaitExit(
        JNIEnv *env, jclass,
        jlong handle,
        jboolean blocking) {

    if (handle == 0) {
        throw_illegal_state(env, "session is not initialised");
        return -1;
    }
    pid_t pid = (pid_t) (handle & 0xFFFFFFFFL);
    return blocking ? wait_for_exit(pid) : wait_nohang(pid);
}

/*
 * Class:     com_ubuntuterm_terminal_NativeTerminal
 * Method:    nativeClose
 * Signature: (J)V
 *
 * Closes the PTY master fd. Does NOT kill the child — caller should
 * send SIGHUP/SIGKILL first if needed.
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_ubuntuterm_terminal_NativeTerminal_nativeClose(
        JNIEnv *env, jclass,
        jlong handle) {

    if (handle == 0) return;
    int master_fd = (int) ((handle >> 32) & 0xFFFFFFFFL);
    pty_compat_close(master_fd);
    LOGI("nativeClose master_fd=%d", master_fd);
}

/* ----------------------------------------------------------------- */
/* JNI_OnLoad — register nothing special, we use name-based linking  */
/* ----------------------------------------------------------------- */
extern "C"
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    LOGI("libterminal.so loaded");
    return JNI_VERSION_1_6;
}
