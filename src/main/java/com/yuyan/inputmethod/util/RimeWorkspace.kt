package com.yuyan.inputmethod.util

import android.content.Context
import android.os.Environment
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.utils.AssetUtils
import java.io.File

/**
 * Rime 工作区管理：
 *  - 从 /sdcard/yuyan 导入用户放置的方案文件
 *  - 扫描用户目录中的自定义方案
 *  - 生成 default.yaml（schema_list 含全部可用方案）
 */
object RimeWorkspace {

    /** 语燕专属的 sdcard 目录（勿用 /sdcard/rime：那是同文输入法等工作区，文件混入会破坏部署） */
    const val SD_RIME_DIR = "/sdcard/yuyan"

    /**
     * 内置方案 id —— **从 assets/rime 下的 *.schema.yaml 动态扫出**，不再写死清单。
     * APK 里增删方案时清单自动跟随；用户自行导入的方案（万象、雾凇…）不在其中，
     * 会作为「自定义方案」出现在输入方式菜单里。
     *
     * （旧实现是写死的 set，还额外塞了 wanxiang_pro —— 于是用户换任何别的方案都识别不出来，
     *   而 wanxiang_pro 本身又不是 App 能保证存在的。）
     */
    private var cachedBuiltinIds: List<String>? = null

    fun builtinSchemaIds(): List<String> {
        cachedBuiltinIds?.let { return it }
        val out = LinkedHashSet<String>()
        runCatching {
            val assets = com.yuyan.imemodule.application.Launcher.instance.context.assets
            val names = assets.list("rime") ?: return@runCatching
            names.sorted().forEach { name ->
                if (!name.endsWith(".schema.yaml")) return@forEach
                val fallback = name.removeSuffix(".schema.yaml")
                // 以文件里的 schema/schema_id 为准（权威），取不到才退回文件名
                val id = runCatching {
                    assets.open("rime/$name").bufferedReader().use { reader ->
                        Regex("^\\s*schema_id:\\s*['\"]?([\\w\\-.]+)")
                            .find(reader.readText())?.groupValues?.get(1)
                    }
                }.getOrNull() ?: fallback
                out.add(id)
            }
        }
        val list = out.toList()
        cachedBuiltinIds = list
        return list
    }

    val userDir: File get() = File(CustomConstant.RIME_DICT_PATH)

    /** 是否为随 APK 附带的内置方案（用户自行部署的方案如万象拼音返回 false） */
    fun isBuiltinSchema(schemaId: String): Boolean = builtinSchemaIds().contains(schemaId)

    /** 所有可部署方案（内置 + 自定义），返回 (schemaId, 名称)。
     *
     *  **清单来自引擎**（librime levers 的 get_available_schema_list，同文同款）：
     *  引擎自己扫用户目录里所有 *.schema.yaml —— 用户往 /sdcard/yuyan 丢什么方案就用什么，
     *  App 一个方案名都不写死。引擎未就绪（首次部署早期）时退回目录扫描。 */
    fun allSchemas(): List<Pair<String, String>> {
        val fromEngine = com.yuyan.inputmethod.core.Rime.availableSchemas()
        if (fromEngine.isNotEmpty()) {
            return fromEngine.map { (id, name) -> id to (if (name.isEmpty()) id else name) }
        }
        // 回退：引擎还没起来（或 levers 取不到）时按目录扫，至少别让 UI 空着
        val result = LinkedHashMap<String, String>()
        userDir.listFiles()?.filter { it.isFile && it.name.endsWith(".schema.yaml") }?.forEach { f ->
            val info = parseSchemaFile(f) ?: return@forEach
            result[info.first] = info.second
        }
        return result.map { (id, name) -> id to (if (name.isEmpty()) id else name) }
    }

    /** 仅自定义方案（排除内置），供键盘方案菜单展示。
     *
     *  **只展示「已启用」的方案**（引擎的 enabled 清单 = default.yaml / default.custom.yaml
     *  声明过的那批），绝不能用 available —— 那会把方案包里的附属派生方案一并漏进菜单：
     *  万象包里就有 wanxiang_mixedcode / wanxiang_reverse / wanxiang_abbrev / _phrase /
     *  _t9 / _t9i 等 9 个，用户根本不该在其中选。
     *  方案包用「注释掉 schema_list 条目」表达「可选、取消注释才启用」
     *  （万象 default.yaml 里 `# - schema: wanxiang_t9` 就是这个意思），所以 enabled 才是权威名单。
     *
     *  附带好处：用户自己在 default.custom.yaml 里写的方案（例如 wanxiang_english）
     *  会出现在菜单里 —— 这是旧实现（只认 default.yaml + 写死清单）做不到的。
     *  引擎未就绪时回退到 default.yaml 的声明清单。 */
    fun customSchemas(): List<Pair<String, String>> {
        val builtin = builtinSchemaIds().toSet()
        val all = allSchemas()
        val availableIds = all.map { it.first }.toSet()
        val names = all.toMap()
        // 不再拿 default.yaml 的 schema_list / enabled 做前置过滤：
        // 万象包的 default.yaml 里 wanxiang_english 等方案是【注释掉】的，
        // 而首次部署前 enabledSchemaIds() 还是空的 → 两边都取不到它，
        // 表现为「第一次部署认不出万象英语」（部署过一次后才冒出来）。
        // 这里改为：凡是引擎里【已部署可用】的方案都列出来（排除 App 内置的）。
        return all.map { it.first }
            .filter { it in availableIds && it !in builtin }
            .map { it to (names[it] ?: it) }
    }

    /** 当前 default.yaml 的 schema_list（用户/方案包维护的可用方案列表） */
    fun activeSchemaIds(): Set<String> {
        val f = File(userDir, "default.yaml")
        if (!f.exists()) return emptySet()
        val ids = mutableSetOf<String>()
        var inList = false
        f.forEachLine { line ->
            when {
                line.trim() == "schema_list:" -> inList = true
                inList && line.trimStart().startsWith("- ") -> {
                    Regex("schema:\\s*(\\S+)").find(line)?.groupValues?.get(1)?.let { ids.add(it) }
                }
                inList && line.isNotBlank() -> inList = false
            }
        }
        return ids
    }

    private fun parseSchemaFile(file: File): Pair<String, String>? {
        return try {
            var id: String? = null
            var name: String? = null
            var inSchemaBlock = false
            file.forEachLine { line ->
                if (line.trim() == "schema:") {
                    inSchemaBlock = true
                } else if (inSchemaBlock && line.isNotBlank() && !line.startsWith("  ")) {
                    inSchemaBlock = false
                }
                if (inSchemaBlock || id == null) {
                    val idMatch = Regex("^\\s*schema_id:\\s*['\"]?([\\w\\-\\.]+)").find(line)
                    if (idMatch != null && id == null) id = idMatch.groupValues[1]
                    if (inSchemaBlock) {
                        val nameMatch = Regex("^\\s*name:\\s*['\"]?([^'\"\n]+)").find(line)
                        if (nameMatch != null && name == null) name = nameMatch.groupValues[1].trim()
                    }
                }
            }
            val schemaId = id ?: return null
            schemaId to (name ?: schemaId)
        } catch (e: Exception) {
            null
        }
    }

    /** 是否已授予文件访问权限（Android 11+ 需“所有文件访问”，6-10 需存储运行时权限） */
    fun hasStoragePermission(context: android.content.Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 确保已授予文件访问权限。未授权时 Toast 提示并跳转系统授权页，返回 false。
     * Android 11+：跳“所有文件访问”授权页（部分 ROM 不支持时回退应用详情页）；
     * Android 6-10：跳应用详情页手动开启存储权限。
     */
    fun ensureStoragePermission(context: android.content.Context): Boolean {
        if (hasStoragePermission(context)) return true
        val pkg = context.packageName
        val detailIntent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$pkg")
        )
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$pkg")
                )
            } catch (e: Exception) {
                detailIntent
            }
        } else {
            detailIntent
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            context.startActivity(detailIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        android.widget.Toast.makeText(
            context,
            "需要文件访问权限才能读取 /sdcard/yuyan，请授权后重新操作",
            android.widget.Toast.LENGTH_LONG
        ).show()
        return false
    }

    /**
     * 从 /sdcard/rime 导入方案文件到 Rime 用户目录。
     * 跳过用户数据（userdb、user.yaml、build/）。
     * 返回导入摘要文本。
     */
    fun importFromSdcard(): String {
        val sdDir = File(SD_RIME_DIR)
        if (!sdDir.isDirectory) return "未找到 $SD_RIME_DIR 目录"
        var fileCount = 0
        var schemaCount = 0
        fun skip(name: String): Boolean {
            // 用户数据与运行时文件不导入；default.yaml 不跳过——它就是 rime 标准的
            // 方案列表文件（万象包自带、用户在里面 patch 方案 id），必须尊重。
            // 注意：不能笼统用 contains("userdb")——方案包的 lua 模块
            // lua/wanxiang/userdb.lua 会被误伤，导致万象 super_replacer /
            // super_sequence 等 lua 组件加载失败（module 'wanxiang/userdb' not found，
            // 间接辅助码等功能全部失效）。只过滤真正的用户词典产物。
            val isUserDbArtifact = name == "userdb" || name.endsWith(".userdb") ||
                name.endsWith(".userdb.txt")
            return isUserDbArtifact || name == "user.yaml" || name == "build" ||
                name == "installation.yaml"
        }
        fun copyRecursively(src: File, dst: File) {
            if (src.isDirectory) {
                src.listFiles()?.forEach { child ->
                    if (skip(child.name)) return@forEach
                    copyRecursively(child, File(dst, child.name))
                }
            } else {
                if (skip(src.name)) return
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                fileCount++
                if (src.name.endsWith(".schema.yaml")) schemaCount++
            }
        }
        copyRecursively(sdDir, userDir)
        // 万象等方案包的安装约定：custom/ 目录里的方案/补丁/资源文件需提升到根目录才生效
        // （rime 的 auto_patch 与方案加载只认根目录；方案包放 custom/ 是怕升级覆盖用户修改）。
        // 根目录已有同名文件时保留（尊重用户手动放置的版本）。
        val customDir = File(userDir, "custom")
        if (customDir.isDirectory) {
            customDir.listFiles()?.forEach { f ->
                val n = f.name
                val isText = n.endsWith(".yaml") || n.endsWith(".txt") || n.endsWith(".csv")
                val dst = File(userDir, n)
                if (f.isFile && isText && !dst.exists()) {
                    f.copyTo(dst)
                    fileCount++
                    if (n.endsWith(".schema.yaml")) schemaCount++
                }
            }
        }
        // 万象词库命名兼容：import_tables 引用 xxx.pro / xxx.pure（release 包命名），
        // 源码仓库中文件名为 xxx.dict.yaml —— 为缺失的变体名生成别名副本。
        //
        // ★★ 必须排除「已经是变体别名」的文件，否则会指数膨胀 ★★
        // 过滤条件是 endsWith(".dict.yaml")，而这里生成的别名（zi.pro.dict.yaml）同样满足。
        // 不排除的话，下一次部署会把别名当成源文件，再生成 zi.pro.pro.dict.yaml、
        // zi.pro.pure.dict.yaml …… 每次部署文件数翻倍，且每个都是整份词典的完整拷贝：
        //   1 次 → 3 个 / 2 次 → 7 个 / N 次 → 2^(N+1)-1 个
        // 实测设备上 dicts/ 涨到 25GB、上千个文件（历史「部署写满存储」的真正机制）。
        // 同时把历史上已经膨胀出来的垃圾文件删掉。
        val dictsDir = File(userDir, "dicts")
        if (dictsDir.isDirectory) {
            val variants = listOf("pro", "pure")
            dictsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".dict.yaml") }?.forEach { f ->
                val stem = f.name.removeSuffix(".dict.yaml")
                val parts = stem.split(".")
                // 基础名只允许 1 段（zi）；变体别名允许 2 段且第二段是 pro/pure。
                // 3 段及以上（zi.pro.pro）必然是膨胀产物 —— 删除，不再作为源文件。
                if (parts.size >= 3) {
                    if (f.delete()) fileCount++
                    return@forEach
                }
                if (parts.size == 2) return@forEach   // 已是别名，跳过
                for (variant in variants) {
                    val alias = File(dictsDir, "$stem.$variant.dict.yaml")
                    if (!alias.exists()) {
                        f.copyTo(alias)
                        fileCount++
                    }
                }
            }
        }
        return if (fileCount == 0) "$SD_RIME_DIR 中没有可导入的方案文件"
        else "已导入 $fileCount 个文件（$schemaCount 个方案）"
    }

    /**
     * 清理历史版本写进 default.custom.yaml 的「内置方案追加」段 —— 旧机制的残留。
     *
     * 旧版本会往里手写：
     *   # ---- 语燕输入法自动追加：内置方案（请勿删除本段）----
     *     schema_list/+:
     *       - schema: pinyin
     *       ...
     * 新机制改由 librime 自己写 schema_list。这段要是留着，同一个 patch 里就又有
     * `schema_list/+`（追加）又有 `schema_list`（替换）—— 又回到「谁生效看键序」的坑。
     * 所以每次部署前按标记行定位并整段删掉。
     */
    fun cleanupLegacySchemaListPlus(): Boolean {
        val f = File(userDir, "default.custom.yaml")
        if (!f.exists()) return false
        val text = f.readText()
        if (!text.contains("# ---- 语燕输入法自动追加")) return false
        runCatching { File(userDir, "default.custom.yaml.bak").writeText(text) }
        val out = ArrayList<String>()
        var skipping = false
        for (line in text.lines()) {
            if (line.contains("# ---- 语燕输入法自动追加")) {
                skipping = true
                continue
            }
            if (skipping) {
                // 标记段的内容都是缩进行（schema_list/+ 及其 - schema: 条目）；
                // 碰到非缩进非空行说明这一段结束了，该行要保留。
                val indented = line.startsWith(" ") || line.startsWith("\t")
                if (line.isNotBlank() && !indented) {
                    skipping = false
                } else {
                    continue
                }
            }
            out.add(line)
        }
        f.writeText(out.joinToString("\n"))
        return true
    }

    /**
     * 保证 App 的内置方案出现在**启用清单**里 —— 全部交给 librime 的 levers / switcher API
     * （同文输入法的机制），App 不再手写 default.custom.yaml。
     *
     * 为什么废弃旧做法：旧实现往 default.custom.yaml 里手写 `schema_list/+`，
     * 而用户/方案包自己的 `patch: schema_list:` 是【替换】语义 —— 同一个 patch 里两个键
     * 都指向 schema_list，最终生效哪个取决于引擎处理键的顺序。实测后果：用户写的
     * `wanxiang_english` 被顶掉，「选择键盘」里根本看不到这个方案。
     * 而且旧实现还写死 12 个方案 id（含 wanxiang_pro）—— 用户换雾凇等任何别的方案
     * 都进不了清单，表现为「装了方案但识别不出来」。
     *
     * 现在：
     *   可用 = 引擎 availableSchemas()（扫用户目录里所有 *.schema.yaml，含用户自己导入的）
     *   启用 = 引擎 enabledSchemaIds()（default.custom.yaml 的 schema_list 经部署合并后的结果）
     *   内置 = assets 动态扫出（builtinSchemaIds()），不再写死
     *   目标 = (启用 ∪ 内置) ∩ 可用
     * 只有清单真的变化时才调 setEnabledSchemas()，返回 true 表示**需要重新部署**。
     *
     * 注意：librime 的 get_selected_schema_list 读的是**部署产物**（staging 里 merged 的
     * default.yaml），所以本函数要在部署之后调用才读得到有效清单（同文也是
     * setEnabledSchemata 之后再 deploy 一次）。宿主机 yuyan_test 已实测：
     *   available 不依赖部署；setEnabledSchemas 能正确写出 default.custom.yaml；
     *   enabled 必须先部署过才非空。
     */
    fun ensureBuiltinSchemasEnabled(): Boolean {
        val available = com.yuyan.inputmethod.core.Rime.availableSchemas().map { it.first }
        if (available.isEmpty()) return false   // 引擎未就绪：不能冒险覆盖用户的清单
        val availableSet = available.toSet()
        // 三方来源合并（只增不减，任何一方都不会被丢掉）：
        //   base    = 用户目录 default.yaml 的 schema_list（方案包/用户声明的清单）
        //   enabled = 已生效清单（部署后才读得到；首次部署前为空）
        //   builtin = App 内置方案（assets 动态扫出）
        // 关键：必须带上 base —— 首次部署时 enabled 还是空的，只按 enabled∪内置 写会把
        // 用户/方案包声明的方案整段挤掉（旧代码那个「schemas 丢失」的坑就是这么来的）。
        val base = activeSchemaIds()
        val enabled = com.yuyan.inputmethod.core.Rime.enabledSchemaIds()

        val target = LinkedHashSet<String>()
        base.forEach { if (it in availableSet) target.add(it) }
        enabled.forEach { if (it in availableSet) target.add(it) }
        builtinSchemaIds().forEach { if (it in availableSet) target.add(it) }

        if (target.isEmpty()) return false
        if (target.toList() == enabled.toList()) return false   // 没变化就别动文件
        return com.yuyan.inputmethod.core.Rime.setEnabledSchemas(target.toList())
    }

    /**
     * 把 assets 里的 rime 资源复制到用户目录 —— 需要时（版本号变化）才做。
     *
     * 复制前会保护「用户状态文件」（installation.yaml / user.yaml / default.yaml），
     * 因为 copyFileOrDir 是 overwrite=true 的全量覆盖：
     *   installation.yaml → sync_dir / installation_id
     *   user.yaml         → 用户 profile
     *   default.yaml      → schema_list 等自定义
     *
     * 调用点有两处（都带同一个版本闸门，先到先做、后到自动跳过）：
     *   1. RimeDeployUtils.deploy() 开头 —— 主路径，部署是用户主动触发的，
     *      时机可控、结果写进部署日志（推荐在这里发生）
     *   2. Launcher.onInitDataChildThread() —— 兜底，避免用户从未部署过时
     *      assets 完全没被复制（此时键盘本身就不可用）
     *
     * @return true 表示本次真的做了复制
     */
    fun copyAssetsIfNeeded(context: Context): Boolean {
        val stored = AppPrefs.getInstance().internal.dataDictVersion.getValue()
        if (stored >= CustomConstant.CURRENT_RIME_DICT_DATA_VERSIOM) return false

        val rimeDir = File(CustomConstant.RIME_DICT_PATH)
        val userStateFiles = listOf("installation.yaml", "user.yaml", "default.yaml")
        val backup = userStateFiles.associateWith { name ->
            File(rimeDir, name).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
        }
        AssetUtils.copyFileOrDir(context, "rime", "", CustomConstant.RIME_DICT_PATH, true)
        AssetUtils.copyFileOrDir(context, "hw", "", CustomConstant.HW_DICT_PATH, true)
        backup.forEach { (name, text) ->
            if (text != null) runCatching { File(rimeDir, name).writeText(text) }
        }
        AppPrefs.getInstance().internal.dataDictVersion
            .setValue(CustomConstant.CURRENT_RIME_DICT_DATA_VERSIOM)
        return true
    }

    /**
     * 保证工作区的 default.yaml 里包含全部内置方案。
     *
     * 原实现是 `if (target.exists()) return false` —— 一旦用户目录已有 default.yaml
     * （用户自定义，或万象方案包自带），App 就再也不碰它。后果：内置方案只要不在清单里
     * 就永远修不回来（实测 pinyin / t9_pinyin / english / 七种双拼全部无法加载）。
     *
     * 现在改为「补齐」而非「覆盖」：保留用户原有条目与顺序（用户方案仍排在前），
     * 只把缺失的内置方案追加到 schema_list 末尾；文件不存在时才新建。
     * 只动 schema_list，其它字段（switcher/punctuator/...）原样保留。
     */
    fun writeDefaultYamlIfNeeded(): Boolean {
        val target = File(userDir, "default.yaml")
        val template = readAssetDefaultYaml()
        var changed = false

        if (!target.exists()) {
            // 新建时用 assets 的【完整模板】（含 punctuator / switcher / menu / key_binder /
            // recognizer / ascii_composer），再补内置方案。
            // 原实现只写 config_version + schema_list 两行 —— 后果见 ensureTopLevelSections 注释。
            target.parentFile?.mkdirs()
            target.writeText(template ?: "config_version: \"1\"\nschema_list:\n")
            changed = true
        } else if (template != null) {
            // 已存在：补齐模板里有、用户文件里缺的顶层段（只补缺失的，不覆盖已有内容）
            if (ensureTopLevelSections(target, template)) changed = true
        }

        val lines = target.readText().lines()
        val existing = HashSet<String>()
        var inList = false
        var listStart = -1
        var listEnd = -1
        lines.forEachIndexed { i, line ->
            val t = line.trim()
            if (t == "schema_list:") {
                inList = true
                listStart = i
                return@forEachIndexed
            }
            if (inList) {
                when {
                    t.startsWith("- ") -> {
                        existing.add(t.removePrefix("- ").removePrefix("schema:").trim())
                        listEnd = i
                    }
                    // 列表结束（遇到下一个顶层键或非缩进内容）
                    t.isNotEmpty() && !t.startsWith("#") -> inList = false
                }
            }
        }

        // 内置清单动态来自 assets（不再写死）；用户自己的方案不由这里负责
        val missing = builtinSchemaIds().filterNot { existing.contains(it) }
        if (missing.isEmpty()) return changed

        val out = ArrayList(lines)
        if (listStart < 0) {
            // 完全没有 schema_list：追加一个块
            out.add("schema_list:")
            missing.forEach { out.add("  - schema: $it") }
        } else {
            val insertAt = if (listEnd >= 0) listEnd + 1 else listStart + 1
            missing.forEachIndexed { k, id -> out.add(insertAt + k, "  - schema: $id") }
        }
        target.writeText(out.joinToString("\n") + "\n")
        return true
    }

    /**
     * 把模板里有、目标文件里缺的「顶层段」补进目标文件。
     *
     * 【为什么必须做】stroke / pinyin 等方案写的是 `punctuator: import_preset: default`，
     * librime 的 legacy_preset_config_plugin 会取 `default.yaml` 的 punctuator 段
     * （`Reference{"default","punctuator",false}` —— **非 optional**）：
     * 取不到就 `failed to include section default:punctuator`，
     * 整个方案构建失败（实测：笔画键盘按键只能当普通字母上屏、且部署很慢）。
     * menu / navigator / selector 那几个是 optional，缺了不报错但也应补齐。
     *
     * 而本函数早期新建 default.yaml 时只写 config_version + schema_list 两行，
     * 这些段全缺；再加上复制 assets 时会「保护用户 default.yaml」（保留残缺文件），
     * 问题就永久存在。这里按 assets 模板补齐缺失的段，不动用户已有内容。
     */
    private fun ensureTopLevelSections(target: File, template: String): Boolean {
        val cur = target.readText()
        val curKeys = cur.lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith(" ") && !it.startsWith("#") && it.contains(":") }
            .map { it.substringBefore(":").trim() }
            .toSet()
        val missing = splitTopLevelBlocks(template)
            .filter { it.first != "schema_list" && !curKeys.contains(it.first) }
        if (missing.isEmpty()) return false
        val sb = StringBuilder(cur.trimEnd('\n'))
        missing.forEach { (_, body) -> sb.append("\n").append(body.trimEnd('\n')).append("\n") }
        target.writeText(sb.toString())
        return true
    }

    /** 把 YAML 文本按顶层段切开：返回 (键, 该段完整原文) 列表 */
    private fun splitTopLevelBlocks(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var key: String? = null
        val body = StringBuilder()
        for (line in text.lines()) {
            val isTop = line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("#") && line.contains(":")
            if (isTop) {
                key?.let { out.add(it to body.toString()) }
                key = line.substringBefore(":").trim()
                body.setLength(0)
                body.append(line).append("\n")
            } else if (key != null) {
                body.append(line).append("\n")
            }
        }
        key?.let { out.add(it to body.toString()) }
        return out
    }

    /** 读取 assets 里的 default.yaml 模板（读不到返回 null，调用方退回旧行为） */
    private fun readAssetDefaultYaml(): String? = try {
        Launcher.instance.context.assets.open("rime/default.yaml")
            .bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }
}
