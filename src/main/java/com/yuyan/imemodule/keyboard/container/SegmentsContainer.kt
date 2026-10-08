package com.yuyan.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.yuyan.imemodule.utils.WordTokenizer
import splitties.dimensions.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 分词页面（照同文 Trime 的 SegmentsWindow）。
 *
 * 把一段文本切词后，每个词显示成一个芯片：点一下选中/取消，也可以按住横向滑行批量选择；
 * 「上屏」把选中的词拼起来送出，「全选」一键全选/全不选。
 */
@SuppressLint("ViewConstructor")
class SegmentsContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {

    companion object {
        /** 待分词的原文。容器是按类型新建的、没法从构造函数带参数，所以放这里传 */
        var sourceText: String = ""
    }

    private val segments = mutableListOf<String>()
    private val selected = mutableSetOf<Int>()
    private lateinit var adapter: SegmentAdapter
    private var selectAllButton: TextView? = null

    init {
        initView()
    }

    /**
     * 容器是按 KeyboardType 缓存的，第二次分词会复用同一个实例 ——
     * 这里按最新的原文重新切词，否则会一直显示上一次的内容。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!::adapter.isInitialized) return
        segments.clear()
        segments.addAll(WordTokenizer.tokenize(sourceText))
        selected.clear()
        adapter.notifyDataSetChanged()
        selectAllButton?.text = "全选"
    }

    private fun initView() {
        segments.clear()
        segments.addAll(WordTokenizer.tokenize(sourceText))
        selected.clear()

        adapter = SegmentAdapter()
        val recyclerView = RecyclerView(context).apply {
            layoutManager = FlexboxLayoutManager(context).apply {
                flexDirection = FlexDirection.ROW
                flexWrap = FlexWrap.WRAP
                alignItems = AlignItems.FLEX_START
            }
            setItemAnimator(null)
            adapter = this@SegmentsContainer.adapter
        }
        recyclerView.addOnItemTouchListener(DragSelectTouchListener(context, recyclerView))

        val selectAllButton = toolButton("全选").apply {
            setOnClickListener { view ->
                if (selected.size == segments.size) selected.clear() else selected.addAll(segments.indices)
                adapter.notifyDataSetChanged()
                (view as TextView).text = if (selected.size == segments.size) "全不选" else "全选"
            }
        }
        this.selectAllButton = selectAllButton
        val commitButton = toolButton("上屏").apply {
            setOnClickListener { commitSelection() }
        }
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(selectAllButton)
            addView(commitButton)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                recyclerView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            addView(
                toolbar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun toolButton(label: String): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(activeTheme.keyTextColor)
        textSize = instance.candidateTextSize.toFloat()
        setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(8f).toInt())
    }

    private fun commitSelection() {
        val text = buildString {
            segments.forEachIndexed { i, s -> if (selected.contains(i)) append(s) }
        }
        if (text.isNotBlank()) {
            inputView.responseLongKeyEvent(Pair(PopupMenuMode.Text, text))
        }
        KeyboardManager.instance.switchKeyboard()
    }

    private fun toggle(position: Int) {
        if (position !in segments.indices) return
        if (selected.contains(position)) selected.remove(position) else selected.add(position)
        adapter.notifyItemChanged(position)
    }

    private fun isSegmentSelected(position: Int): Boolean = selected.contains(position)

    private fun setSegmentSelected(position: Int, value: Boolean) {
        if (position !in segments.indices) return
        if (value) selected.add(position) else selected.remove(position)
        adapter.notifyItemChanged(position)
    }

    inner class SegmentAdapter : RecyclerView.Adapter<SegmentAdapter.Holder>() {
        inner class Holder(val root: TextView) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(TextView(context).apply {
                isSingleLine = true
                textSize = instance.candidateTextSize.toFloat()
                setPadding(dp(10f).toInt(), dp(6f).toInt(), dp(10f).toInt(), dp(6f).toInt())
                layoutParams = FlexboxLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt())
                }
                isClickable = true
            })

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val isSelected = selected.contains(position)
            holder.root.text = segments[position]
            holder.root.setTextColor(
                if (isSelected) activeTheme.accentKeyBackgroundColor else activeTheme.keyTextColor
            )
            holder.root.background = GradientDrawable().apply {
                setColor(activeTheme.keyBackgroundColor)
                cornerRadius = ThemeManager.prefs.keyRadius.getValue().toFloat()
                if (isSelected) setStroke(dp(2f).toInt(), activeTheme.accentKeyBackgroundColor)
            }
            holder.root.setOnClickListener { toggle(position) }
        }

        override fun getItemCount(): Int = segments.size
    }

    /**
     * 按住横向滑行批量选择（照同文 Trime 的 DragSelectTouchListener）：
     * 横向位移超过触摸阈值就进入滑选，把起点到当前手指经过的芯片整段设成同一状态。
     */
    private inner class DragSelectTouchListener(
        context: Context,
        private val recyclerView: RecyclerView,
    ) : RecyclerView.OnItemTouchListener {

        private var isDragSelecting = false
        private var startPosition = -1
        private var lastEndPosition = -1
        private var targetState = false
        private var selectionSnapshot: Set<Int> = emptySet()
        private var initialX = 0f
        private var initialY = 0f
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val scrollThreshold = dp(10f)
        private val maxScrollAmount = dp(12f)

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            val child = rv.findChildViewUnder(e.x, e.y)
            val position = child?.let { rv.getChildAdapterPosition(it) } ?: -1
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (position != -1) {
                        initialX = e.x
                        initialY = e.y
                        startPosition = position
                        return false
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (startPosition == -1) return false
                    if (!isDragSelecting) {
                        val dx = abs(e.x - initialX)
                        val dy = abs(e.y - initialY)
                        if (dx > touchSlop || dy > touchSlop) {
                            if (dx > dy) {   // 横向为主才算滑选，纵向留给滚动
                                isDragSelecting = true
                                rv.parent?.requestDisallowInterceptTouchEvent(true)
                                selectionSnapshot = selected.toSet()
                                targetState = !selectionSnapshot.contains(startPosition)
                                updateRange(startPosition, startPosition)
                                return true
                            } else {
                                startPosition = -1
                                return false
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    startPosition = -1
                    isDragSelecting = false
                }
            }
            return isDragSelecting
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            if (!isDragSelecting) return
            val clampedY = e.y.coerceIn(0f, rv.height.toFloat())
            val child = rv.findChildViewUnder(e.x, clampedY)
            val position = child?.let { rv.getChildAdapterPosition(it) } ?: -1
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val scrollAmount = when {
                        clampedY < scrollThreshold -> -maxScrollAmount
                        clampedY > rv.height - scrollThreshold -> maxScrollAmount
                        else -> 0f
                    }
                    if (scrollAmount != 0f) rv.scrollBy(0, scrollAmount.toInt())
                    if (position != -1 && position != lastEndPosition) {
                        updateRange(startPosition, position)
                        lastEndPosition = position
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragSelecting = false
                    startPosition = -1
                    lastEndPosition = -1
                    rv.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }

        private fun updateRange(start: Int, end: Int) {
            val minNew = min(start, end)
            val maxNew = max(start, end)
            val minOld = min(start, lastEndPosition)
            val maxOld = max(start, lastEndPosition)
            val minEval = min(minNew, minOld)
            val maxEval = max(maxNew, maxOld)
            for (i in minEval..maxEval) {
                val isInsideNewRange = i in minNew..maxNew
                val expectedState =
                    if (isInsideNewRange) targetState else selectionSnapshot.contains(i)
                if (isSegmentSelected(i) != expectedState) setSegmentSelected(i, expectedState)
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    }
}
