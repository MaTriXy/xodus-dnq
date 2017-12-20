/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq

import jetbrains.exodus.entitystore.Entity

open class XdEnumEntity(entity: Entity) : XdEntity(entity) {

    companion object : XdNaturalEntityType<XdEnumEntity>() {
        const val ENUM_CONST_NAME_FIELD = "__ENUM_CONST_NAME__"
    }

    val name by xdRequiredStringProp(dbName = ENUM_CONST_NAME_FIELD)

    open val displayName: String
        get() = name

}