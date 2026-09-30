package com.yuyan.imemodule.entity

import com.yuyan.imemodule.prefs.behavior.SkbMenuMode

class SkbFunItem(val funName: String, @JvmField val funImgResource: Int, val skbMenuMode: SkbMenuMode, val schemaId: String = ""){
    override fun equals(other: Any?): Boolean {
        return when(other) {
            !is SkbFunItem -> false
            else -> this === other || (this.skbMenuMode == other.skbMenuMode && this.schemaId == other.schemaId)
        }
    }
}
