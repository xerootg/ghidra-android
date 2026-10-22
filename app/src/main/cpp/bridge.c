/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 *
 * libghidroid.so: shared between the two Java VMs in the :ghidra process.
 *
 * ART loads it first (System.loadLibrary from the app) and hands it the
 * Surface and input events. HotSpot, started from here with JLI_Launch, loads
 * the same library again (System.load from the toolkit's AndroidBackend) and
 * uses it to put frames on that Surface and to read the input queue. The two
 * sides meet only in the C globals below, so no Java object ever crosses
 * between the VMs.
 */
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TAG "ghidroid"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define INPUT_REDRAW 10

static JavaVM *art_vm;
static JavaVM *hs_vm;
static jclass art_native;
static jmethodID art_on_host_call;
static jmethodID art_on_host_query;
static jmethodID art_on_jvm_exit;

/* ---- surface ---------------------------------------------------------- */

/*
 * Surfaces by key. Key 0 is the whole screen (single-screen mode); other
 * keys are Ghidra window ids, each shown in its own Android window
 * (separate-windows mode, e.g. DeX).
 */
#define MAX_SURFACES 32
typedef struct {
    int key;
    ANativeWindow *window;
    int buf_w, buf_h;
} surface_t;

static pthread_mutex_t win_lock = PTHREAD_MUTEX_INITIALIZER;
static surface_t surfaces[MAX_SURFACES];

/* Caller holds win_lock. */
static surface_t *find_surface(int key, bool create) {
    surface_t *free_slot = NULL;
    for (int i = 0; i < MAX_SURFACES; i++) {
        if (surfaces[i].window != NULL && surfaces[i].key == key) {
            return &surfaces[i];
        }
        if (surfaces[i].window == NULL && free_slot == NULL) {
            free_slot = &surfaces[i];
        }
    }
    return create ? free_slot : NULL;
}

/* ---- input queue ------------------------------------------------------ */

#define QUEUE_LEN 8192
typedef struct {
    int v[5];
} event_t;

static pthread_mutex_t q_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t q_cond = PTHREAD_COND_INITIALIZER;
static event_t queue[QUEUE_LEN];
static unsigned q_head, q_tail;

static void push_event(int t, int a, int b, int c, int d) {
    pthread_mutex_lock(&q_lock);
    if (q_tail - q_head < QUEUE_LEN) {
        event_t *e = &queue[q_tail % QUEUE_LEN];
        e->v[0] = t;
        e->v[1] = a;
        e->v[2] = b;
        e->v[3] = c;
        e->v[4] = d;
        q_tail++;
        pthread_cond_signal(&q_cond);
    }
    pthread_mutex_unlock(&q_lock);
}

static JNIEnv *art_env(void) {
    JNIEnv *env = NULL;
    if ((*art_vm)->GetEnv(art_vm, (void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        (*art_vm)->AttachCurrentThreadAsDaemon(art_vm, &env, NULL);
    }
    return env;
}

/* ======================================================================
 * ART side: io.github.xerootg.ghidroid.Native
 * ==================================================================== */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    if (art_vm == NULL) {
        art_vm = vm;
        JNIEnv *env;
        (*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6);
        jclass c = (*env)->FindClass(env, "io/github/xerootg/ghidroid/Native");
        art_native = (*env)->NewGlobalRef(env, c);
        art_on_host_call = (*env)->GetStaticMethodID(env, c, "onHostCall", "(IILjava/lang/String;)V");
        art_on_host_query = (*env)->GetStaticMethodID(env, c, "onHostQuery", "(I)Ljava/lang/String;");
        art_on_jvm_exit = (*env)->GetStaticMethodID(env, c, "onJvmExit", "(I)V");
    } else if (vm != art_vm) {
        hs_vm = vm;
        LOGI("loaded into HotSpot");
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_io_github_xerootg_ghidroid_Native_setSurfaceFor(JNIEnv *env, jclass clazz, jint key, jobject surface) {
    (void) clazz;
    pthread_mutex_lock(&win_lock);
    surface_t *s = find_surface(key, false);
    if (s != NULL) {
        ANativeWindow_release(s->window);
        s->window = NULL;
    }
    if (surface != NULL) {
        s = find_surface(key, true);
        if (s != NULL) {
            s->key = key;
            s->window = ANativeWindow_fromSurface(env, surface);
            s->buf_w = s->buf_h = 0;
        }
    }
    pthread_mutex_unlock(&win_lock);
    if (surface != NULL) {
        push_event(INPUT_REDRAW, 0, 0, 0, 0);
    }
}

JNIEXPORT void JNICALL
Java_io_github_xerootg_ghidroid_Native_setSurface(JNIEnv *env, jclass clazz, jobject surface) {
    Java_io_github_xerootg_ghidroid_Native_setSurfaceFor(env, clazz, 0, surface);
}

JNIEXPORT void JNICALL
Java_io_github_xerootg_ghidroid_Native_pushEvent(JNIEnv *env, jclass clazz,
                                                 jint t, jint a, jint b, jint c, jint d) {
    (void) env;
    (void) clazz;
    push_event(t, a, b, c, d);
}

/* stdout/stderr of the JVM go to logcat and to a log file. */
static int log_fd = -1;

static void *log_pump(void *arg) {
    int fd = (int) (intptr_t) arg;
    char buf[4096];
    size_t len = 0;
    for (;;) {
        ssize_t n = read(fd, buf + len, sizeof(buf) - 1 - len);
        if (n <= 0) {
            break;
        }
        if (log_fd >= 0) {
            write(log_fd, buf + len, (size_t) n);
        }
        len += (size_t) n;
        char *start = buf;
        char *nl;
        while ((nl = memchr(start, '\n', len - (size_t) (start - buf))) != NULL) {
            *nl = 0;
            __android_log_write(ANDROID_LOG_INFO, "ghidra", start);
            start = nl + 1;
        }
        len -= (size_t) (start - buf);
        memmove(buf, start, len);
        if (len == sizeof(buf) - 1) {
            buf[len] = 0;
            __android_log_write(ANDROID_LOG_INFO, "ghidra", buf);
            len = 0;
        }
    }
    return NULL;
}

static void redirect_stdio(const char *log_path) {
    int p[2];
    if (pipe(p) != 0) {
        return;
    }
    if (log_path != NULL) {
        log_fd = open(log_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    }
    dup2(p[1], STDOUT_FILENO);
    dup2(p[1], STDERR_FILENO);
    close(p[1]);
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    pthread_t t;
    pthread_create(&t, NULL, log_pump, (void *) (intptr_t) p[0]);
    pthread_detach(t);
}

typedef int (*jli_launch_t)(int argc, char **argv, int jargc, const char **jargv,
                            int appclassc, const char **appclassv,
                            const char *fullversion, const char *dotversion,
                            const char *pname, const char *lname,
                            jboolean javaargs, jboolean cpwildcard, jboolean javaw, jint ergo);

#include <dirent.h>

static void preload_dir(const char *home, const char *sub) {
    char dir[4096];
    snprintf(dir, sizeof(dir), "%s/%s", home, sub);
    char *names[256];
    int n = 0;
    DIR *d = opendir(dir);
    if (d == NULL) {
        return;
    }
    struct dirent *e;
    while ((e = readdir(d)) != NULL && n < 256) {
        size_t len = strlen(e->d_name);
        if (len > 3 && strcmp(e->d_name + len - 3, ".so") == 0
                && strcmp(e->d_name, "libjsig.so") != 0) {
            names[n++] = strdup(e->d_name);
        }
    }
    closedir(d);
    bool progress = true;
    while (progress) {
        progress = false;
        for (int i = 0; i < n; i++) {
            if (names[i] == NULL) {
                continue;
            }
            char path[4096];
            snprintf(path, sizeof(path), "%s/%s", dir, names[i]);
            if (dlopen(path, RTLD_NOW | RTLD_GLOBAL) != NULL) {
                free(names[i]);
                names[i] = NULL;
                progress = true;
            }
        }
    }
    for (int i = 0; i < n; i++) {
        if (names[i] != NULL) {
            char path[4096];
            snprintf(path, sizeof(path), "%s/%s", dir, names[i]);
            dlopen(path, RTLD_NOW | RTLD_GLOBAL);
            LOGE("could not preload %s: %s", names[i], dlerror());
            free(names[i]);
        }
    }
}

typedef struct {
    int argc;
    char **argv;
    char *java_home;
} launch_t;

static void *jvm_thread(void *arg) {
    launch_t *l = arg;
    char path[4096];

    /*
     * The JRE's libraries name each other by soname (libnio needs libnet, ...)
     * but the app's linker namespace does not search the JRE directory.
     * Load them all up front; a library whose dependencies are not loaded
     * yet fails and is retried on the next pass. Later dlopen() calls by
     * path from the JVM return these handles.
     */
    preload_dir(l->java_home, "lib/server");
    preload_dir(l->java_home, "lib");

    /*
     * Leave the signal dispositions clean for HotSpot. ART's sigchain keeps
     * its own SIGSEGV handling in front of whatever HotSpot installs.
     */
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    for (int sig = SIGHUP; sig < NSIG; sig++) {
        if (sig == SIGKILL || sig == SIGSTOP) {
            continue;
        }
        sa.sa_handler = sig == SIGSEGV ? SIG_IGN : SIG_DFL;
        sigaction(sig, &sa, NULL);
    }

    snprintf(path, sizeof(path), "%s/lib/server/libjvm.so", l->java_home);
    if (dlopen(path, RTLD_NOW | RTLD_GLOBAL) == NULL) {
        LOGE("dlopen %s: %s", path, dlerror());
    }
    snprintf(path, sizeof(path), "%s/lib/libjli.so", l->java_home);
    void *jli = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (jli == NULL) {
        LOGE("dlopen %s: %s", path, dlerror());
        return NULL;
    }
    jli_launch_t launch = (jli_launch_t) dlsym(jli, "JLI_Launch");
    if (launch == NULL) {
        LOGE("JLI_Launch not found");
        return NULL;
    }
    LOGI("starting JVM with %d args", l->argc);
    int rc = launch(l->argc, l->argv, 0, NULL, 0, NULL, "21", "21",
                    l->argv[0], l->argv[0], JNI_FALSE, JNI_TRUE, JNI_FALSE, 0);
    LOGI("JLI_Launch returned %d", rc);
    JNIEnv *env = art_env();
    (*env)->CallStaticVoidMethod(env, art_native, art_on_jvm_exit, rc);
    exit(rc);
}

JNIEXPORT void JNICALL
Java_io_github_xerootg_ghidroid_Native_launchJvm(JNIEnv *env, jclass clazz, jobjectArray args,
                                                 jstring java_home, jstring log_path) {
    (void) clazz;
    launch_t *l = calloc(1, sizeof(*l));
    l->argc = (*env)->GetArrayLength(env, args);
    l->argv = calloc((size_t) l->argc + 1, sizeof(char *));
    for (int i = 0; i < l->argc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, args, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        l->argv[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    const char *h = (*env)->GetStringUTFChars(env, java_home, NULL);
    l->java_home = strdup(h);
    (*env)->ReleaseStringUTFChars(env, java_home, h);

    const char *lp = log_path != NULL ? (*env)->GetStringUTFChars(env, log_path, NULL) : NULL;
    redirect_stdio(lp);
    if (lp != NULL) {
        (*env)->ReleaseStringUTFChars(env, log_path, lp);
    }

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 16 * 1024 * 1024);
    pthread_t t;
    pthread_create(&t, &attr, jvm_thread, l);
    pthread_attr_destroy(&attr);
    pthread_detach(t);
}

/* ======================================================================
 * HotSpot side: io.github.xerootg.ghidroid.awt.AndroidBackend
 * ==================================================================== */

/* Copies the w x h rectangle at (x, y) of the screen (stride ints per row) to surface key. */
static void present(JNIEnv *env, jintArray pixels, int stride, int key, int x, int y, int w, int h) {
    if (w <= 0 || h <= 0) {
        return;
    }
    pthread_mutex_lock(&win_lock);
    surface_t *s = find_surface(key, false);
    if (s == NULL) {
        pthread_mutex_unlock(&win_lock);
        return;
    }
    if (w != s->buf_w || h != s->buf_h) {
        ANativeWindow_setBuffersGeometry(s->window, w, h, WINDOW_FORMAT_RGBX_8888);
        s->buf_w = w;
        s->buf_h = h;
    }
    ANativeWindow_Buffer b;
    if (ANativeWindow_lock(s->window, &b, NULL) == 0) {
        jsize len = (*env)->GetArrayLength(env, pixels);
        const uint32_t *src = (*env)->GetPrimitiveArrayCritical(env, pixels, NULL);
        if (src != NULL) {
            int rows = h < b.height ? h : b.height;
            int cols = w < b.width ? w : b.width;
            uint32_t *dst = b.bits;
            if (x == 0 && y == 0 && cols == stride && b.stride == stride
                    && (size_t) rows * (size_t) stride <= (size_t) len) {
                memcpy(dst, src, (size_t) rows * (size_t) stride * 4);
            } else {
                for (int r = 0; r < rows; r++) {
                    size_t off = (size_t) (y + r) * (size_t) stride + (size_t) x;
                    if (off + (size_t) cols > (size_t) len) {
                        break;
                    }
                    memcpy(dst + (size_t) r * (size_t) b.stride, src + off, (size_t) cols * 4);
                }
            }
            (*env)->ReleasePrimitiveArrayCritical(env, pixels, (void *) src, JNI_ABORT);
        }
        ANativeWindow_unlockAndPost(s->window);
    }
    pthread_mutex_unlock(&win_lock);
}

JNIEXPORT void JNICALL
Java_io_github_xerootg_ghidroid_awt_AndroidBackend_nativePresent(JNIEnv *env, jclass clazz,
                                                                 jintArray pixels, jint w, jint h) {
    (void) clazz;
    present(env, pixels, w, 0, 0, 0, w, h);
}

JNIEXPORT void JNICALL
Java_io_github_xerootg_ghidroid_awt_AndroidBackend_nativePresentRegion(JNIEnv *env, jclass clazz,
        jintArray pixels, jint stride, jint key, jint x, jint y, jint w, jint h) {
    (void) clazz;
    present(env, pixels, stride, key, x, y, w, h);
}

JNIEXPORT void JNICALL
Java_io_github_xerootg_ghidroid_awt_AndroidBackend_nativeNextEvent(JNIEnv *env, jclass clazz,
                                                                   jintArray out) {
    (void) clazz;
    event_t e;
    pthread_mutex_lock(&q_lock);
    while (q_head == q_tail) {
        pthread_cond_wait(&q_cond, &q_lock);
    }
    e = queue[q_head % QUEUE_LEN];
    q_head++;
    pthread_mutex_unlock(&q_lock);
    (*env)->SetIntArrayRegion(env, out, 0, 5, e.v);
}

JNIEXPORT void JNICALL
Java_io_github_xerootg_ghidroid_awt_AndroidBackend_nativeHostCall(JNIEnv *env, jclass clazz,
                                                                  jint what, jint arg, jstring text) {
    (void) clazz;
    JNIEnv *a = art_env();
    jstring s = NULL;
    if (text != NULL) {
        const jchar *c = (*env)->GetStringChars(env, text, NULL);
        s = (*a)->NewString(a, c, (*env)->GetStringLength(env, text));
        (*env)->ReleaseStringChars(env, text, c);
    }
    (*a)->CallStaticVoidMethod(a, art_native, art_on_host_call, what, arg, s);
    if ((*a)->ExceptionCheck(a)) {
        (*a)->ExceptionDescribe(a);
        (*a)->ExceptionClear(a);
    }
    if (s != NULL) {
        (*a)->DeleteLocalRef(a, s);
    }
}

JNIEXPORT jstring JNICALL
Java_io_github_xerootg_ghidroid_awt_AndroidBackend_nativeHostQuery(JNIEnv *env, jclass clazz,
                                                                   jint what) {
    (void) clazz;
    JNIEnv *a = art_env();
    jstring s = (*a)->CallStaticObjectMethod(a, art_native, art_on_host_query, what);
    if ((*a)->ExceptionCheck(a)) {
        (*a)->ExceptionDescribe(a);
        (*a)->ExceptionClear(a);
        return NULL;
    }
    if (s == NULL) {
        return NULL;
    }
    const jchar *c = (*a)->GetStringChars(a, s, NULL);
    jstring r = (*env)->NewString(env, c, (*a)->GetStringLength(a, s));
    (*a)->ReleaseStringChars(a, s, c);
    (*a)->DeleteLocalRef(a, s);
    return r;
}
