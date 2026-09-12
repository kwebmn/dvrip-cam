package com.kwebmn.dvripcam

import android.content.Context
import com.kwebmn.dvripcam.video.LatencyMode

/** Запоминаемые настройки просмотрщика: качество потока, режим задержки, HUD. */
object ViewerPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("viewer", Context.MODE_PRIVATE)

    /** "Main" (HD) / "Extra" (SD). По умолчанию SD — мгновенный старт. */
    fun stream(ctx: Context): String = p(ctx).getString("stream", "Extra") ?: "Extra"
    fun setStream(ctx: Context, s: String) = p(ctx).edit().putString("stream", s).apply()

    fun latency(ctx: Context): LatencyMode =
        runCatching { LatencyMode.valueOf(p(ctx).getString("latency", "ADAPTIVE")!!) }
            .getOrDefault(LatencyMode.ADAPTIVE)
    fun setLatency(ctx: Context, m: LatencyMode) = p(ctx).edit().putString("latency", m.name).apply()

    fun hud(ctx: Context): Boolean = p(ctx).getBoolean("hud", false)
    fun setHud(ctx: Context, b: Boolean) = p(ctx).edit().putBoolean("hud", b).apply()
}
