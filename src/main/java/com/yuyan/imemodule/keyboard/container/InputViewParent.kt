package com.yuyan.imemodule.keyboard.container

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import com.yuyan.imemodule.singleton.EnvironmentSingleton

/**
 * 根布局集装箱，
 *
 * 此为所有键盘布局共享对象。
 *
 * 该类用于键盘切换是刷新操作。如从九宫格（中文）切换到全键盘（英文），键盘使用缓存对象，需要进行九宫格父布局清除及全键盘父布局设置工作。
 */
class InputViewParent @JvmOverloads constructor(context: Context?, attrs: AttributeSet? = null, defStyleAttr: Int = 0, defStyleRes: Int = 0) : RelativeLayout(context, attrs, defStyleAttr, defStyleRes) {
    private var mLastContainer: View? = null

    /**
     * 强制按按键区设计高度测量。
     *
     * 实测异常：layoutParams.height 已经钉成 skbHeight(720)，但 measuredHeight 仍是 653
     * （父容器 653 却装着 720 的子视图 → 子被裁，键位网格按 720 绘制 → 最下面一行被切，
     * 用户描述为「打字时下缘被裁」）。它里面装的所有键盘容器（BaseContainer/ConstraintLayout）
     * onMeasure 都钉在 skbHeight 上，所以这里也钉成同一个值，父子必然一致。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val env = EnvironmentSingleton.instance
        val kid = if (childCount > 0) getChildAt(0) else null
        com.yuyan.inputmethod.util.ImeLog.d(
            "[parent] spec=${MeasureSpec.toString(heightMeasureSpec)} skbH=${env.skbHeight}" +
                " measuredBefore=$measuredHeight kids=$childCount" +
                " kid=${kid?.javaClass?.simpleName}/${kid?.measuredHeight}"
        )
        if (env.skbHeight > 0) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(env.skbWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(env.skbHeight, MeasureSpec.EXACTLY)
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    fun showView(child: View?) {
        if (child == null) return
        (child as? InputBaseContainer)?.updateStates()
        if (child.parent == null) {
            super.addView(child)
        } else if (child.parent !== this) {
            (child.parent as ViewGroup).removeView(child)
            super.addView(child)
        }
        if (child === mLastContainer) return
        child.visibility = VISIBLE
        child.requestLayout()
        if (mLastContainer != null) {
            hideView(mLastContainer!!)
        }
        mLastContainer = child
    }

    private fun hideView(child: View) {
        child.visibility = GONE
    }
}
