package com.yuyan.imemodule.keyboard

import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.flower.FlowerTypefaceMode
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.prefs.behavior.SymbolMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.AppUtil
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.imemodule.keyboard.container.CandidatesContainer
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.keyboard.container.SettingsContainer
import com.yuyan.imemodule.keyboard.container.SymbolContainer
import com.yuyan.inputmethod.core.Kernel

fun onSettingsMenuClick(inputView: InputView, skbMenuMode: SkbMenuMode) {
    when (skbMenuMode) {
        SkbMenuMode.Emojicon, SkbMenuMode.Emoticon -> {
            val symbolType = if(skbMenuMode == SkbMenuMode.Emoticon) SymbolMode.Emoticon else SymbolMode.Emojicon
            if((KeyboardManager.instance.currentContainer as? SymbolContainer)?.getMenuMode() == symbolType){
                KeyboardManager.instance.switchKeyboard()
            } else {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SYMBOL)
                inputView.mSkbCandidatesBarView.showEmoji()
                (KeyboardManager.instance.currentContainer as? SymbolContainer)?.setEmojisView(symbolType)
            }
        }
        SkbMenuMode.SwitchKeyboard -> {
            KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
            (KeyboardManager.instance.currentContainer as? SettingsContainer)?.showSkbSelelctModeView()
        }
        SkbMenuMode.KeyboardHeight -> {
            KeyboardManager.instance.switchKeyboard()
            KeyboardManager.instance.currentContainer!!.setKeyboardHeight()
        }
        SkbMenuMode.DarkTheme -> {
            val theme = (if (ThemeManager.activeTheme.isDark) ThemeManager.prefs.lightModeTheme else ThemeManager.prefs.darkModeTheme).getValue()
            ThemeManager.setNormalModeTheme(theme)
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.Feedback -> {
            AppUtil.launchSettingsToKeyboard(Launcher.instance.context)
        }
        SkbMenuMode.NumberRow -> {
            val abcNumberLine = AppPrefs.getInstance().keyboardSetting.abcNumberLine.getValue()
            AppPrefs.getInstance().keyboardSetting.abcNumberLine.setValue(!abcNumberLine)
            //更换键盘模式后 重亲加载键盘
            KeyboardLoaderUtil.instance.changeSKBNumberRow()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.JianFan -> {
            val chineseFanTi = AppPrefs.getInstance().input.chineseFanTi.getValue()
            AppPrefs.getInstance().input.chineseFanTi.setValue(!chineseFanTi)
            Kernel.nativeUpdateImeOption()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.LockEnglish -> {
            val keyboardLockEnglish = AppPrefs.getInstance().keyboardSetting.keyboardLockEnglish.getValue()
            AppPrefs.getInstance().keyboardSetting.keyboardLockEnglish.setValue(!keyboardLockEnglish)
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.SymbolShow -> {
            val keyboardSymbol = ThemeManager.prefs.keyboardSymbol.getValue()
            ThemeManager.prefs.keyboardSymbol.setValue(!keyboardSymbol)
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.Mnemonic -> {
            val keyboardMnemonic = AppPrefs.getInstance().keyboardSetting.keyboardMnemonic.getValue()
            AppPrefs.getInstance().keyboardSetting.keyboardMnemonic.setValue(!keyboardMnemonic)
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.EmojiInput -> {
            val emojiInput = AppPrefs.getInstance().input.emojiInput.getValue()
            AppPrefs.getInstance().input.emojiInput.setValue(!emojiInput)
            Kernel.nativeUpdateImeOption()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.Handwriting -> AppUtil.launchSettingsToHandwriting(Launcher.instance.context)
        SkbMenuMode.Settings -> AppUtil.launchSettings(Launcher.instance.context)
        SkbMenuMode.OneHanded -> {
            AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.setValue(!AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue())
            EnvironmentSingleton.instance.initData()
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.FlowerTypeface -> {
            CustomConstant.flowerTypeface = if(CustomConstant.flowerTypeface == FlowerTypefaceMode.Disabled) FlowerTypefaceMode.Mars else FlowerTypefaceMode.Disabled
            inputView.mSkbCandidatesBarView.showFlowerTypeface()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.FloatKeyboard -> {
            val keyboardModeFloat = EnvironmentSingleton.instance.keyboardModeFloat
            EnvironmentSingleton.instance.keyboardModeFloat = !keyboardModeFloat
            EnvironmentSingleton.instance.initData()
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.ClipBoard, SkbMenuMode.Phrases -> {
            val currentContainer = KeyboardManager.instance.currentContainer as? ClipBoardContainer
            if(currentContainer != null){
                if(currentContainer.getMenuMode() == skbMenuMode) KeyboardManager.instance.switchKeyboard()
                else currentContainer.showClipBoardView(skbMenuMode)
            } else {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.ClipBoard)
                (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.showClipBoardView(skbMenuMode)
            }
            inputView.updateCandidateBar()
        }
        SkbMenuMode.Custom -> {
            KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
            (KeyboardManager.instance.currentContainer as? SettingsContainer)?.enableDragItem(true)
        }
        SkbMenuMode.CloseSKB -> {
            inputView.requestHideSelf()
        }
        SkbMenuMode.SettingsMenu -> {
            if (KeyboardManager.instance.isInputKeyboard) {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
                (KeyboardManager.instance.currentContainer as? SettingsContainer)?.showSettingsView()
                inputView.updateCandidateBar()
            } else {
                KeyboardManager.instance.switchKeyboard()
            }
        }
        SkbMenuMode.CandidatesMore -> {
            KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.CANDIDATES)
            (KeyboardManager.instance.currentContainer as? CandidatesContainer)?.showCandidatesView()
        }
        SkbMenuMode.LockClipBoard -> {
            CustomConstant.lockClipBoardEnable = !CustomConstant.lockClipBoardEnable
        }
        SkbMenuMode.TextEdit -> {
            InputModeSwitcher.switchModeForUserKey(
                if(InputModeSwitcher.isTextEditSkb) InputModeSwitcher.USER_KEYCODE_RETURN
                else InputModeSwitcher.USER_KEYCODE_TEXTEDIT)
        }
        SkbMenuMode.RimeSync -> {
            showToast("正在同步用户数据…")
            Thread {
                val result = com.yuyan.inputmethod.util.RimeSyncUtils.sync()
                showToast(result)
            }.start()
        }
        SkbMenuMode.RimeDeploy -> {
            showToast("正在部署，期间输入法暂不可用…")
            Thread {
                val result = com.yuyan.inputmethod.util.RimeDeployUtils.deploy()
                showToast(result)
            }.start()
        }
        SkbMenuMode.FuzzyPinyin -> {
            showFuzzyPinyinDialog()
        }
        else ->{}
    }
}

private fun showToast(message: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(Launcher.instance.context, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 模糊音设置对话框：自由开关模糊音，保存后自动重新部署生效 */
fun showFuzzyPinyinDialog() {
    val context = Launcher.instance.context
    val schemaId = com.yuyan.imemodule.prefs.AppPrefs.getInstance().internal.pinyinModeRime.getValue()
    if (schemaId == com.yuyan.imemodule.application.CustomConstant.SCHEMA_ZH_HANDWRITING) {
        showToast("手写模式不支持模糊音设置")
        return
    }
    val options = com.yuyan.inputmethod.util.FuzzyPinyinUtils.OPTIONS
    val labels = options.map { it.label }.toTypedArray()
    val checked = options.map { it.key in com.yuyan.inputmethod.util.FuzzyPinyinUtils.getEnabled(schemaId) }.toBooleanArray()
    android.app.AlertDialog.Builder(context)
        .setTitle("模糊音设置（$schemaId）")
        .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
        .setPositiveButton("保存并部署") { _, _ ->
            val enabled = options.filterIndexed { i, _ -> checked[i] }.map { it.key }.toSet()
            com.yuyan.inputmethod.util.FuzzyPinyinUtils.save(schemaId, enabled)
            showToast("已保存模糊音，正在重新部署…")
            Thread {
                val result = com.yuyan.inputmethod.util.RimeDeployUtils.deploy()
                showToast(result)
            }.start()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}