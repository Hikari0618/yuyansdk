// yuyan_test.cc — Linux 端验证工具（不依赖 Android）
// 用法:
//   yuyan_test <用户目录> [--deploy] [--schema ID] [--type 键序列] [--assoc 文本] [--select N]
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include <rime/common.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/reverse_lookup_dictionary.h>
#include <rime/dict/table.h>
#include <rime/dict/vocabulary.h>

#include "yuyan_bridge.h"

/**
 * 从预编译 table.bin 生成 reverse.bin（供联想/反查使用）。
 * 只收单音节条目（词根读音），与 librime 源码编译时的 reverse 语义一致。
 */
static int GenReverse(const std::string& user_dir, const std::string& dict_name) {
  rime::DictionaryComponent dc;
  std::unique_ptr<rime::Dictionary> dict(dc.Create(dict_name, dict_name, {}));
  if (!dict || !dict->Load()) {
    fprintf(stderr, "[gen-reverse] cannot load dictionary '%s'\n", dict_name.c_str());
    return 1;
  }
  rime::Syllabary syllabary;
  if (!dict->primary_table()->GetSyllabary(&syllabary)) {
    fprintf(stderr, "[gen-reverse] cannot read syllabary\n");
    return 1;
  }
  rime::Vocabulary vocab;
  rime::DictEntryIterator iter;
  dict->LookupWords(&iter, "", true, 0);
  size_t total = 0;
  while (!iter.exhausted()) {
    auto e = iter.Peek();
    if (e && e->code.size() == 1) {
      auto& page = vocab[(int)e->code[0]];
      auto se = rime::New<rime::ShortDictEntry>();
      se->text = e->text;
      se->code = e->code;
      se->weight = e->weight;
      page.entries.push_back(se);
    }
    total++;
    iter.Next();
  }
  std::string target = user_dir + "/build/" + dict_name + ".reverse.bin";
  rime::ReverseDb rev{rime::path(target)};
  rime::ReverseLookupTable stems;
  uint32_t checksum = dict->primary_table()->dict_file_checksum();
  if (!rev.Build(nullptr, syllabary, vocab, stems, checksum) || !rev.Save()) {
    fprintf(stderr, "[gen-reverse] failed to build %s\n", target.c_str());
    return 1;
  }
  printf("[gen-reverse] %s: %zu entries scanned, reverse db saved (%zu syllables)\n",
         dict_name.c_str(), total, vocab.size());
  return 0;
}

static void PrintContext() {
  yuyan::ContextInfo ctx;
  if (!yuyan::Engine::Instance().GetContext(&ctx)) {
    printf("[context] <null>\n");
    return;
  }
  printf("[preedit] %s\n", ctx.composition.preedit.c_str());
  printf("[menu] page=%d/%d last=%d count=%d\n", ctx.menu.page_no,
         ctx.menu.page_size, ctx.menu.is_last_page ? 1 : 0,
         (int)ctx.menu.candidates.size());
  for (size_t i = 0; i < ctx.menu.candidates.size(); i++) {
    printf("  %zu. %s\t%s\n", i, ctx.menu.candidates[i].text.c_str(),
           ctx.menu.candidates[i].comment.c_str());
  }
}

static void PrintStatus() {
  yuyan::StatusInfo s;
  if (!yuyan::Engine::Instance().GetStatus(&s)) {
    printf("[status] <null>\n");
    return;
  }
  printf("[status] schema=%s(%s) composing=%d ascii=%d\n", s.schema_id.c_str(),
         s.schema_name.c_str(), s.is_composing ? 1 : 0, s.is_ascii_mode ? 1 : 0);
}

static void CheckCommit() {
  std::string commit;
  if (yuyan::Engine::Instance().GetCommit(&commit)) {
    printf("[commit] %s\n", commit.c_str());
  }
}

int main(int argc, char** argv) {
  if (argc < 2) {
    fprintf(stderr,
            "usage: %s <user_dir> [--deploy] [--schema ID] [--type KEYS] "
            "[--page N] [--pagedown] [--assoc TEXT] [--select N]\n",
            argv[0]);
    return 2;
  }
  std::string user_dir = argv[1];
  std::string schema;
  std::string type_keys;
  std::string assoc;
  std::string gen_reverse;
  bool deploy = false;
  int page = 0;
  bool page_down = false;
  int select = -1;

  for (int i = 2; i < argc; i++) {
    if (!strcmp(argv[i], "--deploy")) deploy = true;
    else if (!strcmp(argv[i], "--schema") && i + 1 < argc) schema = argv[++i];
    else if (!strcmp(argv[i], "--type") && i + 1 < argc) type_keys = argv[++i];
    else if (!strcmp(argv[i], "--page") && i + 1 < argc) page = atoi(argv[++i]);
    else if (!strcmp(argv[i], "--pagedown")) page_down = true;
    else if (!strcmp(argv[i], "--assoc") && i + 1 < argc) assoc = argv[++i];
    else if (!strcmp(argv[i], "--select") && i + 1 < argc) select = atoi(argv[++i]);
    else if (!strcmp(argv[i], "--gen-reverse") && i + 1 < argc) gen_reverse = argv[++i];
  }

  yuyan::Engine& engine = yuyan::Engine::Instance();
  engine.Startup(user_dir, user_dir, deploy);
  if (deploy) {
    printf("[deploy] done\n");
  }
  if (!gen_reverse.empty()) {
    return GenReverse(user_dir, gen_reverse);
  }
  PrintStatus();

  if (!schema.empty()) {
    bool ok = engine.SelectSchema(schema);
    printf("[select-schema] %s -> %d\n", schema.c_str(), ok ? 1 : 0);
    PrintStatus();
  }
  if (page > 0) engine.SetPageSize(page);

  for (char c : type_keys) {
    engine.ProcessKey(static_cast<unsigned char>(c), 0);
    CheckCommit();
  }
  if (!type_keys.empty()) PrintContext();

  if (page_down) {
    engine.ProcessKey(engine.GetKeycodeByName("Page_Down"), 0);
    PrintContext();
  }
  if (select >= 0) {
    engine.SelectCandidate(select);
    CheckCommit();
    PrintContext();
  }
  if (!assoc.empty()) {
    auto words = engine.AssociateList(assoc);
    printf("[assoc] %s -> %zu words\n", assoc.c_str(), words.size());
    for (size_t i = 0; i < words.size(); i++) {
      printf("  %zu. %s\n", i, words[i].c_str());
    }
    if (!words.empty()) {
      engine.SelectAssociate(0);
      CheckCommit();
    }
  }
  return 0;
}
