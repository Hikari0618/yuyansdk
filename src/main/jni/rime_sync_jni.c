/*
 * rime_sync_jni.c
 * 通过 dlsym 调用 libyuyanime.so 中未暴露的 Rime 函数
 *
 * 编译 (需要 Android NDK):
 *   $CC -shared -o librime_sync.so rime_sync_jni.c -ldl
 */
#include <jni.h>
#include <dlfcn.h>
#include <stdbool.h>

typedef int (*RimeFuncVoid)();

/* 获取 libyuyanime.so 的 handle，失败返回 NULL */
static void* get_handle() {
    static void* handle = NULL;
    if (!handle) handle = dlopen("libyuyanime.so", RTLD_NOLOAD);
    return handle;
}

/* 查找符号，失败返回 NULL */
static void* find_sym(const char* name) {
    void* h = get_handle();
    return h ? dlsym(h, name) : NULL;
}

/* RimeSyncUserData() — 同步用户数据 */
JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_util_RimeSyncUtils_nativeSyncRimeUserData(JNIEnv *env, jclass clazz) {
    RimeFuncVoid fn = (RimeFuncVoid)find_sym("_Z16RimeSyncUserDatav");
    if (!fn) return false;
    return (jboolean)(fn() != 0);
}

/* RimeDeployWorkspace() — 重新部署（重新编译方案文件） */
JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_util_RimeDeployUtils_nativeDeployWorkspace(JNIEnv *env, jclass clazz) {
    RimeFuncVoid fn = (RimeFuncVoid)find_sym("_Z19RimeDeployWorkspacev");
    if (!fn) return false;
    return (jboolean)(fn() != 0);
}
