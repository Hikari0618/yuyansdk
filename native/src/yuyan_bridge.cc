// yuyan_bridge.cc
// 语燕输入法 Rime 引擎桥接层实现
//
// 基于 librime 1.17 标准 API（rime_get_api），附加：
//  - 大分页模拟（兼容旧引擎 setRimePageSize 语义）
//  - 联想词（反查词根 + 词典预测式查询）
//  - 部署 / 用户数据同步
#include "yuyan_bridge.h"

#include <rime_api.h>

// 强制链接 librime-lua / librime-octagram 的模块注册对象：
// RIME_REGISTER_MODULE 生成的 rime_require_module_* 符号必须被引用，
// 否则静态库的无引用对象被链接器丢弃，lua_processor/lua_translator/
// lua_filter/grammar 等组件无法注册（万象的 lua 流水线会全部失效）。
// 注意：这些符号是 C++ 链接（宏在 C++ 单元展开），不能用 extern "C"。
void rime_require_module_lua();
void rime_require_module_grammar();
void rime_require_module_octagram();
__attribute__((used)) static const void* const kForceLinkModules[] = {
    (const void*)(void (*)(void))&rime_require_module_lua,
    (const void*)(void (*)(void))&rime_require_module_grammar,
    (const void*)(void (*)(void))&rime_require_module_octagram,
};

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <memory>
#include <mutex>
#include <sys/stat.h>

#include <rime/schema.h>
#include <rime/service.h>
#include <rime/key_table.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/reverse_lookup_dictionary.h>

namespace yuyan {

namespace {

std::mutex g_mutex;

RimeApi* rime() {
  static RimeApi* api = rime_get_api();
  return api;
}

// 词根反查结果按空格拆分（多音字多个读音）
std::vector<std::string> SplitWords(const std::string& s) {
  std::vector<std::string> out;
  std::string cur;
  for (char c : s) {
    if (c == ' ' || c == '\t' || c == ',') {
      if (!cur.empty()) out.push_back(cur);
      cur.clear();
    } else {
      cur.push_back(c);
    }
  }
  if (!cur.empty()) out.push_back(cur);
  return out;
}

// 声调/附加符号归一化：hào → hao、lǜ → lv（反查读音转为拼写前缀查询）
std::string NormalizeSpelling(const std::string& s) {
  static const std::pair<const char*, const char*> kMap[] = {
      {"ā", "a"}, {"á", "a"}, {"ǎ", "a"}, {"à", "a"}, {"ē", "e"}, {"é", "e"},
      {"ě", "e"}, {"è", "e"}, {"ī", "i"}, {"í", "i"}, {"ǐ", "i"}, {"ì", "i"},
      {"ō", "o"}, {"ó", "o"}, {"ǒ", "o"}, {"ò", "o"}, {"ū", "u"}, {"ú", "u"},
      {"ǔ", "u"}, {"ù", "u"}, {"ǖ", "v"}, {"ǘ", "v"}, {"ǚ", "v"}, {"ǜ", "v"},
      {"ü", "v"}, {"ń", "n"}, {"ň", "n"}, {"ǹ", "n"}, {"ḿ", "m"},
  };
  std::string out;
  size_t i = 0;
  while (i < s.size()) {
    bool matched = false;
    for (const auto& p : kMap) {
      size_t len = strlen(p.first);
      if (s.compare(i, len, p.first) == 0) {
        out += p.second;
        i += len;
        matched = true;
        break;
      }
    }
    if (matched) continue;
    unsigned char c = static_cast<unsigned char>(s[i]);
    // 去掉组合声调符号（U+0304/U+0301/U+030C/U+0300）
    if (c == 0xCC && i + 1 < s.size() &&
        (s[i + 1] == 0x84 || s[i + 1] == 0x81 || s[i + 1] == 0x8C ||
         s[i + 1] == 0x80)) {
      i += 2;
      continue;
    }
    if (c >= '1' && c <= '5') {  // 数字声调
      i++;
      continue;
    }
    out.push_back(static_cast<char>(c));
    i++;
  }
  return out;
}

// 取字符串最后一个 UTF-8 字符
std::string LastUtf8Char(const std::string& s) {
  if (s.empty()) return "";
  size_t i = s.size() - 1;
  while (i > 0 && (static_cast<unsigned char>(s[i]) & 0xC0) == 0x80) --i;
  return s.substr(i);
}

std::string EscapeKeySequence(const std::string& key) {
  std::string out;
  for (char c : key) {
    if (c == '{' || c == '}' || c == '\\') out.push_back('\\');
    out.push_back(c);
  }
  return out;
}

}  // namespace

Engine& Engine::Instance() {
  static Engine engine;
  return engine;
}

void Engine::Startup(const std::string& shared_dir,
                     const std::string& user_dir,
                     bool full_check) {
  shared_dir_ = shared_dir;
  user_dir_ = user_dir;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!initialized_) {
      RIME_STRUCT(RimeTraits, traits)
      traits.shared_data_dir = shared_dir_.c_str();
      traits.user_data_dir = user_dir_.c_str();
      // 引擎日志写 /sdcard/yuyan/logs（部署失败的真实原因都在这里，
      // 比如 "error building config: default" / "schema list not defined"）；
      // 无权限时退回空（只输出 stderr）
      ::mkdir("/sdcard/yuyan", 0770);
      static const char* kLogDir = "/sdcard/yuyan/logs";
      traits.log_dir = (::mkdir(kLogDir, 0770) == 0 || errno == EEXIST) ? kLogDir : "";
      traits.app_name = "rime.yuyan";
      traits.distribution_name = "YuyanIme";
      traits.distribution_code_name = "yuyan";
      traits.distribution_version = "1.0";
      // deployer 组含 core+dict+levers（部署/同步任务），gears 是翻译组件，
      // lua/octagram 是万象等方案的脚本与语言模型组件
      static const char* kModules[] = {"deployer", "gears", "lua", "octagram",
                                       nullptr};
      traits.modules = kModules;
      rime()->setup(&traits);
      rime()->initialize(&traits);
      initialized_ = true;
      session_id_ = 0;
    }
    if (full_check && session_id_) {
      rime()->destroy_session(session_id_);
      session_id_ = 0;
      ResetPaging();
    }
  }
  if (full_check) {
    // 完整部署：重新编译方案文件。编译可达分钟级，必须在锁外等待，
    // 否则期间所有按键/JNI 调用被阻塞（表现为打不出字、系统侧滑卡顿）。
    rime()->start_maintenance(True);
    rime()->join_maintenance_thread();
    std::lock_guard<std::mutex> lock(g_mutex);
    ResetPaging();
    EnsureSession();
    return;
  }
  std::lock_guard<std::mutex> lock(g_mutex);
  EnsureSession();
}

void Engine::Shutdown() {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!initialized_) return;
  if (session_id_) {
    rime()->destroy_session(session_id_);
    session_id_ = 0;
  }
  rime()->finalize();
  initialized_ = false;
  ResetPaging();
}

void Engine::EnsureSession() {
  if (!initialized_) return;
  // 只在没有会话时创建。此前还会用 find_session 探活并在"失效"时重建，
  // 但 find_session 在 Android 上会误判导致每键重建新会话——组合输入
  // （preedit）随之每键清空、候选永远为 0（打不出字）。
  // 会话真失效时 processKey 会返回 false，由上层重新初始化来恢复。
  if (!session_id_) {
    session_id_ = rime()->create_session();
    ResetPaging();
    FILE* f = fopen("/sdcard/yuyan/ime.log", "a");
    if (f) {
      fprintf(f, "[bridge] session created id=%d\n", (int)session_id_);
      fclose(f);
    }
  }
}

void Engine::ResetPaging() {
  page_offset_ = 0;
  associate_words_.clear();
}

bool Engine::SelectSchema(const std::string& schema_id) {
  std::lock_guard<std::mutex> lock(g_mutex);
  EnsureSession();
  if (!session_id_) return false;
  ResetPaging();
  return rime()->select_schema(session_id_, schema_id.c_str()) != 0;
}

std::string Engine::CurrentSchema() {
  std::lock_guard<std::mutex> lock(g_mutex);
  EnsureSession();
  if (!session_id_) return "";
  char buffer[256] = {0};
  if (rime()->get_current_schema(session_id_, buffer, sizeof(buffer))) {
    return buffer;
  }
  return "";
}

bool Engine::ProcessKey(int keycode, int mask) {
  std::lock_guard<std::mutex> lock(g_mutex);
  EnsureSession();
  if (!session_id_) return false;
  (void)mask;  // 旧引擎语义：软键盘传 event.action，不作为修饰键
  if (keycode <= 0 || keycode == 0xffffff) return false;

  // 模拟大分页：拦截翻页键，按 page_size_ 步进
  if (keycode == GetKeycodeByName("Page_Down") ||
      keycode == GetKeycodeByName("Page_Up")) {
    ContextInfo probe;
    bool backward = (keycode == GetKeycodeByName("Page_Up"));
    int target = page_offset_ + (backward ? -page_size_ : page_size_);
    if (target < 0) target = 0;
    if (target != page_offset_) {
      // 仅当目标页有候选时翻页
      RimeCandidateListIterator iter{};
      bool has = false;
      if (rime()->candidate_list_from_index(session_id_, &iter, target)) {
        has = rime()->candidate_list_next(&iter) != 0;
        rime()->candidate_list_end(&iter);
      }
      if (has) page_offset_ = target;
    }
    return true;
  }

  ResetPaging();
  return rime()->process_key(session_id_, keycode, 0) != 0;
}

bool Engine::ReplaceKey(int caret_pos, int length, const std::string& key) {
  std::lock_guard<std::mutex> lock(g_mutex);
  EnsureSession();
  if (!session_id_) return false;
  if (length < 0) return false;
  ResetPaging();
  rime()->set_caret_pos(session_id_, static_cast<size_t>(caret_pos + length));
  int backspace = GetKeycodeByName("BackSpace");
  for (int i = 0; i < length; i++) {
    rime()->process_key(session_id_, backspace, 0);
  }
  if (!key.empty()) {
    std::string seq = EscapeKeySequence(key);
    rime()->simulate_key_sequence(session_id_, seq.c_str());
  }
  return true;
}

void Engine::ClearComposition() {
  std::lock_guard<std::mutex> lock(g_mutex);
  EnsureSession();
  if (!session_id_) return;
  ResetPaging();
  rime()->clear_composition(session_id_);
}

bool Engine::GetCommit(std::string* out) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!pending_commit_.empty()) {
    *out = pending_commit_;
    pending_commit_.clear();
    return true;
  }
  EnsureSession();
  if (!session_id_) return false;
  RIME_STRUCT(RimeCommit, commit)
  if (rime()->get_commit(session_id_, &commit)) {
    if (commit.text) *out = commit.text;
    rime()->free_commit(&commit);
    return true;
  }
  return false;
}

bool Engine::GetContext(ContextInfo* out) {
  std::lock_guard<std::mutex> lock(g_mutex);
  EnsureSession();
  if (!session_id_) return false;
  RIME_STRUCT(RimeContext, ctx)
  if (!rime()->get_context(session_id_, &ctx)) {
    return false;
  }
  out->composition.length = ctx.composition.length;
  out->composition.cursor_pos = ctx.composition.cursor_pos;
  out->composition.sel_start = ctx.composition.sel_start;
  out->composition.sel_end = ctx.composition.sel_end;
  if (ctx.composition.preedit) out->composition.preedit = ctx.composition.preedit;
  if (ctx.commit_text_preview) out->commit_preview = ctx.commit_text_preview;

  // 全局候选 + 自模拟分页
  out->menu.page_size = page_size_;
  out->menu.page_no = page_size_ > 0 ? page_offset_ / page_size_ : 0;
  out->menu.highlighted_index = 0;
  out->menu.candidates.clear();
  RimeCandidateListIterator iter{};
  if (rime()->candidate_list_from_index(session_id_, &iter, page_offset_)) {
    while ((int)out->menu.candidates.size() < page_size_ &&
           rime()->candidate_list_next(&iter)) {
      CandidateInfo item;
      if (iter.candidate.text) item.text = iter.candidate.text;
      if (iter.candidate.comment) item.comment = iter.candidate.comment;
      out->menu.candidates.push_back(item);
    }
    rime()->candidate_list_end(&iter);
  }
  out->menu.is_last_page = (int)out->menu.candidates.size() < page_size_;

  // select_labels 数量与 rime 自身 page_size 对应
  if (ctx.select_labels) {
    for (int i = 0; i < ctx.menu.page_size; i++) {
      if (ctx.select_labels[i]) out->select_labels.push_back(ctx.select_labels[i]);
    }
  }
  rime()->free_context(&ctx);
  return true;
}

bool Engine::GetStatus(StatusInfo* out) {
  std::lock_guard<std::mutex> lock(g_mutex);
  EnsureSession();
  if (!session_id_) return false;
  RIME_STRUCT(RimeStatus, status)
  if (!rime()->get_status(session_id_, &status)) {
    return false;
  }
  if (status.schema_id) out->schema_id = status.schema_id;
  if (status.schema_name) out->schema_name = status.schema_name;
  out->is_disable = status.is_disabled != 0;
  out->is_composing = status.is_composing != 0;
  out->is_ascii_mode = status.is_ascii_mode != 0;
  out->is_full_shape = status.is_full_shape != 0;
  out->is_simplified = status.is_simplified != 0;
  out->is_traditional = status.is_traditional != 0;
  out->is_ascii_punch = status.is_ascii_punct != 0;
  rime()->free_status(&status);
  return true;
}

void Engine::SetOption(const std::string& name, bool value) {
  std::lock_guard<std::mutex> lock(g_mutex);
  EnsureSession();
  if (!session_id_) return;
  rime()->set_option(session_id_, name.c_str(), value ? True : False);
}

std::string Engine::GetSwitches() {
  std::lock_guard<std::mutex> lock(g_mutex);
  EnsureSession();
  std::string out;
  // 读取当前方案配置（schema_open 指向当前 schema:/ 命名空间）
  RimeStatus status = {0};
  status.data_size = sizeof(RimeStatus);
  std::string schema_id;
  if (session_id_ && rime()->get_status(session_id_, &status)) {
    schema_id = status.schema_id ? status.schema_id : "";
    rime()->free_status(&status);
  }
  if (schema_id.empty()) return out;
  RimeConfig cfg = {nullptr};
  if (!rime()->schema_open(schema_id.c_str(), &cfg)) return out;
  // 用列表迭代器遍历 switches（同文机制），iter.path 是列表项路径
  RimeConfigIterator iter = {nullptr};
  if (rime()->config_begin_list(&iter, &cfg, "switches")) {
    while (rime()->config_next(&iter)) {
      std::string base = iter.path ? iter.path : "";
      if (base.empty()) continue;
      const char* name =
          rime()->config_get_cstring(&cfg, (base + "/name").c_str());
      if (!name || !*name) continue;
      // states 是内联列表，用迭代器取标量值
      std::string s0s, s1s;
      RimeConfigIterator siter = {nullptr};
      if (rime()->config_begin_list(&siter, &cfg,
                                   (base + "/states").c_str())) {
        int si = 0;
        while (rime()->config_next(&siter)) {
          const char* v = rime()->config_get_cstring(
              &cfg, siter.path ? siter.path : "");
          if (si == 0 && v) s0s = v;
          if (si == 1 && v) s1s = v;
          si++;
        }
        rime()->config_end(&siter);
      }
      bool val = session_id_ && rime()->get_option(session_id_, name);
      out += name;
      out += "\t";
      out += s0s;
      out += "\t";
      out += s1s;
      out += "\t";
      out += val ? "1" : "0";
      out += "\n";
    }
    rime()->config_end(&iter);
  }
  rime()->config_close(&cfg);
  return out;
}

bool Engine::SelectCandidate(int index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  EnsureSession();
  if (!session_id_) return false;
  ResetPaging();
  return rime()->select_candidate(session_id_, static_cast<size_t>(index)) != 0;
}

int Engine::GetKeycodeByName(const std::string& name) {
  return RimeGetKeycodeByName(name.c_str());
}

std::string Engine::DictName() {
  auto session = rime::Service::instance().GetSession(session_id_);
  if (!session || !session->schema() || !session->schema()->config()) return "";
  std::string dict_name;
  if (!session->schema()->config()->GetString("translator/dictionary", &dict_name)) {
    return "";
  }
  return dict_name;
}

std::vector<std::string> Engine::AssociateList(const std::string& text) {
  std::lock_guard<std::mutex> lock(g_mutex);
  associate_words_.clear();
  if (text.empty()) return associate_words_;
  EnsureSession();
  if (!session_id_) return associate_words_;

  std::string stem = LastUtf8Char(text);
  std::string dict_name = DictName();
  if (dict_name.empty()) return associate_words_;

  // 反查词根读音
  std::string codes;
  {
    rime::ReverseLookupDictionaryComponent component;
    std::unique_ptr<rime::ReverseLookupDictionary> rev(component.Create(dict_name));
    if (!rev || !rev->Load()) return associate_words_;
    if (!rev->ReverseLookup(stem, &codes)) return associate_words_;
  }

  // 用读音做前缀预测式查询，取以词根开头的组词
  rime::DictionaryComponent component;
  std::unique_ptr<rime::Dictionary> dict(component.Create(dict_name, dict_name, {}));
  if (!dict || !dict->Load()) return associate_words_;

  std::vector<std::string> raw;
  for (const auto& code : SplitWords(codes)) {
    rime::DictEntryIterator iter;
    dict->LookupWords(&iter, NormalizeSpelling(code), true, 200);
    while (!iter.exhausted()) {
      auto entry = iter.Peek();
      if (entry) raw.push_back(entry->text);
      iter.Next();
    }
    if (raw.size() >= 200) break;
  }

  // 优先返回以词根开头的词，不足时补充同音词
  std::vector<std::string> prefixed;
  std::vector<std::string> others;
  for (const auto& w : raw) {
    if (w == stem) continue;
    if (w.rfind(stem, 0) == 0) {
      if (std::find(prefixed.begin(), prefixed.end(), w) == prefixed.end())
        prefixed.push_back(w);
    } else {
      if (std::find(others.begin(), others.end(), w) == others.end())
        others.push_back(w);
    }
    if (prefixed.size() >= 20) break;
  }
  associate_words_ = prefixed;
  for (const auto& w : others) {
    if (associate_words_.size() >= 20) break;
    associate_words_.push_back(w);
  }
  return associate_words_;
}

bool Engine::SelectAssociate(int index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (index < 0 || index >= (int)associate_words_.size()) return false;
  pending_commit_ = associate_words_[index];
  return true;
}

bool Engine::SyncUserData() {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!initialized_) return false;
  if (session_id_) {
    rime()->destroy_session(session_id_);
    session_id_ = 0;
  }
  // 同步执行部署任务（installation_update 重建 sync 目录信息、
  // backup_config_files 备份配置、user_dict_sync 导出+合并用户词典快照）
  bool ok = rime()->run_task("installation_update") != 0;
  ok = (rime()->run_task("backup_config_files") != 0) && ok;
  ok = (rime()->run_task("user_dict_sync") != 0) && ok;
  EnsureSession();
  return ok;
}

bool Engine::DeployWorkspace() {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!initialized_) return false;
  if (session_id_) {
    rime()->destroy_session(session_id_);
    session_id_ = 0;
  }
  Bool ok = rime()->deploy();
  EnsureSession();
  ResetPaging();
  return ok != 0;
}

void Engine::SetPageSize(int page_size) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (page_size > 0 && page_size != page_size_) {
    page_size_ = page_size;
    page_offset_ = 0;
  }
}

}  // namespace yuyan
