/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
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
package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.database.TransientStoreSession;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityId;
import jetbrains.exodus.entitystore.iterate.EntityIteratorWithPropId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

class PersistentEntityIteratorWithPropIdWrapper implements EntityIteratorWithPropId {

    @NotNull
    protected final EntityIteratorWithPropId source;
    @NotNull
    private final TransientStoreSession session;

    PersistentEntityIteratorWithPropIdWrapper(
            @NotNull final EntityIteratorWithPropId source,
            @NotNull final TransientStoreSession session) {
        this.source = source;
        this.session = session;
    }

    public boolean hasNext() {
        return source.hasNext();
    }

    @Nullable
    public Entity next() {
        //TODO: do not save in session?
        final Entity persistentEntity = source.next();
        if (persistentEntity == null) {
            return null;
        }
        return session.newEntity(persistentEntity);
    }

    @Nullable
    public String currentLinkName() {
        return source.currentLinkName();
    }

    public void remove() {
        source.remove();
    }

    @Nullable
    public EntityId nextId() {
        return source.nextId();
    }

    public boolean dispose() {
        return source.dispose();
    }

    public boolean skip(int number) {
        return source.skip(number);
    }

    public boolean shouldBeDisposed() {
        return source.shouldBeDisposed();  //TODO: revisit EntityIterator interface and remove these stub method
    }
}
