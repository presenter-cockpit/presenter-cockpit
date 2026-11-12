/*
 * Pseudo-terminal for the local terminal (Android shell), used by it.presenter.app.tabs.LocalPty. Android
 * offers no PTY from Java and termlib only has the emulator: here the master side is opened, the shell is
 * started with the slave side as its controlling terminal (in its own session, so the hangup reaches all
 * its processes), and size, waiting for the end and signals are provided.
 */
#include <jni.h>
#include <android/fdsan.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static void throw_io(JNIEnv *env, const char *what) {
    char msg[256];
    snprintf(msg, sizeof msg, "%s: %s", what, strerror(errno));
    jclass c = (*env)->FindClass(env, "java/io/IOException");
    if (c != NULL) (*env)->ThrowNew(env, c, msg);
}

/* C copy of a Java string array, NULL-terminated (before the fork: no JNI or malloc after it). */
static char **c_strings(JNIEnv *env, jobjectArray array) {
    jsize n = array == NULL ? 0 : (*env)->GetArrayLength(env, array);
    char **out = calloc((size_t) n + 1, sizeof(char *));
    if (out == NULL) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *chars = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(chars != NULL ? chars : "");
        if (chars != NULL) (*env)->ReleaseStringUTFChars(env, s, chars);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static void free_strings(char **strings) {
    if (strings == NULL) return;
    for (char **p = strings; *p != NULL; p++) free(*p);
    free(strings);
}

/* Starts [path] with [argv] and [envp] in folder [cwd]: returns {master file descriptor, pid}. */
JNIEXPORT jintArray JNICALL
Java_it_presenter_app_tabs_LocalPty_nativeStart(JNIEnv *env, jclass cls, jstring path, jobjectArray argv,
                                                jobjectArray envp, jstring cwd, jint rows, jint cols) {
    (void) cls;
    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        throw_io(env, "posix_openpt");
        return NULL;
    }
    fcntl(master, F_SETFD, FD_CLOEXEC);
    char name[64];
    if (grantpt(master) != 0 || unlockpt(master) != 0 || ptsname_r(master, name, sizeof name) != 0) {
        throw_io(env, "ptsname");
        close(master);
        return NULL;
    }
    struct winsize size = {.ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols};
    ioctl(master, TIOCSWINSZ, &size);

    const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
    const char *c_cwd = (*env)->GetStringUTFChars(env, cwd, NULL);
    char *program = strdup(c_path);
    char *dir = strdup(c_cwd);
    (*env)->ReleaseStringUTFChars(env, path, c_path);
    (*env)->ReleaseStringUTFChars(env, cwd, c_cwd);
    char **args = c_strings(env, argv);
    char **vars = c_strings(env, envp);
    long max_fd = sysconf(_SC_OPEN_MAX);
    if (max_fd < 0 || max_fd > 65536) max_fd = 65536;

    pid_t pid = fork();
    if (pid < 0) {
        throw_io(env, "fork");
        close(master);
        free(program);
        free(dir);
        free_strings(args);
        free_strings(vars);
        return NULL;
    }
    if (pid == 0) {
        /* Child: signals as for a freshly started program (the virtual machine blocks some of them). */
        sigset_t none;
        sigemptyset(&none);
        sigprocmask(SIG_SETMASK, &none, NULL);
        for (int s = 1; s < NSIG; s++) signal(s, SIG_DFL);
        setsid();
        int slave = open(name, O_RDWR);
        if (slave < 0) _exit(127);
        ioctl(slave, TIOCSCTTY, 0);
        struct termios t;
        if (tcgetattr(slave, &t) == 0) {
            /* UTF-8 so that erasing multibyte characters works; no Ctrl+S/Ctrl+Q freezing the screen. */
            t.c_iflag |= IUTF8;
            t.c_iflag &= ~(IXON | IXOFF);
            tcsetattr(slave, TCSANOW, &t);
        }
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        /*
         * The app's file descriptors do not pass to the shell. Many belong to objects of the app: without
         * turning the check off, closing them here would raise the debugger signal (fatal in the child,
         * where signals are back to their defaults).
         */
        android_fdsan_set_error_level(ANDROID_FDSAN_ERROR_LEVEL_DISABLED);
        for (long fd = 3; fd < max_fd; fd++) close((int) fd);
        if (chdir(dir) != 0 && chdir("/") != 0) _exit(127);
        execve(program, args, vars);
        _exit(127);
    }
    free(program);
    free(dir);
    free_strings(args);
    free_strings(vars);
    jintArray result = (*env)->NewIntArray(env, 2);
    if (result != NULL) {
        jint values[2] = {master, pid};
        (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_it_presenter_app_tabs_LocalPty_nativeResize(JNIEnv *env, jclass cls, jint fd, jint rows, jint cols) {
    (void) env;
    (void) cls;
    struct winsize size = {.ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols};
    ioctl(fd, TIOCSWINSZ, &size);
}

/* Waits for process [pid] to end: its exit code, or -signal if a signal terminated it. */
JNIEXPORT jint JNICALL
Java_it_presenter_app_tabs_LocalPty_nativeWaitFor(JNIEnv *env, jclass cls, jint pid) {
    (void) env;
    (void) cls;
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}

/* Session of process [pid] (sixth field of /proc/pid/stat, after the name in parentheses), or -1. */
static int session_of(int pid) {
    char path[64], buf[512];
    snprintf(path, sizeof path, "/proc/%d/stat", pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = 0;
    char *p = strrchr(buf, ')');
    int ppid, pgrp, sid;
    char state;
    if (p == NULL || sscanf(p + 1, " %c %d %d %d", &state, &ppid, &pgrp, &sid) != 4) return -1;
    return sid;
}

/*
 * Signal [signal] to all processes of the session of shell [pid]: the programs it launched, background ones
 * too (with job control each has its own group, so the shell's group is not enough), and with [leader] the
 * shell itself (not after it was reaped: its pid may belong to another process by then). The session is the
 * one created at start (setsid).
 */
JNIEXPORT void JNICALL
Java_it_presenter_app_tabs_LocalPty_nativeSignal(JNIEnv *env, jclass cls, jint pid, jint signal, jboolean leader) {
    (void) env;
    (void) cls;
    DIR *proc = opendir("/proc");
    if (proc != NULL) {
        struct dirent *e;
        while ((e = readdir(proc)) != NULL) {
            int other = atoi(e->d_name);
            if (other > 0 && other != pid && session_of(other) == pid) kill(other, signal);
        }
        closedir(proc);
    }
    if (leader) kill(pid, signal);
}
