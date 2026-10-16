// yuyan_bridge.h
// 语燕输入法 Rime 引擎桥接层（纯 C++，与 JNI 解耦，便于 Linux 端验证）
//
// 对外语义与旧版 libyuyanime.so 保持一致，供 yuyan_jni.cc 与测试工具调用。
#ifndef YUYAN_BRIDGE_H_
#define YUYAN_BRIDGE_H_

#include <string>
#include <vector>

namespace yuyan {

// ---- 调试日志开关 ----
// 默认关闭，由 Java 侧 Rime.setDebugLog(BuildConfig.DEBUG) 在启动时打开；
// 这样 release 版（BuildConfig.DEBUG=false）原生层不再往 /sdcard/yuyan/ime.log
// 写日志（Kotlin 侧 ImeLog 已由同一个 BuildConfig 开关控制）。
extern bool g_debug_log;
void DebugLog(const char* fmt, ...);

struct CandidateInfo {
  std::string text;
  std::string comment;
};

struct CompositionInfo {
  int length = 0;
  int cursor_pos = 0;
  int sel_start = 0;
  int sel_end = 0;
  std::string preedit;
};

struct MenuInfo {
  int page_size = 0;
  int page_no = 0;
  bool is_last_page = true;
  int highlighted_index = 0;
  std::vector<CandidateInfo> candidates;
};

struct ContextInfo {
  CompositionInfo composition;
  MenuInfo menu;
  std::string commit_preview;
  std::vector<std::string> select_labels;
};

struct StatusInfo {
  std::string schema_id;
  std::string schema_name;
  bool is_disable = true;
  bool is_composing = false;
  bool is_ascii_mode = true;
  bool is_full_shape = false;
  bool is_simplified = false;
  bool is_traditional = false;
  bool is_ascii_punch = true;
};

class Engine {
 public:
  static Engine& Instance();

  // 初始化/重启引擎。fullCheck=true 时执行完整部署（重新编译方案）。
  void Startup(const std::string& shared_dir,
               const std::string& user_dir,
               bool full_check);
  void Shutdown();

  bool SelectSchema(const std::string& schema_id);
  std::string CurrentSchema();

  // 处理按键。mask 语义与旧版一致：软键盘传入 event.action，实际忽略。
  bool ProcessKey(int keycode, int mask);
  // 将输入串 [caret_pos, caret_pos+length) 替换为 key（用于 9 键/双拼转换）
  bool ReplaceKey(int caret_pos, int length, const std::string& key);
  // 移动组合区光标（点击待编辑区字母时用）
  bool SetCaretPos(int pos);
  void ClearComposition();

  bool GetCommit(std::string* out);

  // 读取当前方案的 switcher 选项（name\t状态0\t状态1\t当前值 每行一条），
  // 供键盘菜单动态展示（同文机制：部署后选项自动出现，无需硬编码）
  std::string GetSwitches();

  // ---- 方案列表：librime levers/switcher API（同文输入法同款机制）----
  // 返回每行一条 "schema_id\t方案名"；引擎未就绪或取不到时返回空串。
  //   AvailableSchemas()：引擎扫到的【全部可用】方案（含用户自行导入的万象/雾凇等）
  //   EnabledSchemas()  ：当前【生效】的启用清单（default.custom.yaml 的 schema_list）
  // App 不再写死任何方案 id —— 清单一律来自引擎。
  std::string AvailableSchemas();
  std::string EnabledSchemas();
  // 覆盖写启用清单 —— 交给 librime 自己写 default.custom.yaml（同文 setEnabledSchemata）。
  // 返回是否成功；成功后需要重新部署才会生效（由调用方触发）。
  bool SetEnabledSchemas(const std::vector<std::string>& ids);
  // 开关组（schema 里用 options: 定义的组，如 s2s/s2t/s2hk/s2tw）切换
  void SetOptionGroup(const std::string& options, int index);
  bool GetContext(ContextInfo* out);
  bool GetStatus(StatusInfo* out);

  void SetOption(const std::string& name, bool value);
  bool SelectCandidate(int index);  // 全局索引（与候选栏累计列表一致）
  bool DeleteCandidate(int index);  // 「忘记该词」：从用户词库删掉该候选（全局索引，同 Select）
  int GetKeycodeByName(const std::string& name);

  // 联想词：以 text 结尾字为词根查询词典，返回组词联想
  std::vector<std::string> AssociateList(const std::string& text);
  bool SelectAssociate(int index);

  bool SyncUserData();
  bool DeployWorkspace();

  // 候选区分页（模拟大页，避免 rime 内置小分页）
  void SetPageSize(int page_size);

 private:
  Engine() = default;
  void EnsureSession();
  void ResetPaging();
  std::string DictName();

  bool initialized_ = false;
  unsigned long session_id_ = 0;
  int page_size_ = 100;
  int page_offset_ = 0;
  std::vector<std::string> associate_words_;
  std::string pending_commit_;
  std::string shared_dir_;
  std::string user_dir_;
};

}  // namespace yuyan

#endif  // YUYAN_BRIDGE_H_
