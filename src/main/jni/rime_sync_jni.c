/*
 * rime_sync_jni.c
 * 通过 dlsym 调用 libyuyanime.so 中未暴露的 RimeSyncUserData()
 * 编译命令 (需要 Android NDK):
 *   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang \
 *     -shared -o librime_sync.so rime_sync_jni.c -ldl
 */
#include <jni.h>
#include <dlfcn.h>
#include <stdbool.h>

typedef int (*RimeSyncUserDataFunc)();

JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_util_RimeSyncUtils_nativeSyncRimeUserData(JNIEnv *env, jclass clazz) {
    // 在已加载的 libyuyanime.so 中查找 RimeSyncUserData 符号
    // C++ mangled name: _Z16RimeSyncUserDatav
    void *handle = dlopen("libyuyanime.so", RTLD_NOLOAD);
    if (!handle) {
        return false;
    }

    RimeSyncUserDataFunc syncFunc = (RimeSyncUserDataFunc)dlsym(handle, "_Z16RimeSyncUserDatav");
    if (!syncFunc) {
        dlclose(handle);
        return false;
    }

    int result = syncFunc();
    dlclose(handle);
    return (jboolean)(result != 0);
}
