/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault

import android.app.ActivityManager
import android.app.ActivityManager.MemoryInfo
import android.content.Context
import android.os.Debug.getNativeHeapAllocatedSize
import android.os.Debug.getNativeHeapFreeSize
import android.os.Debug.getNativeHeapSize
import android.text.format.Formatter.formatShortFileSize
import android.util.Log

object MemoryLogger {

    private const val TAG = "MemoryLogger"

    fun logFull(ctx: Context) {
        val am: ActivityManager = ctx.getSystemService(ActivityManager::class.java)
        val mem = MemoryInfo()
        am.getMemoryInfo(mem)
        val r = Runtime.getRuntime()
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)

        Log.i(TAG, "mem: ${ctx.s(mem.availMem)}/${ctx.s(mem.totalMem)} (${ctx.s(mem.threshold)})")
        Log.i(TAG, "mem.lowMemory: ${mem.lowMemory}")
        Log.i(TAG, "memClass: ${am.memoryClass} MiB, large: ${am.largeMemoryClass} MiB")
        Log.i(TAG, "runtime: ${ctx.s(r.freeMemory())}/${ctx.s(r.totalMemory())}")
        Log.i(TAG, "runtime.maxMemory() ${ctx.s(r.maxMemory())}")
        Log.i(TAG, "getNativeHeapSize() ${ctx.s(getNativeHeapSize())}")
        Log.i(TAG, "getNativeHeapAllocatedSize() ${ctx.s(getNativeHeapAllocatedSize())}")
        Log.i(TAG, "getNativeHeapFreeSize() ${ctx.s(getNativeHeapFreeSize())}")

        Log.i(TAG, "importance: ${state.importance} - reason: ${state.importanceReasonCode}" +
            " - lru: ${state.lru} - reasonPid: ${state.importanceReasonPid}")
        Log.i(TAG, "lastTrimLevel: ${state.lastTrimLevel} - ${state.pkgList}")
    }

    fun log(ctx: Context) {
        val r = Runtime.getRuntime()
        Log.i(
            TAG, "runtime: ${ctx.s(r.freeMemory())} free of ${ctx.s(r.totalMemory())} - " +
                "native heap: ${ctx.s(getNativeHeapFreeSize())} free " +
                "of ${ctx.s(getNativeHeapSize())}"
        )
    }

    private fun Context.s(size: Long): String {
        return formatShortFileSize(this, size)
    }

}
