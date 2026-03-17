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
package kotlinx.dnq

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TransactionSizeWarningTest : DBTest() {

    @Test
    fun `warning count increments when threshold is exceeded`() {
        val warning = store.transactionSizeWarning
        warning.enabled = true
        warning.warningThreshold = 2

        store.transactional {
            repeat(3) { i ->
                User.new {
                    login = "user$i"
                    skill = 1
                }
            }
        }

        assertThat(warning.warningCount).isEqualTo(1)
    }

    @Test
    fun `no warning when disabled`() {
        val warning = store.transactionSizeWarning
        warning.enabled = false
        warning.warningThreshold = 2

        store.transactional {
            repeat(3) { i ->
                User.new {
                    login = "user$i"
                    skill = 1
                }
            }
        }

        assertThat(warning.warningCount).isEqualTo(0)
    }

    @Test
    fun `no warning when below threshold`() {
        val warning = store.transactionSizeWarning
        warning.enabled = true
        warning.warningThreshold = 10

        store.transactional {
            repeat(3) { i ->
                User.new {
                    login = "user$i"
                    skill = 1
                }
            }
        }

        assertThat(warning.warningCount).isEqualTo(0)
    }
}
