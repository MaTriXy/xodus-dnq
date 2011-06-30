package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Comparator;

/**
 */
abstract class AbstractTransientSession implements TransientStoreSession {
  protected TransientEntityStoreImpl store;
  protected Object id;
  protected String name;
  protected int flushRetryOnLockConflict;

    AbstractTransientSession(final TransientEntityStoreImpl store, final String name, final Object id) {
    this.store = store;
    this.name = (name == null || name.length() == 0) ? "unnamed" : name;
    this.id = id;
    this.flushRetryOnLockConflict = store.getFlushRetryOnLockConflict();
  }

  public void close() {
    throw new UnsupportedOperationException("Unsupported for transient session. Use abort or commit instead.");
  }

    public void setQueryCancellingPolicy(QueryCancellingPolicy policy) {
        getPersistentSessionInternal().setQueryCancellingPolicy(policy);
    }

    public QueryCancellingPolicy getQueryCancellingPolicy() {
        return getPersistentSessionInternal().getQueryCancellingPolicy();
    }


    @NotNull
  public StoreTransaction beginTransaction() {
    throw new UnsupportedOperationException("Unsupported for transient session.");
  }

  @NotNull
  public StoreTransaction beginExclusiveTransaction() {
    throw new UnsupportedOperationException("Unsupported for transient session.");
  }

  public void lockForUpdate(@NotNull Iterable<Entity> entities) {
    throw new UnsupportedOperationException("Unsupported for transient session.");
  }

  @NotNull
  public TransientEntityStore getStore() {
    return store;
  }

  public Object getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  @Nullable
  public StoreTransaction getCurrentTransaction() {
    return this;
  }

  protected StoreSession getPersistentSessionInternal() {
    return store.getPersistentStore().getThreadSession();
  }
}
