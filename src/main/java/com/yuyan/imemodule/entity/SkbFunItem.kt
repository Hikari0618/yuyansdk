package com.yuyan.imemodule.entity

import com.yuyan.imemodule.prefs.behavior.SkbMenuMode

class SkbFunItem(
    val funName: String,
    @JvmField val funImgResource: Int,
    val skbMenuMode: SkbMenuMode,
    val schemaId: String = "",
    /** 「选方案」行专用：点这一行要切到的键盘布局（InputModeSwitcher.MASK_SKB_LAYOUT_*） */
    val layoutMask: Int = 0,
){
    override fun equals(other: Any?): Boolean {
        return when(other) {
            !is SkbFunItem -> false
            else -> this === other || (this.skbMenuMode == other.skbMenuMode && this.schemaId == other.schemaId && this.layoutMask == other.layoutMask)
        }
    }
}
