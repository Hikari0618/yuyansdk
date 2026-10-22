package com.yuyan.imemodule.view

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.yuyan.imemodule.data.theme.ThemeManager

/** 菜单项。[enabled] = false 用于标题/展示行（如「忘记该词」菜单里要操作的那个词本身）。 */
internal class ImeMenuItem(
    val text: String,
    val enabled: Boolean = true,
    val onClick: (() -> Unit)? = null,
)

private var sImeItemMenu: PopupWindow? = null

/** 收起当前输入法内菜单（切换键盘 / 收起键盘时调用，避免窗口令牌变化后残留）。 */
internal fun dismissImeItemMenu() {
    runCatching { sImeItemMenu?.dismiss() }
    sImeItemMenu = null
}

/**
 * 输入法窗口里的长按菜单（编辑 / 分享 / 分词 / 收藏、「忘记该词」等）。
 *
 * 为什么不用 android.widget.PopupMenu：
 *   它的弹窗是 focusable 的。输入法窗口本身不持焦点（TYPE_INPUT_METHOD），从这个窗口里弹出
 *   聚焦弹窗会触发窗口焦点迁移；宿主应用（典型：Google 搜索首页的搜索框）一旦收到失焦就会
 *   收起键盘 —— 表现就是「长按条目 → 键盘直接消失」。键盘再唤起时，弹窗又因为父窗口令牌变更
 *   闪一下就没了（用户实测：选择界面出现一瞬即消失）。
 *
 * 这里改用 isFocusable=false 的 PopupWindow：不参与焦点竞争、宿主完全无感；点菜单项或点外部
 * 都会收起（outsideTouchable 让弹窗收到 ACTION_OUTSIDE 自行 dismiss）。
 */
internal fun showImeItemMenu(
    anchor: View,
    items: List<ImeMenuItem>,
    title: String? = null,
) {
    if (title == null && items.isEmpty()) return
    val ctx = anchor.context
    val dm = ctx.resources.displayMetrics
    val density = if (dm.density > 0f) dm.density else 1f
    fun dp(v: Int) = (v * density).toInt()

    val theme = ThemeManager.activeTheme
    val bg = theme.popupBackgroundColor
    val fg = theme.keyTextColor
    val disabledFg = Color.argb(0x99, Color.red(fg), Color.green(fg), Color.blue(fg))

    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(bg)
        }
        elevation = dp(6).toFloat()
    }

    fun addRow(text: String, enabled: Boolean, click: (() -> Unit)?) {
        val tv = TextView(ctx).apply {
            this.text = text
            textSize = 16f
            setTextColor(if (enabled) fg else disabledFg)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(280)
            isEnabled = enabled
            isClickable = enabled
        }
        if (enabled && click != null) {
            tv.setOnClickListener {
                dismissImeItemMenu()
                click.invoke()
            }
        }
        card.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    if (title != null) addRow(title, false, null)
    items.forEach { addRow(it.text, it.enabled, it.onClick) }

    val win = PopupWindow(
        card,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        false            // focusable = false：关键，别改
    ).apply {
        isOutsideTouchable = true
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        elevation = dp(8).toFloat()
    }

    sImeItemMenu?.let { runCatching { it.dismiss() } }
    sImeItemMenu = win

    // 先量出菜单尺寸，再决定贴条目下方还是翻到上方，并夹在屏幕内
    card.measure(
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )
    val w = card.measuredWidth.coerceAtLeast(dp(96))
    val h = card.measuredHeight
    val loc = IntArray(2).also { anchor.getLocationOnScreen(it) }
    var x = loc[0] + (anchor.width - w) / 2
    var y = loc[1] + anchor.height + dp(6)
    if (y + h > dm.heightPixels - dp(8)) {
        y = (loc[1] - h - dp(6)).coerceAtLeast(dp(8))
    }
    x = x.coerceIn(dp(4), (dm.widthPixels - w - dp(4)).coerceAtLeast(dp(4)))
    win.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
}
