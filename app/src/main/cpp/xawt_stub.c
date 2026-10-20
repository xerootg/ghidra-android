/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 *
 * Stand-in for libawt_xawt.so. libawt loads it (and registers it with
 * System.load) whenever AWT is not headless. The toolkit here has no X11, so
 * the natives the X11 toolkit would provide are no-ops. Everything else that
 * normally lives in xawt or headless (fonts, Java2D) comes from
 * libawt_headless.so, which this library links against so that symbol lookup
 * through this handle finds it.
 */
#include <jni.h>

#define NOOP_INIT_IDS(cls) \
    JNIEXPORT void JNICALL Java_##cls##_initIDs(JNIEnv *env, jclass c) { (void) env; (void) c; }

NOOP_INIT_IDS(java_awt_AWTEvent)
NOOP_INIT_IDS(java_awt_Button)
NOOP_INIT_IDS(java_awt_Checkbox)
NOOP_INIT_IDS(java_awt_Component)
NOOP_INIT_IDS(java_awt_Container)
NOOP_INIT_IDS(java_awt_Cursor)
NOOP_INIT_IDS(java_awt_Dialog)
NOOP_INIT_IDS(java_awt_Event)
NOOP_INIT_IDS(java_awt_FileDialog)
NOOP_INIT_IDS(java_awt_Frame)
NOOP_INIT_IDS(java_awt_Insets)
NOOP_INIT_IDS(java_awt_KeyboardFocusManager)
NOOP_INIT_IDS(java_awt_Menu)
NOOP_INIT_IDS(java_awt_MenuComponent)
NOOP_INIT_IDS(java_awt_MenuItem)
NOOP_INIT_IDS(java_awt_Scrollbar)
NOOP_INIT_IDS(java_awt_ScrollPane)
NOOP_INIT_IDS(java_awt_TextArea)
NOOP_INIT_IDS(java_awt_TextField)
NOOP_INIT_IDS(java_awt_TrayIcon)
NOOP_INIT_IDS(java_awt_Window)
NOOP_INIT_IDS(java_awt_event_InputEvent)
NOOP_INIT_IDS(java_awt_event_KeyEvent)
NOOP_INIT_IDS(java_awt_event_MouseEvent)
NOOP_INIT_IDS(java_awt_Choice)
NOOP_INIT_IDS(java_awt_Label)
NOOP_INIT_IDS(java_awt_List)
NOOP_INIT_IDS(java_awt_Rectangle)
NOOP_INIT_IDS(java_awt_Dimension)
NOOP_INIT_IDS(java_awt_Color)
NOOP_INIT_IDS(java_awt_CheckboxMenuItem)
NOOP_INIT_IDS(java_awt_TextComponent)
NOOP_INIT_IDS(java_awt_PopupMenu)
NOOP_INIT_IDS(java_awt_SplashScreen)

JNIEXPORT void JNICALL Java_java_awt_Cursor_finalizeImpl(JNIEnv *env, jclass c, jlong p) {
    (void) env; (void) c; (void) p;
}

JNIEXPORT void JNICALL Java_java_awt_AWTEvent_nativeSetSource(JNIEnv *env, jobject e, jobject s) {
    (void) env; (void) e; (void) s;
}

JNIEXPORT void JNICALL Java_sun_awt_SunToolkit_closeSplashScreen(JNIEnv *env, jclass c) {
    (void) env; (void) c;
}

JNIEXPORT jboolean JNICALL Java_sun_awt_UNIXToolkit_check_1gtk(JNIEnv *env, jclass c, jint v) {
    (void) env; (void) c; (void) v;
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_sun_awt_UNIXToolkit_get_1gtk_1version(JNIEnv *env, jclass c) {
    (void) env; (void) c;
    return 0;
}

JNIEXPORT jboolean JNICALL Java_sun_awt_UNIXToolkit_gtkCheckVersionImpl(JNIEnv *env, jobject t,
        jint major, jint minor, jint micro) {
    (void) env; (void) t; (void) major; (void) minor; (void) micro;
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_sun_awt_UNIXToolkit_load_1gtk(JNIEnv *env, jclass c, jint v, jboolean verbose) {
    (void) env; (void) c; (void) v; (void) verbose;
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_sun_awt_UNIXToolkit_unload_1gtk(JNIEnv *env, jclass c) {
    (void) env; (void) c;
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_sun_awt_UNIXToolkit_load_1gtk_1icon(JNIEnv *env, jobject t, jstring f) {
    (void) env; (void) t; (void) f;
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_sun_awt_UNIXToolkit_load_1stock_1icon(JNIEnv *env, jobject t,
        jint type, jstring id, jint size, jint dir, jstring detail) {
    (void) env; (void) t; (void) type; (void) id; (void) size; (void) dir; (void) detail;
    return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_sun_awt_UNIXToolkit_nativeSync(JNIEnv *env, jobject t) {
    (void) env; (void) t;
}
