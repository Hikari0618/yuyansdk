package com.yuyan.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.yuyan.imemodule.R
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.view.preference.ManagedPreference
import splitties.dimensions.dp
import splitties.views.bottomPadding
import splitties.views.rightPadding
import kotlin.math.abs
import kotlin.math.absoluteValue

/**
 * 软键盘View集装箱
 * 所有软键盘（输入、符号、设置等）父容器View。
 */
@SuppressLint("ViewConstructor")
open class BaseContainer(@JvmField var mContext: Context, @JvmField protected var inputView: InputView) : ConstraintLayout(mContext) {
    private lateinit var mRightPaddingKey: ManagedPreference.PInt
    private lateinit var mBottomPaddingKey: ManagedPreference.PInt

    /** 「调整键盘高度」的影子覆盖层（含上/下两个把手 + 重置/确定）。
     *  拖动改高度时要把「它」的高度设成 skbHeight，让把手贴住键盘上下缘。
     *  注意：不能写成 `rootView.setLayoutParams(...)` —— 那会被 Kotlin 解析成
     *  View.getRootView() 的合成属性，改到整个输入视图上去（症状：改高度时
     *  上界被钉住不动、下界跟着手指走）。 */
    private var mHeightShadowView: View? = null

    /**
     * 更新软键盘布局
     */
    open fun updateSkbLayout(){
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = EnvironmentSingleton.instance.skbWidth
        val measuredHeight = EnvironmentSingleton.instance.skbHeight
        val widthMeasure = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val heightMeasure = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasure, heightMeasure)
    }

    /**
     * 设置键盘高度
     */
    @SuppressLint("ClickableViewAccessibility")
    fun setKeyboardHeight() {
        val rootView = LayoutInflater.from(context).inflate(R.layout.layout_ime_keyboard_height_shadow, this, false)
        this.addView(rootView)
        mHeightShadowView = rootView
        // 覆盖层四边对齐铺满整个按键容器：上/下两个把手才会稳定贴住键盘上下缘。
        // （之前拖拽时按 skbHeight 设高度，但容器实测高度是 skbHeight+候选区，
        //   例：容器 971 而 skbHeight 768 —— 两者不一致时下面的把手会跑到键盘外，
        //   用户实测「用上面的键调过、点重置后下面的键就消失了」。）
        rootView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topToTop = LayoutParams.PARENT_ID
            bottomToBottom = LayoutParams.PARENT_ID
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
        }
        rootView.findViewById<View>(R.id.ll_keyboard_height_reset).setOnClickListener { _: View? ->
            EnvironmentSingleton.instance.keyBoardHeightRatio = 0.3f
            EnvironmentSingleton.instance.initData()
            // 位移必须一并归零：重置只管高度比的话，「拖过下面的把手再点重置」
            // 键盘仍停在被抬高的位置（日志实测 rootPadB 停在 176 不归零、看起来无反应）。
            inputView.bottomPadding = 0
            inputView.rightPadding = 0
            inputView.mSkbRoot.bottomPadding = 0
            inputView.mSkbRoot.rightPadding = 0
            mBottomPaddingKey.setValue(0)
            mRightPaddingKey.setValue(0)
            com.yuyan.inputmethod.util.ImeLog.d("[kh] 重置：高度比→0.3 位移→0（含存储值）")
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            updateSkbLayout()
        }
        rootView.findViewById<View>(R.id.ll_keyboard_height_sure).setOnClickListener { removeView(rootView) }
        rootView.findViewById<View>(R.id.iv_keyboard_height_Top).setOnTouchListener { v12: View, event -> onModifyKeyboardHeightEvent(v12, event) }
        if(EnvironmentSingleton.instance.keyboardModeFloat){
            mBottomPaddingKey = if(EnvironmentSingleton.instance.isLandscape) AppPrefs.getInstance().internal.keyboardBottomPaddingLandscapeFloat
            else AppPrefs.getInstance().internal.keyboardBottomPaddingFloat
            mRightPaddingKey = if(EnvironmentSingleton.instance.isLandscape) AppPrefs.getInstance().internal.keyboardRightPaddingLandscapeFloat
            else AppPrefs.getInstance().internal.keyboardRightPaddingFloat
        } else {
            mBottomPaddingKey = AppPrefs.getInstance().internal.keyboardBottomPadding
            mRightPaddingKey = AppPrefs.getInstance().internal.keyboardRightPadding
        }
        rootView.findViewById<View>(R.id.iv_keyboard_move).setOnTouchListener { _, event -> onMoveKeyboardEvent(event) }
    }

    private val lastY = floatArrayOf(0f)
    var isHandling = false
    private fun onModifyKeyboardHeightEvent(v12: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY[0] = event.y
                // 无条件埋点：确认「上面那个把手」到底有没有收到触摸
                com.yuyan.inputmethod.util.ImeLog.d(
                    "[kh] 上把手 DOWN y=${event.y} rawY=${event.rawY}" +
                        " vH=${v12.height} vTop=${v12.top} shadowH=${mHeightShadowView?.height}" +
                        " shadowVis=${mHeightShadowView?.visibility}"
                )
            }
            MotionEvent.ACTION_MOVE -> {
                val y = event.y
                if (!isHandling && abs((y - lastY[0]).toDouble()) > dp(10)) {
                    isHandling = true
                    var rat = EnvironmentSingleton.instance.keyBoardHeightRatio
                    if (y < lastY[0]) { // 手指向上移动
                        rat += 0.01f
                    } else { // 向下移动
                        rat -= 0.01f
                    }
                    lastY[0] = y
                    EnvironmentSingleton.instance.keyBoardHeightRatio = rat
                    EnvironmentSingleton.instance.initData()
                    KeyboardLoaderUtil.instance.clearKeyboardMap()
                    KeyboardManager.instance.clearKeyboard()
                    updateSkbLayout()
                    // 覆盖层已在 setKeyboardHeight() 里铺满容器（四边对齐），
                    // 这里不再按 skbHeight 重设高度 —— 容器实测高度是 skbHeight+候选区，
                    // 按 skbHeight 设会让下面的把手跑到键盘外（「重置后下面的键消失」）。
                    com.yuyan.inputmethod.util.ImeLog.d(
                        "[kh] 拖高度 rat=$rat skbH=${EnvironmentSingleton.instance.skbHeight}" +
                            " shadowH=${mHeightShadowView?.height} shadowVis=${mHeightShadowView?.visibility}"
                    )
                    isHandling = false
                }
            }
            MotionEvent.ACTION_UP -> v12.performClick()
        }
        return true
    }

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var rightPaddingValue = 0  // 右侧边距
    private var bottomPaddingValue = 0  // 底部边距
    private var mSkbRootHeight = 0  // 键盘高度
    private var mSkbRootWidth = 0  // 键盘宽度
    private fun onMoveKeyboardEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                bottomPaddingValue = mBottomPaddingKey.getValue()
                rightPaddingValue = mRightPaddingKey.getValue()
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                mSkbRootHeight = inputView.mSkbRoot.height
                mSkbRootWidth = inputView.mSkbRoot.width
                com.yuyan.inputmethod.util.ImeLog.d(
                    "[kh] 下把手 DOWN rawY=${event.rawY} float=${EnvironmentSingleton.instance.keyboardModeFloat}" +
                        " storedPad=$bottomPaddingValue rootH=$mSkbRootHeight"
                )
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx: Float = event.rawX - initialTouchX
                val dy: Float = event.rawY - initialTouchY
                if(dx.absoluteValue > 10) {
                    rightPaddingValue -= dx.toInt()
                    rightPaddingValue = if(rightPaddingValue < 0) 0
                    else if(rightPaddingValue > inputView.width - mSkbRootWidth) {
                        inputView.width - mSkbRootWidth
                    } else rightPaddingValue
                    initialTouchX = event.rawX
                    if(EnvironmentSingleton.instance.keyboardModeFloat) {
                        inputView.rightPadding = rightPaddingValue
                    } else {
                        inputView.mSkbRoot.rightPadding = rightPaddingValue
                    }
                }
                if(dy.absoluteValue > 10 ) {
                    bottomPaddingValue -= dy.toInt()
                    bottomPaddingValue = if(bottomPaddingValue < 0) 0
                    else if(bottomPaddingValue > inputView.height - mSkbRootHeight) {
                        inputView.height - mSkbRootHeight
                    } else bottomPaddingValue
                    initialTouchY = event.rawY
                    if(EnvironmentSingleton.instance.keyboardModeFloat) {
                        inputView.bottomPadding = bottomPaddingValue
                    } else {
                        inputView.mSkbRoot.bottomPadding = bottomPaddingValue
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mRightPaddingKey.setValue(rightPaddingValue)
                mBottomPaddingKey.setValue(bottomPaddingValue)
                com.yuyan.inputmethod.util.ImeLog.d(
                    "[kh] 拖位移结束 float=${EnvironmentSingleton.instance.keyboardModeFloat}" +
                        " pad=$bottomPaddingValue right=$rightPaddingValue" +
                        " ivPad=${inputView.paddingBottom} rootPadB=${inputView.mSkbRoot.paddingBottom}" +
                        " ivH=${inputView.height} rootH=${inputView.mSkbRoot.height}"
                )
            }
        }
        return false
    }
}
