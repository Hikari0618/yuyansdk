/*
 * rime_sync_jni.c
 * 通过 dlsym 调用 libyuyanime.so 中未暴露的 RimeSyncUserData()
 *
 * 编译 (需要 Android NDK):
 *   $CC -shared -o librime_sync.so rime_sync_jni.c -ldl
 */
#include <jni.h>
#include <dlfcn.h>

typedef int (*RimeFuncVoid)();

JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_util_RimeSyncUtils_nativeSyncRimeUserData(JNIEnv *env, jclass clazz) {
    void* handle = dlopen("libyuyanime.so", RTLD_NOLOAD);
    if (!handle) return false;
    RimeFuncVoid fn = (RimeFuncVoid)dlsym(handle, "_Z16RimeSyncUserDatav");
    if (!fn) return false;
    return (jboolean)(fn() != 0);
}
