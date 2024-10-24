/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import com.stevesoltys.seedvault.proto.Snapshot

sealed class CheckerResult {
    data class Success(
        val snapshots: List<Snapshot>,
        val percent: Int,
        val size: Long,
    ) : CheckerResult()

    data class Error(
        val snapshots: List<Snapshot>,
        val errors: Map<String, Exception>,
    ) : CheckerResult()

    data class GeneralError(val e: Exception) : CheckerResult()
}
