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
#include <vector>
#include <sys/stat.h>

#include <rime/config.h>
#include <rime/context.h>
#include <rime/schema.h>
#include <rime/service.h>
#include <rime/key_table.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/reverse_lookup_dictionary.h>

#include <cstdarg>

namespace yuyan {

bool g_debug_log = false;

void DebugLog(const char* fmt, ...) {
  if (!g_debug_log) return;
  FILE* f = fopen("/sdcard/yuyan/ime.log", "a");
  if (!f) return;
  va_list ap;
  va_start(ap, fmt);
  vfprintf(f, fmt, ap);
  va_end(ap);
  fclose(f);
}

namespace {

std::mutex g_mutex;

// 联想词用到的反查词典/词典缓存。创建 + Load 开销极大（pinyin.table.bin 68MB），
// 且整个过程持着 g_mutex；此前每次调用都重建，于是光标一变
// （含系统返回手势转场时的聚焦变化）就把主线程卡住数秒，
// 表现为「侧滑返回失效」。这里按方案词典名缓存，方案不变即复用。
std::string g_assoc_dict_name;
std::unique_ptr<rime::ReverseLookupDictionary> g_assoc_rev;
std::unique_ptr<rime::Dictionary> g_assoc_dict;

void ResetAssociateCache() {
  g_assoc_rev.reset();
  g_assoc_dict.reset();
  g_assoc_dict_name.clear();
}

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
      ResetAssociateCache();
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
  ResetAssociateCache();
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
    DebugLog("[bridge] session created id=%d\n", (int)session_id_);
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

void Engine::SetOptionGroup(const std::string& options, int index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  EnsureSession();
  if (!session_id_) return;
  // options 是逗号分隔的开关名（schema 里的开关组），index 为要打开的那一项
  std::vector<std::string> items;
  std::string cur;
  for (char ch : options) {
    if (ch == ',') {
      if (!cur.empty()) items.push_back(cur);
      cur.clear();
    } else {
      cur += ch;
    }
  }
  if (!cur.empty()) items.push_back(cur);
  for (size_t i = 0; i < items.size(); ++i) {
    bool on = static_cast<int>(i) == index;
    DebugLog("[switches] group %s -> %s\n", items[i].c_str(), on ? "1" : "0");
    rime()->set_option(session_id_, items[i].c_str(), on ? True : False);
  }
}

bool Engine::SetCaretPos(int pos) {
  std::lock_guard<std::mutex> lock(g_mutex);
  EnsureSession();
  if (!session_id_) return false;
  rime()->set_caret_pos(session_id_, static_cast<size_t>(pos < 0 ? 0 : pos));
  return true;
}

std::string Engine::GetSwitches() {
  std::lock_guard<std::mutex> lock(g_mutex);
  EnsureSession();
  std::string out;
  auto dbg = [](const std::string& m) { DebugLog("[switches] %s\n", m.c_str()); };
  // 照同文/引擎自己的做法：直接用 C++ 接口拿当前 session 的 schema 配置。
  // 之前用 get_status + RimeStatus 结构体（以及 schema_open/config_* 这套过时
  // C API）在真机上会踩内存、崩在函数返回处。CurrentSchema() 走字符缓冲的
  // get_current_schema 一直很稳，这里同样避开结构体。
  if (!session_id_) return out;
  rime::an<rime::Session> session =
      rime::Service::instance().GetSession(session_id_);
  if (!session) { dbg("no session"); return out; }
  rime::Schema* schema = session->schema();
  if (!schema) { dbg("no schema"); return out; }
  rime::Config* cfg = schema->config();
  if (!cfg) { dbg("no config"); return out; }
  dbg("enter schema=" + schema->schema_id());
  // 用引擎自己的 C++ 配置对象读 switches（同文也是这么读的），
  // 不再碰 config_begin_list/config_get_cstring 那套过时 C API
  rime::an<rime::ConfigList> list = cfg->GetList("switches");
  if (!list) { dbg("no switches"); return out; }
  dbg("count=" + std::to_string(list->size()));
  for (size_t i = 0; i < list->size(); ++i) {
    rime::an<rime::ConfigMap> item = rime::As<rime::ConfigMap>(list->GetAt(i));
    if (!item) continue;
    // 两种写法：普通开关用 name:（2 态），开关组用 options: [a,b,c]（多态）
    std::string key;
    std::vector<std::string> opts;
    rime::an<rime::ConfigValue> nameVal =
        rime::As<rime::ConfigValue>(item->Get("name"));
    if (nameVal && !nameVal->str().empty()) {
      key = nameVal->str();
      opts.push_back(key);
    } else {
      rime::an<rime::ConfigList> optList =
          rime::As<rime::ConfigList>(item->Get("options"));
      if (!optList) continue;
      for (size_t k = 0; k < optList->size(); ++k) {
        rime::an<rime::ConfigValue> v =
            rime::As<rime::ConfigValue>(optList->GetAt(k));
        if (!v) continue;
        std::string o = v->str();
        if (o.empty()) continue;
        if (!key.empty()) key += ",";
        key += o;
        opts.push_back(o);
      }
      if (key.empty()) continue;
    }
    // states 全部带上（原来只带 2 个，三态/四态开关表达不出来）
    std::vector<std::string> states;
    rime::an<rime::ConfigList> stList =
        rime::As<rime::ConfigList>(item->Get("states"));
    if (stList) {
      for (size_t k = 0; k < stList->size(); ++k) {
        rime::an<rime::ConfigValue> v =
            rime::As<rime::ConfigValue>(stList->GetAt(k));
        states.push_back(v ? v->str() : std::string());
      }
    }
    // 当前状态：开关组取第一个为 true 的项下标，普通开关取 0/1
    rime::Context* ctx = session->context();
    int cur = 0;
    if (ctx) {
      if (opts.size() > 1) {
        for (size_t k = 0; k < opts.size(); ++k) {
          if (ctx->get_option(opts[k])) {
            cur = static_cast<int>(k);
            break;
          }
        }
      } else {
        cur = ctx->get_option(opts[0]) ? 1 : 0;
      }
    }
    out += key;
    for (size_t k = 0; k < states.size(); ++k) {
      out += "\t";
      out += states[k];
    }
    out += "\t";
    out += std::to_string(cur);
    out += "\n";
  }
  dbg("done bytes=" + std::to_string(out.size()));
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

  // 词典/反查词典只在方案变化时构建一次（见 g_assoc_* 的注释）
  if (g_assoc_dict_name != dict_name || !g_assoc_rev || !g_assoc_dict) {
    ResetAssociateCache();
    rime::ReverseLookupDictionaryComponent rev_component;
    std::unique_ptr<rime::ReverseLookupDictionary> rev(rev_component.Create(dict_name));
    if (!rev || !rev->Load()) return associate_words_;
    rime::DictionaryComponent dict_component;
    std::unique_ptr<rime::Dictionary> dict(dict_component.Create(dict_name, dict_name, {}));
    if (!dict || !dict->Load()) return associate_words_;
    g_assoc_rev = std::move(rev);
    g_assoc_dict = std::move(dict);
    g_assoc_dict_name = dict_name;
  }

  // 反查词根读音
  std::string codes;
  if (!g_assoc_rev->ReverseLookup(stem, &codes)) return associate_words_;

  // 用读音做前缀预测式查询，取以词根开头的组词
  rime::Dictionary* dict = g_assoc_dict.get();

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
  ResetAssociateCache();  // 用户词典快照被合并，旧缓存必须失效
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
  ResetAssociateCache();  // 词典已被重新编译，旧缓存必须失效
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
