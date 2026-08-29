/*
 * rime_sync_jni.c
 *
 * JNI wrapper for Rime user data sync.
 * Uses dlopen/dlsym to call RimeSyncUserData() and RimeDeployWorkspace()
 * from libyuyanime.so, which contains the pre-compiled librime engine.
 *
 * Compiled to librime_sync.so by the CI build step.
 *
 * 编译 (需要 Android NDK):
 *   $CC -shared -o librime_sync.so rime_sync_jni.c -ldl
 */

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#define TAG "RimeSync"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Get handle to the already-loaded libyuyanime.so */
static void *get_handle(void) {
    /* RTLD_NOLOAD: library must already be loaded by System.loadLibrary("yuyanime") */
    void *handle = dlopen("libyuyanime.so", RTLD_NOLOAD);
    if (!handle) {
        LOGE("dlopen libyuyanime.so failed: %s", dlerror());
    }
    return handle;
}

/*
 * Look up a symbol by its C++ mangled name, falling back to the plain C name.
 */
static void *get_func(void *handle, const char *mangled, const char *plain) {
    void *func = dlsym(handle, mangled);
    if (!func) {
        func = dlsym(handle, plain);
    }
    if (!func) {
        LOGE("dlsym failed (%s / %s): %s", mangled, plain, dlerror());
    }
    return func;
}

/*
 * RimeSyncUserData()
 * Triggers user dictionary sync. Copies user db to/from the sync directory
 * configured in installation.yaml (sync_dir field).
 *
 * C++ mangled name: _Z16RimeSyncUserDatav
 */
JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_util_RimeSyncUtils_nativeSyncRimeUserData(JNIEnv *env, jclass clazz) {
    void *handle = get_handle();
    if (!handle) return JNI_FALSE;

    typedef int (*sync_func_t)(void);
    sync_func_t func = (sync_func_t) get_func(handle,
            "_Z16RimeSyncUserDatav", "RimeSyncUserData");
    if (!func) return JNI_FALSE;

    LOGI("Calling RimeSyncUserData...");
    int result = func();
    LOGI("RimeSyncUserData done, result=%d", result);
    return (jboolean)(result != 0);
}

/*
 * RimeDeployWorkspace()
 * Re-deploys the Rime workspace (reloads schemas, rebuilds dictionaries).
 * Useful after sync imports new data.
 *
 * C++ mangled name: _Z19RimeDeployWorkspacev
 */
JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_util_RimeSyncUtils_nativeDeployWorkspace(JNIEnv *env, jclass clazz) {
    void *handle = get_handle();
    if (!handle) return JNI_FALSE;

    typedef int (*deploy_func_t)(void);
    deploy_func_t func = (deploy_func_t) get_func(handle,
            "_Z19RimeDeployWorkspacev", "RimeDeployWorkspace");
    if (!func) return JNI_FALSE;

    LOGI("Calling RimeDeployWorkspace...");
    int result = func();
    LOGI("RimeDeployWorkspace done, result=%d", result);
    return (jboolean)(result != 0);
}
