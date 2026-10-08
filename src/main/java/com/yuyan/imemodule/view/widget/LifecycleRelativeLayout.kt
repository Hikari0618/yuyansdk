package com.yuyan.imemodule.view.widget

import android.content.Context
import android.view.View
import android.widget.RelativeLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

open class LifecycleRelativeLayout(context: Context)  : RelativeLayout(context), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        initParentViewModelIfNeeded()
    }

    open fun initParentViewModelIfNeeded() {}

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 不要置为 DESTROYED：IME 的输入视图/候选视图会被框架临时摘下再挂回
        // （旋转时 setInputView/setCandidatesView 内部就是 removeView + addView），
        // 一旦 DESTROYED 会有两个后果：
        //   1) LiveData 观察者被永久注销（candidatesLiveData.observe 只在 init 块注册一次）
        //      → 引擎里就算有组合串，候选区/待编辑区也永远不刷新，
        //      表现为「打字母待编辑栏不出字母，按一下菜单键才出现」；
        //   2) LifecycleRegistry 无法从 DESTROYED 恢复，重新 attach 时的 RESUMED 也无效。
        // 置为 CREATED：观察者保留（暂停派发），重新 attach 后自动恢复。
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (visibility == VISIBLE) {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        } else {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }

    override val lifecycle: Lifecycle = lifecycleRegistry
}