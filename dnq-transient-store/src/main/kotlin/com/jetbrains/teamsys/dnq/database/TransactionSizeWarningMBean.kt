 /**
 * Copyright 2006 - 2024 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.teamsys.dnq.database

interface TransactionSizeWarningMBean {

    /**
     * Whether large transaction warnings are enabled.
     * Typically set to true by the application after migrations are complete.
     */
    var enabled: Boolean

    /**
     * Number of changed entities in a single flush that triggers a warning.
     */
    var warningThreshold: Int

    /**
     * Total number of warnings emitted since the monitor was enabled.
     */
    val warningCount: Long

    /**
     * Entity count from the last warning.
     */
    val lastWarningEntityCount: Int

    /**
     * Per-type breakdown from the last warning, e.g. "IssueTag:800, Issue:400".
     */
    val lastWarningEntityTypes: String

    /**
     * Epoch millis of the last warning.
     */
    val lastWarningTimestamp: Long
}