// yuyan_jni.cc
// JNI 绑定：与 com.yuyan.inputmethod.core.Rime.kt / RimeSyncUtils.kt 的
// external 函数一一对应。引擎逻辑见 yuyan_bridge.cc。
#include <jni.h>

#include <string>
#include <sstream>
#include <vector>

#include "yuyan_bridge.h"

extern void rime_require_module_lua();
extern void rime_require_module_octagram();

namespace {

std::string JToStdString(JNIEnv* env, jstring js) {
  if (!js) return "";
  const char* chars = env->GetStringUTFChars(js, nullptr);
  std::string result = chars ? chars : "";
  if (chars) env->ReleaseStringUTFChars(js, chars);
  return result;
}

jstring JFromString(JNIEnv* env, const std::string& s) {
  return env->NewStringUTF(s.c_str());
}

// ---- 构造 com.yuyan.inputmethod.core 下的数据类 ----

jobject MakeCandidate(JNIEnv* env, const yuyan::CandidateInfo& c) {
  jclass cls = env->FindClass("com/yuyan/inputmethod/core/CandidateListItem");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
  jstring comment = JFromString(env, c.comment);
  jstring text = JFromString(env, c.text);
  return env->NewObject(cls, ctor, comment, text);
}

jobject MakeComposition(JNIEnv* env, const yuyan::CompositionInfo& c) {
  jclass cls = env->FindClass("com/yuyan/inputmethod/core/RimeComposition");
  jmethodID ctor =
      env->GetMethodID(cls, "<init>", "(IIIILjava/lang/String;)V");
  jstring preedit = JFromString(env, c.preedit);
  return env->NewObject(cls, ctor, (jint)c.length, (jint)c.cursor_pos,
                        (jint)c.sel_start, (jint)c.sel_end, preedit);
}

jobject MakeMenu(JNIEnv* env, const yuyan::MenuInfo& m) {
  jclass itemCls = env->FindClass("com/yuyan/inputmethod/core/CandidateListItem");
  jobjectArray items =
      env->NewObjectArray((jsize)m.candidates.size(), itemCls, nullptr);
  for (jsize i = 0; i < (jsize)m.candidates.size(); i++) {
    jobject item = MakeCandidate(env, m.candidates[i]);
    env->SetObjectArrayElement(items, i, item);
    env->DeleteLocalRef(item);
  }
  jclass cls = env->FindClass("com/yuyan/inputmethod/core/RimeMenu");
  jmethodID ctor = env->GetMethodID(
      cls, "<init>", "(IIZII[Lcom/yuyan/inputmethod/core/CandidateListItem;)V");
  return env->NewObject(cls, ctor, (jint)m.page_size, (jint)m.page_no,
                        (jboolean)(m.is_last_page ? JNI_TRUE : JNI_FALSE),
                        (jint)m.highlighted_index, (jint)m.candidates.size(),
                        items);
}

jobject MakeContext(JNIEnv* env, const yuyan::ContextInfo& c) {
  jobject composition = MakeComposition(env, c.composition);
  jobject menu = MakeMenu(env, c.menu);
  jstring preview = JFromString(env, c.commit_preview);
  jclass strCls = env->FindClass("java/lang/String");
  jobjectArray labels =
      env->NewObjectArray((jsize)c.select_labels.size(), strCls, nullptr);
  for (jsize i = 0; i < (jsize)c.select_labels.size(); i++) {
    jstring label = JFromString(env, c.select_labels[i]);
    env->SetObjectArrayElement(labels, i, label);
    env->DeleteLocalRef(label);
  }
  jclass cls = env->FindClass("com/yuyan/inputmethod/core/RimeContext");
  jmethodID ctor = env->GetMethodID(
      cls, "<init>",
      "(Lcom/yuyan/inputmethod/core/RimeComposition;"
      "Lcom/yuyan/inputmethod/core/RimeMenu;"
      "Ljava/lang/String;[Ljava/lang/String;)V");
  return env->NewObject(cls, ctor, composition, menu, preview, labels);
}

jobject MakeStatus(JNIEnv* env, const yuyan::StatusInfo& s) {
  jclass cls = env->FindClass("com/yuyan/inputmethod/core/RimeStatus");
  jmethodID ctor = env->GetMethodID(
      cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;ZZZZZZZ)V");
  jstring id = JFromString(env, s.schema_id);
  jstring name = JFromString(env, s.schema_name);
  return env->NewObject(
      cls, ctor, id, name, (jboolean)(s.is_disable ? JNI_TRUE : JNI_FALSE),
      (jboolean)(s.is_composing ? JNI_TRUE : JNI_FALSE),
      (jboolean)(s.is_ascii_mode ? JNI_TRUE : JNI_FALSE),
      (jboolean)(s.is_full_shape ? JNI_TRUE : JNI_FALSE),
      (jboolean)(s.is_simplified ? JNI_TRUE : JNI_FALSE),
      (jboolean)(s.is_traditional ? JNI_TRUE : JNI_FALSE),
      (jboolean)(s.is_ascii_punch ? JNI_TRUE : JNI_FALSE));
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  // 静态链接插件模块（librime-lua / librime-octagram）
  rime_require_module_lua();
  rime_require_module_octagram();
  return JNI_VERSION_1_6;
}

// ---- com.yuyan.inputmethod.core.Rime ----

JNIEXPORT void JNICALL
Java_com_yuyan_inputmethod_core_Rime_startupRime(JNIEnv* env, jclass,
                                                 jobject context,
                                                 jstring shared_dir,
                                                 jstring user_dir,
                                                 jboolean full_check) {
  (void)context;
  yuyan::Engine::Instance().Startup(JToStdString(env, shared_dir),
                                    JToStdString(env, user_dir),
                                    full_check == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_yuyan_inputmethod_core_Rime_exitRime(JNIEnv*, jclass) {
  yuyan::Engine::Instance().Shutdown();
}

JNIEXPORT void JNICALL
Java_com_yuyan_inputmethod_core_Rime_setRimePageSize(JNIEnv*, jclass,
                                                     jint page_size) {
  yuyan::Engine::Instance().SetPageSize(page_size);
}

JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_core_Rime_processRimeKey(JNIEnv*, jclass,
                                                    jint keycode, jint mask) {
  return yuyan::Engine::Instance().ProcessKey(keycode, mask) ? JNI_TRUE
                                                             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_core_Rime_replaceRimeKey(JNIEnv* env, jclass,
                                                    jint caret_pos,
                                                    jint length, jstring key) {
  return yuyan::Engine::Instance().ReplaceKey(caret_pos, length,
                                              JToStdString(env, key))
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_yuyan_inputmethod_core_Rime_clearRimeComposition(JNIEnv*, jclass) {
  yuyan::Engine::Instance().ClearComposition();
}

JNIEXPORT jobject JNICALL
Java_com_yuyan_inputmethod_core_Rime_getRimeCommit(JNIEnv* env, jclass) {
  std::string text;
  if (!yuyan::Engine::Instance().GetCommit(&text)) return nullptr;
  jclass cls = env->FindClass("com/yuyan/inputmethod/core/RimeCommit");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;)V");
  jstring commit = JFromString(env, text);
  return env->NewObject(cls, ctor, commit);
}

JNIEXPORT jobject JNICALL
Java_com_yuyan_inputmethod_core_Rime_getRimeContext(JNIEnv* env, jclass) {
  yuyan::ContextInfo info;
  if (!yuyan::Engine::Instance().GetContext(&info)) return nullptr;
  return MakeContext(env, info);
}

JNIEXPORT jobject JNICALL
Java_com_yuyan_inputmethod_core_Rime_getRimeStatus(JNIEnv* env, jclass) {
  yuyan::StatusInfo info;
  if (!yuyan::Engine::Instance().GetStatus(&info)) return nullptr;
  return MakeStatus(env, info);
}

JNIEXPORT void JNICALL
Java_com_yuyan_inputmethod_core_Rime_setRimeOption(JNIEnv* env, jclass,
                                                   jstring option,
                                                   jboolean value) {
  yuyan::Engine::Instance().SetOption(JToStdString(env, option),
                                      value == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_yuyan_inputmethod_core_Rime_setRimeOptionGroup(JNIEnv* env, jclass,
                                                        jstring options,
                                                        jint index) {
  yuyan::Engine::Instance().SetOptionGroup(JToStdString(env, options),
                                           static_cast<int>(index));
}

JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_core_Rime_setRimeCaretPos(JNIEnv*, jclass,
                                                     jint caret_pos) {
  return yuyan::Engine::Instance().SetCaretPos(static_cast<int>(caret_pos))
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_yuyan_inputmethod_core_Rime_getRimeSwitches(JNIEnv* env, jclass) {
  std::string s = yuyan::Engine::Instance().GetSwitches();
  // 崩溃边界埋点：判断进程是不是死在 NewStringUTF（真机崩溃日志为空 = native 崩溃）
  yuyan::DebugLog("[switches] jni 准备 NewStringUTF len=%zu\n", s.size());
  jstring js = env->NewStringUTF(s.c_str());
  yuyan::DebugLog("[switches] jni NewStringUTF 完成 ok=%d\n", js != nullptr ? 1 : 0);
  return js;
}

// 原生层日志开关：Java 侧 Rime.setDebugLog(BuildConfig.DEBUG) 启动时调用，
// release 版传 false → 原生层不再写 /sdcard/yuyan/ime.log。
JNIEXPORT void JNICALL
Java_com_yuyan_inputmethod_core_Rime_setDebugLog(JNIEnv*, jclass,
                                                jboolean enabled) {
  yuyan::g_debug_log = (enabled == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_core_Rime_getRimeOption(JNIEnv* env, jclass,
                                                   jstring option) {
  // 复用 GetSwitches 的取值（避免额外 API 面）——按名称查当前值
  std::string name = JToStdString(env, option);
  std::string all = yuyan::Engine::Instance().GetSwitches();
  std::istringstream ss(all);
  std::string line;
  while (std::getline(ss, line)) {
    if (line.rfind(name + "\t", 0) == 0) {
      return (!line.empty() && line.back() == '1') ? JNI_TRUE : JNI_FALSE;
    }
  }
  return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_yuyan_inputmethod_core_Rime_getCurrentRimeSchema(JNIEnv* env,
                                                          jclass) {
  return JFromString(env, yuyan::Engine::Instance().CurrentSchema());
}

JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_core_Rime_selectRimeSchema(JNIEnv* env, jclass,
                                                      jstring schema_id) {
  return yuyan::Engine::Instance().SelectSchema(JToStdString(env, schema_id))
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_core_Rime_selectRimeCandidate(JNIEnv*, jclass,
                                                         jint index) {
  return yuyan::Engine::Instance().SelectCandidate(index) ? JNI_TRUE
                                                          : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_yuyan_inputmethod_core_Rime_getRimeKeycodeByName(JNIEnv* env, jclass,
                                                          jstring name) {
  return yuyan::Engine::Instance().GetKeycodeByName(JToStdString(env, name));
}

JNIEXPORT jobjectArray JNICALL
Java_com_yuyan_inputmethod_core_Rime_getRimeAssociateList(JNIEnv* env, jclass,
                                                          jstring key) {
  std::vector<std::string> words =
      yuyan::Engine::Instance().AssociateList(JToStdString(env, key));
  jclass strCls = env->FindClass("java/lang/String");
  jobjectArray result =
      env->NewObjectArray((jsize)words.size(), strCls, nullptr);
  for (jsize i = 0; i < (jsize)words.size(); i++) {
    jstring word = JFromString(env, words[i]);
    env->SetObjectArrayElement(result, i, word);
    env->DeleteLocalRef(word);
  }
  return result;
}

JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_core_Rime_selectRimeAssociate(JNIEnv*, jclass,
                                                         jint index) {
  return yuyan::Engine::Instance().SelectAssociate(index) ? JNI_TRUE
                                                          : JNI_FALSE;
}

// ---- com.yuyan.inputmethod.util.RimeSyncUtils ----

JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_util_RimeSyncUtils_nativeSyncRimeUserData(JNIEnv*,
                                                                     jclass) {
  return yuyan::Engine::Instance().SyncUserData() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_util_RimeSyncUtils_nativeDeployWorkspace(JNIEnv*,
                                                                    jclass) {
  return yuyan::Engine::Instance().DeployWorkspace() ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
