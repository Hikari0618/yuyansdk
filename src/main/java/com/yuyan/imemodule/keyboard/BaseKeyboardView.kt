package com.yuyan.imemodule.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.entity.keyboard.SoftKeyboard
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.KeyboardSymbolSlideUpMod
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.view.popup.PopupComponent
import com.yuyan.imemodule.view.popup.PopupComponent.Companion.get
import java.util.LinkedList
import java.util.Queue
import kotlin.math.abs
import kotlin.math.absoluteValue

/**
 * 键盘根布局
 *
 * 由于之前键盘体验问题，当前基于Android内置键盘[android.inputmethodservice.KeyboardView]进行调整开发。
 */
open class BaseKeyboardView(mContext: Context?) : View(mContext) {
    private val popupComponent: PopupComponent = get()
    protected var mSoftKeyboard: SoftKeyboard? = null
    private var mCurrentKey: SoftKey? = null
    private var mGestureDetector: GestureDetector? = null
    protected var mLongPressKey = false
    private var mAbortKey = false
    private var mHandler: Handler? = null
    protected var mDrawPending = false
    protected var mService: InputView? = null
    fun setResponseKeyEvent(service: InputView) {
        mService = service
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        initGestureDetector()
        if (mHandler == null) {
            mHandler = object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        MSG_REPEAT -> {
                            if (repeatKey()) {
                                val repeat = Message.obtain(this, MSG_REPEAT)
                                sendMessageDelayed(repeat, REPEAT_INTERVAL)
                            }
                        }

                        MSG_LONGPRESS -> openPopupIfRequired()
                    }
                }
            }
        }
    }

    private fun initGestureDetector() {
        if (mGestureDetector == null) {
            mGestureDetector = GestureDetector(context, object : SimpleOnGestureListener() {
                override fun onScroll(downEvent: MotionEvent?, currentEvent: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    if(mLongPressKey && mCurrentKey?.getkeyLabel()?.isNotBlank() == true){
                        popupComponent.changeFocus(currentEvent.x - downEvent!!.x, currentEvent.y - downEvent.y)
                    } else {
                        dispatchGestureEvent(downEvent, currentEvent, distanceX, distanceY)
                    }
                    return true
                }
                override fun onDown(e: MotionEvent): Boolean {
                    currentDistanceY = 0f
                    return super.onDown(e)
                }
            })
            mGestureDetector!!.setIsLongpressEnabled(false)
        }
    }

    fun invalidateKey() {
        mDrawPending = true
        invalidate()
    }

    open fun onBufferDraw() {}
    private fun openPopupIfRequired() {
        if(mCurrentKey != null) {
            val softKey = mCurrentKey!!
            val keyboardSymbol = ThemeManager.prefs.keyboardSymbol.getValue()
            if (softKey.getkeyLabel().isNotBlank() && softKey.code != InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI ) {
                val keyLabel = if (InputModeSwitcher.isLower) softKey.keyLabel.lowercase() else softKey.keyLabel
                val designPreset = setOf("，", "。", ",", ".")
                val smallLabel = if(designPreset.any { it == keyLabel } || !keyboardSymbol) "" else softKey.getmKeyLabelSmall()
                val bounds = Rect(softKey.mLeft, softKey.mTop, softKey.mRight, softKey.mBottom)
                popupComponent.showKeyboard(keyLabel, smallLabel, bounds)
                mLongPressKey = true
            } else if (softKey.code == InputModeSwitcher.USER_KEYCODE_LANG ||
                softKey.code == InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI ||
                    softKey.code == KeyEvent.KEYCODE_SHIFT_LEFT ||
                softKey.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION ||
                softKey.code == KeyEvent.KEYCODE_DEL || softKey.code == KeyEvent.KEYCODE_ENTER){
                val bounds = Rect(softKey.mLeft, softKey.mTop, softKey.mRight, softKey.mBottom)
                popupComponent.showKeyboardMenu(softKey, bounds, currentDistanceY)
                mLongPressKey = true
            } else {
                mLongPressKey = true
                mAbortKey = true
                dismissPreview()
            }
        }
    }

    private var motionEventQueue: Queue<MotionEvent> = LinkedList()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(me: MotionEvent): Boolean {
        var result = false
        if (mGestureDetector!!.onTouchEvent(me)) {
            return true
        }
        when (val action = me.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = me.actionIndex
                val x = me.getX(actionIndex)
                val y = me.getY(actionIndex)
                val now = me.eventTime
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, me.metaState)
                motionEventQueue.offer(down)
                result = onModifiedTouchEvent(me)
                val keyIndex = getKeyIndices(x.toInt(), y.toInt())
                if(keyIndex != null) {
                    DevicesUtils.tryPlayKeyDown(keyIndex.code)
                    DevicesUtils.tryVibrate(this)
                }
                showPreview(keyIndex)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val now = me.eventTime
                val act = if(action == MotionEvent.ACTION_CANCEL)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                while (!motionEventQueue.isEmpty()) {
                    val first = motionEventQueue.poll()
                    if(first!= null) {
                        result = onModifiedTouchEvent(MotionEvent.obtain(now, now, act, first.x, first.y, me.metaState))
                    }
                }
                dismissPreview()
            }
            else -> {
                result = onModifiedTouchEvent(me)
            }
        }
        return result
    }

    private fun onModifiedTouchEvent(me: MotionEvent): Boolean {
        when (me.action) {
            MotionEvent.ACTION_DOWN -> {
                mCurrentKey = getKeyIndices(me.x.toInt(), me.y.toInt())
                mAbortKey = false
                mLongPressKey = false
                mSwipeUpFired = false
                mDelRevertFired = false
                // 手指位移必须跟着每次触摸清零：否则上一次手势留下的旧值会带到下一次长按，
                // 新的长按一上来就以为「手指还停在滑动位置」，重复逻辑一直走恢复、删不动字
                // （用户实测：触发过一次恢复后，松开再长按只会删一个字）。
                relDistanceX = 0f
                relDistanceY = 0f
                if(mCurrentKey != null){
                    if (mCurrentKey!!.repeatable()) {
                        val msg = mHandler!!.obtainMessage(MSG_REPEAT)
                        mHandler!!.sendMessageDelayed(msg, REPEAT_START_DELAY)
                    }
                    val msg = mHandler!!.obtainMessage(MSG_LONGPRESS)
                    mHandler!!.sendMessageDelayed(msg, AppPrefs.getInstance().keyboardSetting.longPressTimeout.getValue().toLong())
                }
            }
            MotionEvent.ACTION_UP -> {
                mCurrentKey?.onReleased()
                mCurrentKey = getKeyIndices(me.x.toInt(), me.y.toInt())
                removeMessages()
                if (!mAbortKey && !mLongPressKey && mCurrentKey != null) {
                    mService?.responseKeyEvent(mCurrentKey!!)
                }
                currentDistanceX = 0F
                currentDistanceY = 0F
                relDistanceX = 0f
                relDistanceY = 0f
            }
            MotionEvent.ACTION_CANCEL -> {
                removeMessages()
                currentDistanceX = 0F
                currentDistanceY = 0F
                relDistanceX = 0f
                relDistanceY = 0f
            }
        }
        return true
    }

    private var lastEventX:Float = -1f
    private var lastEventY:Float = -1f
    private var currentDistanceY:Float = 0f
    private var currentDistanceX:Float = 0f
    // 相对「按下点」的累计位移（手势判断用）。currentDistanceX/Y 只是本次滑动事件的增量，
    // 几像素的抖动就足以让它落到某一侧，不能用来判断手势方向。
    private var relDistanceX:Float = 0f
    private var relDistanceY:Float = 0f
    // 一次长按里「恢复」手势只触发一次：触发后手指通常还停在原位，
    // 若继续按位移判定为手势，重复逻辑就会一直走恢复、删字停不下来
    // （用户实测：触发一次恢复后继续长按就不再删字，要右滑/下滑才恢复删除）。
    private var mDelRevertFired:Boolean = false
    private var lastEventActionIndex:Int = 0
    // 一次触摸只允许触发一次上滑：否则手指连续滑动时每个 ACTION_MOVE 都会再发一次键
    // （日志里 47ms 内连发 3 个 ` 就是这么来的）
    private var mSwipeUpFired:Boolean = false
    // 处理手势滑动
    private fun dispatchGestureEvent(downEvent: MotionEvent?, currentEvent: MotionEvent, distanceX: Float, distanceY: Float) : Boolean {
        var result = false
        val currentX = currentEvent.x
        val currentY = currentEvent.y
        currentDistanceX = distanceX
        currentDistanceY = distanceY
        if (downEvent != null) {
            relDistanceX = currentX - downEvent.x
            relDistanceY = currentY - downEvent.y
        }
        val keyLableSmall = mCurrentKey?.getmKeyLabelSmall()
        if(currentEvent.pointerCount > 1) return false    // 避免多指触控导致上屏
        if(lastEventX < 0 || lastEventActionIndex != currentEvent.actionIndex) {   // 避免多指触控导致符号上屏
            lastEventX = currentX
            lastEventY = currentY
            lastEventActionIndex = currentEvent.actionIndex
            return false
        }
        val relDiffX = abs(currentX - lastEventX)
        val relDiffY = abs(currentY - lastEventY)
        val isVertical = relDiffX * 1.5 < relDiffY  //横向、竖向滑动距离接近时，优先触发左右滑动
        val symbolSlideUp = EnvironmentSingleton.instance.heightForCandidatesArea / when(ThemeManager.prefs.symbolSlideUpMod.getValue()){
            KeyboardSymbolSlideUpMod.SHORT -> 3;KeyboardSymbolSlideUpMod.MEDIUM -> 2;else -> 1
        }
        val spaceSwipeMoveCursorSpeed = AppPrefs.getInstance().keyboardSetting.spaceSwipeMoveCursorSpeed.getValue()
        if (!isVertical && relDiffX > spaceSwipeMoveCursorSpeed) {  // 左右滑动
            val isSwipeKey = mCurrentKey?.code == KeyEvent.KEYCODE_SPACE || mCurrentKey?.code == KeyEvent.KEYCODE_0
            if(mCurrentKey?.code == KeyEvent.KEYCODE_DEL && distanceX > 20){// 左滑删除
                removeMessages()
                mAbortKey = true
                mService?.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_CLEAR))
            } else if (isSwipeKey && AppPrefs.getInstance().keyboardSetting.spaceSwipeMoveCursor.getValue()) {  // 左右滑动
                removeMessages()
                lastEventX = currentX
                lastEventY = currentY
                mAbortKey = true
                mService!!.responseKeyEvent(SoftKey(code = if (distanceX > 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT))
                result = true
            }
        } else if(keyLableSmall?.isNotBlank() == true){
            if (!mSwipeUpFired && isVertical && distanceY > 0 && relDiffY > symbolSlideUp && ThemeManager.prefs.keyboardSymbol.getValue()){   // 向上滑动
                lastEventX = currentX
                lastEventY = currentY
                lastEventActionIndex = currentEvent.actionIndex
                mLongPressKey = true
                mSwipeUpFired = true
                removeMessages()
                mService?.responseLongKeyEvent(Pair(PopupMenuMode.Text, keyLableSmall))
                result = true
            }
        } else {  // 菜单
            if (!mSwipeUpFired && isVertical && relDiffY > symbolSlideUp * 2) {   // 向上滑动
                lastEventX = currentX
                lastEventY = currentY
                lastEventActionIndex = currentEvent.actionIndex
                mLongPressKey = true
                popupComponent.onGestureEvent(distanceY)
            } else {
                if(downEvent != null) popupComponent.changeFocus(currentEvent.x - downEvent.x, currentEvent.y - downEvent.y)
            }
        }
        return result
    }

    private fun repeatKey(): Boolean {
        if (mCurrentKey != null && mCurrentKey!!.repeatable()) {
            if(mCurrentKey!!.code == KeyEvent.KEYCODE_DEL && mLongPressKey) {
                    // 「长按退格 + 上滑/左滑 = 恢复刚删掉的字」是个手势，但原来用
                    // currentDistanceX >= -2 / currentDistanceY > 0 判断 —— 那只是本次滑动事件的
                    // 增量（几像素），长按时手指自然抖动就会翻到「上滑/左滑」一侧，
                    // 于是删掉的字被 commitText 一个个送回来（用户实测：长按一会儿文字又回来了）。
                    // 改用相对按下点的累计位移 + 阈值；且一次长按只触发一次恢复 ——
                    // 否则触发后手指仍停在原位，重复逻辑会一直判为手势，删字就停下来了
                    // （用户实测：触发一次恢复后继续长按不再删字，要右滑/下滑才恢复删除）。
                    val delGestureThreshold = 30f
                    val revertRequested = if (relDistanceX.absoluteValue >= relDistanceY.absoluteValue) {
                        relDistanceX < -delGestureThreshold
                    } else {
                        relDistanceY < -delGestureThreshold
                    }
                    if (revertRequested && !mDelRevertFired) {
                        mDelRevertFired = true
                        mService?.responseLongKeyEvent(Pair(PopupMenuMode.Revertl,  ""))
                    } else {
                        // 手指滑回原位后重新武装手势，可以再次触发恢复
                        if (!revertRequested) mDelRevertFired = false
                        mService?.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_DEL))
                    }
            } else {
                mService?.responseKeyEvent(
                    if (mCurrentKey!!.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION) {
                        SoftKey(
                            if (currentDistanceX.absoluteValue >= currentDistanceY.absoluteValue) {
                                if (currentDistanceX > 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                            } else {
                                if (currentDistanceY < 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                            }
                        )
                    } else mCurrentKey!!
                )
            }
        }
        return true
    }

    private fun removeMessages() {
        if (mHandler != null) {
            mHandler!!.removeMessages(MSG_REPEAT)
            mHandler!!.removeMessages(MSG_LONGPRESS)
            mHandler!!.removeMessages(MSG_SHOW_PREVIEW)
        }
    }

    public override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closing()
    }

    /**
     * 显示短按气泡
     */
    private fun showPreview(key: SoftKey?) {
        mCurrentKey?.onReleased()
        if (key != null) {
            key.onPressed()
            showBalloonText(key)
        } else {
            popupComponent.dismissPopup()
        }
        invalidateKey()
    }

    /**
     * 隐藏短按气泡
     */
    private fun dismissPreview() {
        if (mLongPressKey) {
            mService?.responseLongKeyEvent(popupComponent.triggerFocused())
            mLongPressKey = false
        }
        if (mCurrentKey != null) {
            mCurrentKey!!.onReleased()
            if(mService == null) return
            invalidateKey()
        }
        popupComponent.dismissPopup()
        lastEventX = -1f
    }

    open fun closing() {
        removeMessages()
    }

    private fun showBalloonText(key: SoftKey) {
        val keyboardBalloonShow = AppPrefs.getInstance().keyboardSetting.keyboardBalloonShow.getValue()
        if (keyboardBalloonShow && !TextUtils.isEmpty(key.getkeyLabel())) {
            val bounds = Rect(key.mLeft, key.mTop, key.mRight, key.mBottom)
            popupComponent.showPopup(key.getkeyLabel(), bounds)
        }
    }

    fun getKeyIndices(x: Int, y: Int): SoftKey? {
        return mSoftKeyboard?.mapToKey(x, y)
    }

    open fun setSoftKeyboard(softSkb: SoftKeyboard) {
        mSoftKeyboard = softSkb
    }

    fun getSoftKeyboard(): SoftKeyboard {
        return mSoftKeyboard!!
    }

    companion object {
        private const val MSG_SHOW_PREVIEW = 1
        private const val MSG_REPEAT = 3
        private const val MSG_LONGPRESS = 4
        private const val REPEAT_INTERVAL = 50L // ~20 keys per second
        private const val REPEAT_START_DELAY = 400L
    }
}
