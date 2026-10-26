package com.boloscreen

import android.content.Context

object Prefs {
    fun p(c: Context) = c.getSharedPreferences("bs", 0)
    fun s(c: Context, k: String, d: String) = p(c).getString(k, d) ?: d
    fun f(c: Context, k: String, d: Float) = p(c).getFloat(k, d)
    fun i(c: Context, k: String, d: Int) = p(c).getInt(k, d)
    private val cols = intArrayOf(0xFF3F51B5.toInt(), 0xFF212121.toInt(), 0xFFF5F5F5.toInt(), 0xFFF2A900.toInt())
    fun bidx(c: Context) = i(c, "bcolor", 0).coerceIn(0, 3)
    fun bcolor(c: Context) = cols[bidx(c)]
    fun btint(c: Context) = if (bidx(c) >= 2) 0xFF14233A.toInt() else 0xFFFFFFFF.toInt()
}
