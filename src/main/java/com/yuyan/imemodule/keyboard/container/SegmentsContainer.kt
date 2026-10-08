package com.yuyan.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.TranslateAnimation
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Phrase
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.yuyan.imemodule.utils.WordTokenizer
import splitties.dimensions.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 分词页面（照同文 Trime 的 SegmentsWindow）。
 *
 * 切词后每个词是一个芯片：点一下选中/取消，也可以按住横向滑行批量选择。
 * 选中即拼进组合区（和同文一样，不用再点「上屏」）；
 * 左上角「←」= 收尾上屏并回键盘，右上角是功能按钮（全选/复制/收藏/分享）；打开时带下拉动画。
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
     * 容器是按 KeyboardType 缓存的，每次显示都按最新原文重新切词；
     * 顺便收起候选栏的剪贴板建议行（分词页面里不需要它），并播放下拉动画。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!::adapter.isInitialized) return
        com.yuyan.inputmethod.util.ImeLog.d("[seg] 分词页面 onAttached，原文长度=${sourceText.length}")
        segments.clear()
        segments.addAll(WordTokenizer.tokenize(sourceText))
        selected.clear()
        adapter.notifyDataSetChanged()
        selectAllButton?.text = "全选"
        inputView.updateSegmentComposing("")
        inputView.hideClipboardSuggestionBar()
        playDropDownAnimation()
    }

    /** 同文的分词页面打开时有下拉动画 */
    private fun playDropDownAnimation() {
        val offset = resources.displayMetrics.heightPixels * 0.25f
        startAnimation(
            TranslateAnimation(0f, 0f, -offset, 0f).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
            }
        )
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

        // 左上角：返回（收尾上屏 + 回键盘）
        val backButton = toolButton("←").apply {
            setOnClickListener { finishAndBack() }
        }
        // 右上角：功能按钮（照同文工具栏）
        val selectAllButton = toolButton("全选").apply {
            setOnClickListener { view ->
                if (selected.size == segments.size) selected.clear() else selected.addAll(segments.indices)
                adapter.notifyDataSetChanged()
                (view as TextView).text = if (selected.size == segments.size) "全不选" else "全选"
                onSelectionChanged()
            }
        }
        this.selectAllButton = selectAllButton
        val copyButton = toolButton("复制").apply {
            setOnClickListener {
                val text = joinedSelection()
                if (text.isEmpty()) return@setOnClickListener
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("", text))
                toast("已复制")
            }
        }
        val collectButton = toolButton("收藏").apply {
            setOnClickListener {
                val text = joinedSelection()
                if (text.isEmpty()) return@setOnClickListener
                val dao = DataBaseKT.instance.phraseDao()
                val message = runCatching {
                    val exist = dao.queryByContent(text)
                    if (exist != null) {
                        exist.isKeep = 1
                        dao.update(exist)
                        "已在短语中，已置顶"
                    } else {
                        dao.insert(Phrase(content = text, t9 = "", qwerty = "", lx17 = ""))
                        "已收藏到短语"
                    }
                }.getOrElse { "收藏失败：${it.message}" }
                toast(message)
            }
        }
        val shareButton = toolButton("分享").apply {
            setOnClickListener {
                val text = joinedSelection()
                if (text.isEmpty()) return@setOnClickListener
                runCatching {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(
                        Intent.createChooser(send, null).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }
        }

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(backButton)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))   // 中间撑开，功能按钮靠右
            addView(selectAllButton)
            addView(copyButton)
            addView(collectButton)
            addView(shareButton)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                topBar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                recyclerView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun toolButton(label: String): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(activeTheme.keyTextColor)
        textSize = instance.candidateTextSize.toFloat()
        setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /** 选中的词拼起来 */
    private fun joinedSelection(): String = buildString {
        segments.forEachIndexed { i, s -> if (selected.contains(i)) append(s) }
    }

    /** 选中即拼进组合区（照同文，不用再点「上屏」） */
    private fun onSelectionChanged() {
        inputView.updateSegmentComposing(joinedSelection())
    }

    /** 返回：组合区收尾上屏，回键盘 */
    private fun finishAndBack() {
        inputView.updateSegmentComposing("")
        KeyboardManager.instance.switchKeyboard()
    }

    private fun toggle(position: Int) {
        if (position !in segments.indices) return
        if (selected.contains(position)) selected.remove(position) else selected.add(position)
        adapter.notifyItemChanged(position)
        onSelectionChanged()
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
                    onSelectionChanged()
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
