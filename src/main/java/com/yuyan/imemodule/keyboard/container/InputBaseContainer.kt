package com.yuyan.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.TextKeyboard
import com.yuyan.imemodule.data.theme.Theme

/**
 * 输入键盘容器父类，用于进行输入键盘相关处理（判断）。
 *
 * 与基类不同的是，该类内置输入键盘[TextKeyboard],用于键盘更新、按键响应操作。
 */
@SuppressLint("ViewConstructor")
open class InputBaseContainer(context: Context?, inputView: InputView) : BaseContainer(context!!, inputView) {

    //主要视图：软键盘视图。
    @JvmField
    protected var mMajorView: TextKeyboard? = null

    /**
     * 刷新按键状态，当前主要解决切换键盘时enter键状态切换
     */
    fun updateStates() {
        mMajorView!!.updateStates()
    }

    /**
     * 主题变化时把新主题交给按键视图。
     *
     * TextKeyboard.mActiveTheme 是缓存字段（只在 setSoftKeyboard 里赋值），
     * 而 TextKeyboard.setTheme() 原本没有任何调用点 —— 于是缓存键盘的按键颜色
     * 永远停留在创建时那一套。这里把它接上。
     */
    override fun updateTheme(theme: Theme) {
        mMajorView?.setTheme(theme)
    }
}
