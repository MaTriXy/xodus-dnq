package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.Pair;
import jetbrains.exodus.core.dataStructures.decorators.HashMapDecorator;
import jetbrains.exodus.core.dataStructures.decorators.HashSetDecorator;
import jetbrains.exodus.core.dataStructures.decorators.QueueDecorator;
import jetbrains.exodus.core.dataStructures.hash.HashSet;
import jetbrains.exodus.database.*;
import jetbrains.exodus.database.exceptions.*;
import jetbrains.exodus.exceptions.PhysicalLayerException;
import jetbrains.teamsys.dnq.runtime.events.EventsMultiplexer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.util.*;

/**
 */
public class TransientSessionImpl implements TransientStoreSession {

    protected static final Log log = LogFactory.getLog(TransientSessionImpl.class);

    protected TransientEntityStoreImpl store;
    protected int flushRetryOnVersionMismatch;
    protected State state;
    protected boolean quietFlush = false;
    protected TransientChangesTrackerImpl changesTracker;
    // stores transient entities that were created for loaded persistent entities to avoid double loading
    protected Map<EntityId, TransientEntity> managedEntities;
    private Queue<MyRunnable> changes = new QueueDecorator<MyRunnable>();
    private int hashCode = 0;

    protected TransientSessionImpl(final TransientEntityStoreImpl store) {
        this.store = store;
        this.flushRetryOnVersionMismatch = store.getFlushRetryOnLockConflict();
        this.store.getPersistentStore().beginTransaction();
        this.state = State.Open;
        this.managedEntities = new HashMapDecorator<EntityId, TransientEntity>();
        initChangesTracker();
    }

    private TransientChangesTrackerImpl initChangesTracker() {
        if (this.changesTracker != null) changesTracker.dispose();
        TransientChangesTrackerImpl oldChangesTracker = changesTracker;
        this.changesTracker = new TransientChangesTrackerImpl(getSnapshot());

        return oldChangesTracker;
    }

    public void setQueryCancellingPolicy(QueryCancellingPolicy policy) {
        getPersistentTransactionInternal().setQueryCancellingPolicy(policy);
    }

    public QueryCancellingPolicy getQueryCancellingPolicy() {
        return getPersistentTransactionInternal().getQueryCancellingPolicy();
    }

    @NotNull
    public TransientEntityStore getStore() {
        return store;
    }

    protected StoreTransaction getPersistentTransactionInternal() {
        return store.getPersistentStore().getCurrentTransaction();
    }

    public String toString() {
        return "transaction [" + hashCode() + "] state [" + state + "]";
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = (int)(Math.random()*Integer.MAX_VALUE);
        }

        return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this;
    }

    public boolean isOpened() {
        return state == State.Open;
    }

    public boolean isCommitted() {
        return state == State.Committed;
    }

    public boolean isAborted() {
        return state == State.Aborted;
    }

    void assertOpen(final String action) {
        if (state != State.Open) {
            throw new IllegalStateException("Can't " + action + " in state [" + state + "]");
        }
    }

    @NotNull
    public EntityIterable createPersistentEntityIterableWrapper(@NotNull EntityIterable wrappedIterable) {
        assertOpen("create wrapper");
        // do not wrap twice
        if (wrappedIterable instanceof PersistentEntityIterableWrapper) {
            return wrappedIterable;
        } else {
            return new PersistentEntityIterableWrapper(wrappedIterable);
        }
    }

    @Override
    public void revert() {
        if (log.isDebugEnabled()) {
            log.debug("Revert transient session " + this);
        }
        assertOpen("revert");

        managedEntities = new HashMapDecorator<EntityId, TransientEntity>();
        changes = new QueueDecorator<MyRunnable>();
        getPersistentTransactionInternal().revert();
        initChangesTracker();
    }

    @Override
    public void flush() {
        if (store.getThreadSession() != this) {
            throw new IllegalStateException("Can't commit session from another thread.");
        }

        if (log.isDebugEnabled()) {
            log.debug("Intermidiate commit transient session " + this);
        }

        assertOpen("flush");

        if (changes.isEmpty()) {
            log.trace("Nothing to flush.");
        } else {
            flushChanges();
            changes = new QueueDecorator<MyRunnable>();

            for (TransientEntity r: changesTracker.getRemovedEntities()) {
                managedEntities.remove(r.getId());
            }

            final TransientChangesTracker oldChangesTracker = changesTracker;
            this.changesTracker = new TransientChangesTrackerImpl(getSnapshot());
            notifyFlushedListeners(oldChangesTracker);
        }
    }

    public void commit() {
        // flush until no side-effects from listeners
        do {
            flush();
        } while (!changes.isEmpty());

        try {
            changesTracker.dispose();
        } finally {
            try {
                closePersistentSession();
            } finally {
                store.unregisterStoreSession(this);
                state = State.Committed;
            }
        }
    }

    public void abort() {
        if (store.getThreadSession() != this) {
            throw new IllegalStateException("Can't abort session that is not current thread session. Current thread session is [" + store.getThreadSession() + "]");
        }

        if (log.isDebugEnabled()) {
            log.debug("Abort transient session " + this);
        }
        assertOpen("abort");
        try {
            changesTracker.dispose();
        } finally {
            try {
                closePersistentSession();
            } finally {
                store.unregisterStoreSession(this);
                state = State.Aborted;
            }
        }
    }

    @NotNull
    public StoreTransaction getPersistentTransaction() {
        assertOpen("get persistent transaction");
        return getPersistentTransactionInternal();
    }


    /**
     * Creates transient wrapper for existing persistent entity
     *
     * @param persistent
     * @return
     */
    @NotNull
    public TransientEntity newEntity(@NotNull Entity persistent) {
        if (persistent instanceof TransientEntity) {
            throw new IllegalArgumentException("Can't create transient entity wrapper for another transient entity.");
        }
        assertOpen("create entity");
        return newEntityImpl(persistent);
    }

    @Nullable
    public Entity getEntity(@NotNull final EntityId id) {
        assertOpen("get entity");
        TransientEntity e = managedEntities.get(id);
        if (e == null) {
            PersistentEntityStoreImpl persistentEntityStore = (PersistentEntityStoreImpl) store.getPersistentStore();
            if (persistentEntityStore.getLastVersion(id) < 0) {
                return null;
            }
            return newEntity(persistentEntityStore.getEntity(id));
        } else {
            return e;
        }
    }

    @NotNull
    public EntityId toEntityId(@NotNull final String representation) {
        assertOpen("convert to entity id");
        return getPersistentTransactionInternal().toEntityId(representation);
    }

    @NotNull
    public List<String> getEntityTypes() {
        assertOpen("get entity types");
        return getPersistentTransactionInternal().getEntityTypes();
    }

    @NotNull
    public EntityIterable getAll(@NotNull final String entityType) {
        assertOpen("getAll");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().getAll(entityType));
    }

    @NotNull
    public EntityIterable getSingletonIterable(@NotNull final Entity entity) {
        assertOpen("getSingletonIterable");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().getSingletonIterable(((TransientEntityImpl) entity).getPersistentEntity()));
    }

    @NotNull
    public EntityIterable find(@NotNull final String entityType, @NotNull final String propertyName, @NotNull final Comparable value) {
        assertOpen("find");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().find(entityType, propertyName, value));
    }

    @NotNull
    public EntityIterable find(@NotNull final String entityType, @NotNull final String propertyName, @NotNull final Comparable minValue, @NotNull final Comparable maxValue) {
        assertOpen("find");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().find(entityType, propertyName, minValue, maxValue));
    }

    public EntityIterable findWithProp(@NotNull final String entityType, @NotNull final String propertyName) {
        assertOpen("findWithProp");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().findWithProp(entityType, propertyName));
    }

    @NotNull
    public EntityIterable startsWith(@NotNull final String entityType, @NotNull final String propertyName, @NotNull final String value) {
        assertOpen("startsWith");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().startsWith(entityType, propertyName, value));
    }

    @NotNull
    public EntityIterable findWithBlob(@NotNull final String entityType, @NotNull final String propertyName) {
        assertOpen("findWithBlob");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().findWithBlob(entityType, propertyName));
    }

    @NotNull
    public EntityIterable findLinks(@NotNull final String entityType, @NotNull final Entity entity, @NotNull final String linkName) {
        assertOpen("findLinks");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().findLinks(entityType, entity, linkName));
    }

    @NotNull
    public EntityIterable findLinks(@NotNull String entityType, @NotNull EntityIterable entities, @NotNull String linkName) {
        assertOpen("findLinks");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().findLinks(entityType, entities.getSource(), linkName));
    }

    @NotNull
    public EntityIterable findWithLinks(@NotNull String entityType, @NotNull String linkName) {
        assertOpen("findWithLinks");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().findWithLinks(entityType, linkName));
    }

    @NotNull
    public EntityIterable findWithLinks(@NotNull String entityType,
                                        @NotNull String linkName,
                                        @NotNull String oppositeEntityType,
                                        @NotNull String oppositeLinkName) {
        assertOpen("findWithLinks");
        return new PersistentEntityIterableWrapper(
                getPersistentTransactionInternal().findWithLinks(entityType, linkName, oppositeEntityType, oppositeLinkName));
    }

    @NotNull
    public EntityIterable sort(@NotNull final String entityType,
                               @NotNull final String propertyName,
                               final boolean ascending) {
        assertOpen("sort");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().sort(entityType, propertyName, ascending));
    }

    @NotNull
    public EntityIterable sort(@NotNull final String entityType,
                               @NotNull final String propertyName,
                               @NotNull final EntityIterable rightOrder,
                               final boolean ascending) {
        assertOpen("sort");
        return new PersistentEntityIterableWrapper(
                getPersistentTransactionInternal().sort(entityType, propertyName, rightOrder.getSource(), ascending));
    }

    @NotNull
    public EntityIterable sortLinks(@NotNull final String entityType,
                                    @NotNull final EntityIterable sortedLinks,
                                    boolean isMultiple,
                                    @NotNull final String linkName,
                                    final @NotNull EntityIterable rightOrder) {
        assertOpen("sortLinks");
        return new PersistentEntityIterableWrapper(
                getPersistentTransactionInternal().sortLinks(entityType, sortedLinks, isMultiple, linkName, rightOrder.getSource()));
    }

    @NotNull
    public EntityIterable sortLinks(@NotNull final String entityType,
                                    @NotNull final EntityIterable sortedLinks,
                                    boolean isMultiple,
                                    @NotNull final String linkName,
                                    final @NotNull EntityIterable rightOrder,
                                    @NotNull final String oppositeEntityType,
                                    @NotNull final String oppositeLinkName) {
        assertOpen("sortLinks");
        return new PersistentEntityIterableWrapper(
                getPersistentTransactionInternal().sortLinks(entityType, sortedLinks, isMultiple, linkName, rightOrder.getSource(), oppositeEntityType, oppositeLinkName));
    }

    @NotNull
    public EntityIterable mergeSorted(@NotNull List<EntityIterable> sorted, @NotNull Comparator<Entity> comparator) {
        assertOpen("mergeSorted");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().mergeSorted(sorted, comparator));
    }

    @NotNull
    public EntityIterable distinct(@NotNull final EntityIterable source) {
        assertOpen("distinct");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().distinct(source.getSource()));
    }

    @NotNull
    public EntityIterable selectDistinct(@NotNull EntityIterable source, @NotNull String linkName) {
        assertOpen("selectDistinct");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().selectDistinct(source.getSource(), linkName));
    }

    @NotNull
    public EntityIterable selectManyDistinct(@NotNull EntityIterable source, @NotNull String linkName) {
        assertOpen("selectManyDistinct");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().selectManyDistinct(source.getSource(), linkName));
    }

    @Nullable
    public Entity getFirst(@NotNull final EntityIterable it) {
        assertOpen("getFirst");
        final Entity first = getPersistentTransactionInternal().getFirst(it.getSource());
        return (first == null) ? null : newEntityImpl(first);
    }

    @Nullable
    public Entity getLast(@NotNull final EntityIterable it) {
        assertOpen("getLast");
        final Entity last = getPersistentTransactionInternal().getLast(it.getSource());
        return (last == null) ? null : newEntityImpl(last);
    }

    @NotNull
    public EntityIterable reverse(@NotNull final EntityIterable source) {
        assertOpen("reverse");
        return new PersistentEntityIterableWrapper(getPersistentTransactionInternal().reverse(source.getSource()));
    }

    @NotNull
    public Sequence getSequence(@NotNull final String sequenceName) {
        assertOpen("get sequence");
        return getPersistentTransactionInternal().getSequence(sequenceName);
    }

    protected void closePersistentSession() {
        if (log.isDebugEnabled()) {
            log.debug("Close persistent session for transient session " + this);
        }
        StoreTransaction persistentTxn = getPersistentTransactionInternal();
        if (persistentTxn != null) {
            persistentTxn.abort();
        }
    }

    public void quietIntermediateCommit() {
        final boolean qf = quietFlush;
        try {
            this.quietFlush = true;
            flush();
        } finally {
            this.quietFlush = qf;
        }
    }

    public void clearHistory(@NotNull final String entityType) {
        assertOpen("clear history");

        addChange(new MyRunnable() {
            public boolean run() {
                getPersistentTransaction().clearHistory(entityType);
                return true;
            }
        }).run();
    }

    @NotNull
    public TransientChangesTracker getTransientChangesTracker() throws IllegalStateException {
        assertOpen("get changes tracker");
        return changesTracker;
    }


    private void replayChanges() {
        initChangesTracker();

        for (MyRunnable c : changes) {
            c.run();
        }
    }

    /**
     * Removes orphans (entities without parents) or returns OrphanException to throw later.
     */
    @NotNull
    private Set<DataIntegrityViolationException> removeOrphans() {
        Set<DataIntegrityViolationException> orphans = new HashSetDecorator<DataIntegrityViolationException>();
        final ModelMetaData modelMetaData = store.getModelMetaData();

        if (modelMetaData == null) {
            return orphans;
        }

        for (TransientEntity e : new ArrayList<TransientEntity>(changesTracker.getChangedEntities())) {
            if (!e.isRemoved()) {
                EntityMetaData emd = modelMetaData.getEntityMetaData(e.getType());

                if (emd != null && emd.hasAggregationChildEnds() && !EntityMetaDataUtils.hasParent(emd, e, changesTracker)) {
                    if (emd.getRemoveOrphan()) {
                        // has no parent - remove
                        if (log.isDebugEnabled()) {
                            log.debug("Remove orphan: " + e);
                        }

                        EntityOperations.remove(e);
                    } else {
                        // has no parent, but orphans shouldn't be removed automatically - exception
                        orphans.add(new OrphanChildException(e, emd.getAggregationChildEnds()));
                    }
                }
            }
        }

        return orphans;
    }

    /**
     * Creates new transient entity
     *
     * @param entityType
     * @return
     */
    @NotNull
    public Entity newEntity(@NotNull final String entityType) {
        assertOpen("create entity");
        final TransientEntity e = new TransientEntityImpl(entityType, this.getStore());
        managedEntities.put(e.getId(), e);
        return e;
    }

    /**
     * Creates local copy of given entity in current session.
     *
     * @param entity
     * @return
     */
    @NotNull
    public TransientEntity newLocalCopy(@NotNull final TransientEntity entity) {
        assertOpen("create local copy");
        if (entity.isReadonly()) {
            return entity;
        } else if (entity.isRemoved()) {
            EntityRemovedException entityRemovedException = new EntityRemovedException(entity);
            log.warn("Entity [" + entity + "] was removed by you.");
            throw entityRemovedException;
        } else if (entity.isNew()) {
            final EntityId entityId = entity.getId();
            if (managedEntities.get(entityId) == entity) {
                // was created in this session and session wasn't reverted
                return entity;
            }
            throw new IllegalStateException("Entity in state New was not created in this session. " + entity);
        } else if (entity.isSaved()) {
            final EntityId entityId = entity.getId();
            if (managedEntities.get(entityId) == entity) {
                // was created in this session and session wasn't reverted
                return entity;
            } else {
                // saved entity from another session or from reverted session - load it from database by id
                // local copy already created?
                TransientEntity localCopy = managedEntities.get(entityId);
                if (localCopy != null) {
                    if (localCopy.isRemoved()) {
                        EntityRemovedException entityRemovedException = new EntityRemovedException(entity);
                        log.warn("Local copy of entity [" + entity + "] was removed by you.");
                        throw entityRemovedException;
                    }
                    return localCopy;
                }

                try {
                    // load persistent entity from database by id
                    return newEntity(getPersistentTransactionInternal().getEntity(entityId));
                } catch (EntityRemovedInDatabaseException e) {
                    log.warn("Entity [" + entity + "] was removed in database, can't create local copy.");
                    throw e;
                }
            }
        } else {
            throw new IllegalStateException("Can't create local copy of entity (unexpected state) [" + entity + "]");
        }
    }

    /**
     * Checks if entity entity was removed in this transaction or in database
     *
     * @param entity
     * @return true if e was removed, false if it wasn't removed at all
     */
    public boolean isRemoved(@NotNull final Entity entity) {
        EntityId entityId = null;
        if (entity instanceof TransientEntity && state == State.Open) {
            final TransientEntity transientEntity = (TransientEntity) entity;
            if (transientEntity.isRemoved()) {
                return true;
            } else if (transientEntity.isSaved()) {
                // saved entity from another session or from reverted session
                entityId = entity.getId();
                if (managedEntities.get(entityId) != transientEntity) {
                    // local copy already created?
                    TransientEntity localCopy = managedEntities.get(entityId);
                    if (localCopy != null && localCopy.isRemoved()) {
                        return true;
                    }
                }
            } else if (transientEntity.isReadonly() || transientEntity.isNew()) {
                return false;
            }
        }
        // load persistent entity from database by id
        if (entityId == null) {
            entityId = entity.getId();
        }
        return ((PersistentEntityStore) getPersistentTransactionInternal().getStore()).getLastVersion(entityId) < 0;
    }

    /**
     * Checks constraints before save changes
     *
     * @param exceptions resulting errors set
     */
    private void checkBeforeSaveChangesConstraints(@NotNull final Set<DataIntegrityViolationException> exceptions) {
        final ModelMetaData modelMetaData = store.getModelMetaData();

        if (quietFlush || /* for tests only */ modelMetaData == null) {
            if (log.isWarnEnabled()) {
                log.warn("Quiet intermediate commit: skip before save changes constraints checking. " + this);
            }
            return;
        }

        if (log.isTraceEnabled()) {
            log.trace("Check before save changes constraints. " + this);
        }

        // 0. check incoming links for deleted entities
        exceptions.addAll(ConstraintsUtil.checkIncomingLinks(changesTracker));

        // 1. check associations cardinality
        exceptions.addAll(ConstraintsUtil.checkAssociationsCardinality(changesTracker, modelMetaData));

        // 2. check required properties
        exceptions.addAll(ConstraintsUtil.checkRequiredProperties(changesTracker, modelMetaData));

        // 3. check other property constraints
        exceptions.addAll(ConstraintsUtil.checkOtherPropertyConstraints(changesTracker, modelMetaData));

        // 4. check index fields
        exceptions.addAll(ConstraintsUtil.checkIndexFields(changesTracker, modelMetaData));

        if (exceptions.size() != 0) {
            ConstraintsValidationException e = new ConstraintsValidationException(exceptions);
            store.forAllListeners(new TransientEntityStoreImpl.ListenerVisitor() {
                public void visit(TransientStoreSessionListener listener) {
                    try {
                        listener.afterConstraintsFail(exceptions);
                    } catch (Exception e) {
                        if (log.isErrorEnabled()) {
                            log.error("Exception while inside listener [" + listener + "]", e);
                        }
                        // do not rethrow exception, because we are after constaints fail
                    }
                }
            });
            throw e;
        }
    }

    /**
     * Checks custom flush constraints before save changes
     *
     * @return side effects
     */
    @Nullable
    private Set<TransientEntity> executeBeforeFlushTriggers(Set<TransientEntity> changedEntities, Set<TransientEntity> processedEntities) {
        final ModelMetaData modelMetaData = store.getModelMetaData();

        if (quietFlush || /* for tests only */ modelMetaData == null) {
            if (log.isDebugEnabled()) {
                log.warn("Quiet intermediate commit: skip before flush triggers. " + this);
            }
            return null;
        }

        if (log.isDebugEnabled()) {
            log.debug("Execute before flush triggers. " + this);
        }

        final Set<DataIntegrityViolationException> exceptions = new HashSetDecorator<DataIntegrityViolationException>();
        final int changesEntitiesCount = ((TransientChangesTrackerImpl) changesTracker).getChangedEntities().size();
        for (TransientEntity entity : changedEntities) {
            if (!entity.isRemoved()) {
                EntityMetaData md = modelMetaData.getEntityMetaData(entity.getType());

                // meta-data may be null for persistent enums
                if (md != null) {
                    try {
                        TransientStoreUtil.getPersistentClassInstance(entity, md).executeBeforeFlushTrigger(entity);
                    } catch (ConstraintsValidationException cve) {
                        for (DataIntegrityViolationException dive : cve.getCauses()) {
                            exceptions.add(dive);
                        }
                    }
                }
            }
        }

        if (exceptions.size() != 0) {
            ConstraintsValidationException e = new ConstraintsValidationException(exceptions);
            throw e;
        }

        if (changesEntitiesCount != ((TransientChangesTrackerImpl) changesTracker).getChangedEntities().size()) {
            processedEntities.addAll(changedEntities);

            HashSet<TransientEntity> sideEffect = new HashSet<TransientEntity>(changesTracker.getChangedEntities());
            sideEffect.removeAll(processedEntities);

            return sideEffect;
        }

        return null;
    }

    /**
     * Flushes changes
     */
    private void flushChanges() {
        beforeFlush();
        checkBeforeSaveChangesConstraints(removeOrphans());

        notifyBeforeFlushAfterConstraintsCheckListeners();

        int retry = 0;
        Throwable lastEx = null;
        final StoreTransaction txn = getPersistentTransactionInternal();

        while (retry++ < flushRetryOnVersionMismatch) {
            try {
                saveHistory();
                flushIndexes();

                if (log.isTraceEnabled()) {
                    log.trace("Flush persistent transaction in transient session " + this);
                }

                txn.flush();
                lastEx = null;
                break;
            } catch (Throwable e) {
                lastEx = e;
                log.error("Catch exception in flush: " + e.getMessage());

                if (e instanceof jetbrains.exodus.exceptions.VersionMismatchException) {
                    Thread.yield();
                    // replay changes
                    replayChanges();
                    //recheck constraints against new database root
                    checkBeforeSaveChangesConstraints(removeOrphans());
                } else {
                    break;
                }
            }
        }

        if (lastEx != null) {
            txn.revert();
            // we have to execute changes against new database root
            //TODO: there're none recovarable exceptions, for which can skip executeChanges
            replayChanges();
            decodeException(lastEx);
            throw new IllegalStateException("should never be thrown");
        }
    }

    private PersistentStoreTransaction getSnapshot() {
        return ((PersistentStoreTransaction)getPersistentTransaction()).getSnapshot();
    }

    private void flushIndexes() {
        if (TransientStoreUtil.isPostponeUniqueIndexes()) {
            return;
        }

        for (TransientEntity e: changesTracker.getChangedEntities()) {
                if (!e.isRemoved()) {
                    // create/update
                    Set<Index> dirtyIndeces = new HashSetDecorator<Index>();
                    final Set<String> changedProperties = changesTracker.getChangedProperties(e);
                    if (changedProperties != null) {
                        for (String propertyName: changedProperties) {
                            final Set<Index> indices = getMetadataIndexes(e, propertyName);
                            if (indices != null) {
                                dirtyIndeces.addAll(indices);
                            }
                        }
                    }

                    final Map<String, LinkChange> changedLinksDetailed = changesTracker.getChangedLinksDetailed(e);
                    if (changedLinksDetailed != null) {
                        for (String propertyName: changedLinksDetailed.keySet()) {
                            final Set<Index> indices = getMetadataIndexes(e, propertyName);
                            if (indices != null) {
                                dirtyIndeces.addAll(indices);
                            }
                        }
                    }

                    for (Index index: dirtyIndeces) {
                        try {
                            if (!e.isNew()) {
                                getPersistentTransaction().deleteUniqueKey(index, getIndexFieldsOriginalValues(e, index));
                            }
                            getPersistentTransaction().insertUniqueKey(index, getIndexFieldsFinalValues(e, index), e);
                        } catch (PhysicalLayerException ex) {
                            throw new ConstraintsValidationException(new UniqueIndexViolationException(e, index));
                        }
                    }
                }
        }
    }

    @Nullable
    Set<Pair<Index, List<Comparable>>> getIndexesValuesBeforeDelete(TransientEntity e) {
        if (changesTracker.isNew(e)) return null;

        Set<Pair<Index, List<Comparable>>> res = null;
        final EntityMetaData emd = getEntityMetaData(e);
        if (emd != null) {
            for (Index index: emd.getIndexes()) {
                if (res == null) res = new HashSet<Pair<Index, List<Comparable>>>();
                res.add(new Pair<Index, List<Comparable>>(index, getIndexFieldsOriginalValues(e, index)));
            }
        }

        return res;
    }

    void deleteIndexes(TransientEntity e, @Nullable Set<Pair<Index, List<Comparable>>> indexes) {
        if (indexes == null) return;

        for (Pair<Index, List<Comparable>> index : indexes) {
            try {
                getPersistentTransaction().deleteUniqueKey(index.getFirst(), index.getSecond());
            } catch (PhysicalLayerException ex) {
                throw new ConstraintsValidationException(new UniqueIndexViolationException(e, index.getFirst()));
            }
        }
    }

    void deleteIndexes(TransientEntity e) {
    }

    private List<Comparable> getIndexFieldsOriginalValues(TransientEntity e, Index index) {
        List<Comparable> res = new ArrayList<Comparable>(index.getFields().size());
        for (IndexField f: index.getFields()) {
            if (f.isProperty()) {
                res.add(getOriginalPropertyValue(e, f.getName()));
            } else {
                res.add(getOriginalLinkValue(e, f.getName()));
            }
        }
        return res;
    }

    @Nullable
    private Comparable getOriginalPropertyValue(TransientEntity e, String propertyName) {
        return e.getPersistentEntity().getSnapshot(changesTracker.getSnapshot()).getProperty(propertyName);
    }

    @Nullable
    private String getOriginalBlobStringValue(TransientEntity e, String blobName) {
        return e.getPersistentEntity().getSnapshot(changesTracker.getSnapshot()).getBlobString(blobName);
    }

    @Nullable
    private InputStream getOriginalBlobValue(TransientEntity e, String blobName) {
        return e.getPersistentEntity().getSnapshot(changesTracker.getSnapshot()).getBlob(blobName);
    }

    @Nullable
    private Comparable getOriginalLinkValue(TransientEntity e, String linkName) {
        // get from saved changes, if not - from db
        Map<String, LinkChange> linksDetailed = changesTracker.getChangedLinksDetailed(e);
        if (linksDetailed != null) {
            LinkChange change = linksDetailed.get(linkName);
            if (change != null) {
                switch (change.getChangeType()) {
                    case ADD_AND_REMOVE:
                    case REMOVE:
                        if (change.getRemovedEntitiesSize() != 1) {
                            throw new IllegalStateException("Can't determine original link value: " + e.getType() + "." + linkName);
                        }
                        return change.getRemovedEntities().iterator().next();
                    default:
                        throw new IllegalStateException("Incorrect change type for link that is part of index: " + e.getType() + "." + linkName + ": " + change.getChangeType().getName());
                }
            }
        }

        return e.getPersistentEntity().getSnapshot(changesTracker.getSnapshot()).getLink(linkName);
    }

    private List<Comparable> getIndexFieldsFinalValues(TransientEntity e, Index index) {
        List<Comparable> res = new ArrayList<Comparable>(index.getFields().size());
        for (IndexField f: index.getFields()) {
            if (f.isProperty()) {
                res.add(e.getProperty(f.getName()));
            } else {
                res.add(e.getLink(f.getName()));
            }
        }
        return res;
    }

    @Nullable
    private Set<Index> getMetadataIndexes(TransientEntity e, String field) {
        EntityMetaData md = getEntityMetaData(e);
        return md == null ? null : md.getIndexes();
    }

    @Nullable
    private EntityMetaData getEntityMetaData(TransientEntity e) {
        ModelMetaData mdd = store.getModelMetaData();
        return mdd == null ? null : mdd.getEntityMetaData(e.getType());
    }

    private void beforeFlush() {
        // notify listeners, execute before flush, if were side effects, do the same for side effects

        Set<TransientEntityChange> changesDescription = changesTracker.getChangesDescription();
        Set<TransientEntity> sideEffects = new HashSet<TransientEntity>(changesTracker.getChangedEntities());
        Set<TransientEntity> processedEntities = new HashSetDecorator<TransientEntity>();

        while (sideEffects != null && !sideEffects.isEmpty()) {
            notifyBeforeFlushListeners(changesDescription);
            sideEffects = executeBeforeFlushTriggers(sideEffects, processedEntities);

            if (sideEffects != null && !sideEffects.isEmpty()) {
                changesDescription = new HashSet<TransientEntityChange>();
                for (TransientEntity sideEffectEntity : sideEffects) {
                    changesDescription.add(changesTracker.getChangeDescription(sideEffectEntity));
                }
            }
        }
    }

    private static void decodeException(@NotNull Throwable e) {
        if (e instanceof Error) {
            throw (Error) e;
        }

        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }

        throw new RuntimeException(e);
    }

    private void saveHistory() {
        final ModelMetaData modelMetaData = store.getModelMetaData();

        if (modelMetaData == null) {
            if (log.isWarnEnabled()) {
                log.warn("Model meta data is not defined. Skip history processing. " + this);
            }
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Save history of changed entities. " + this);
        }

        final PersistentStoreTransaction snapshot = changesTracker.getSnapshot();
        final Set<TransientEntity> changedEntities = changesTracker.getChangedEntities();

        for (final TransientEntity e : changedEntities.toArray(new TransientEntity[changedEntities.size()])) {
            if (!e.isNew() && !e.isRemoved()) {
                final EntityMetaData emd = modelMetaData.getEntityMetaData(e.getType());
                if (emd != null && TransientStoreUtil.getPersistentClassInstance(e, emd).evaluateSaveHistoryCondition(e)
                        && EntityMetaDataUtils.changesReflectHistory(emd, e, changesTracker)) {
                    if (log.isDebugEnabled()) {
                        log.debug("Save history of: " + e);
                    }
                    e.getPersistentEntity().newVersion(snapshot);

                    // !!! should be called after e.newVersion();
                    TransientStoreUtil.getPersistentClassInstance(e, emd).saveHistoryCallback(e);
                }
            }
        }
    }

    private void notifyFlushedListeners(TransientChangesTracker oldChangesTracker) {
        final Set<TransientEntityChange> changesDescription = oldChangesTracker.getChangesDescription();
        if (changesDescription.isEmpty()) {
            oldChangesTracker.dispose();
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Notify flushed listeners " + this);
        }

        store.forAllListeners(new TransientEntityStoreImpl.ListenerVisitor() {
            public void visit(TransientStoreSessionListener listener) {
                try {
                    listener.flushed(changesDescription);
                } catch (Exception e) {
                    if (log.isErrorEnabled()) {
                        log.error("Exception while inside listener [" + listener + "]", e);
                    }
                    // do not rethrow exception
                }
            }
        });

        //explicitly notify EventsMultiplexer - it will dispose changes tracker in async job
        final EventsMultiplexer ep = EventsMultiplexer.getInstance();
        if (ep != null) {
            try {
                ep.flushed(oldChangesTracker, changesDescription);
            } catch (Exception e) {
                if (log.isErrorEnabled()) {
                    log.error("Exception while inside events multiplexer", e);
                }
                oldChangesTracker.dispose();
            }
        } else {
            oldChangesTracker.dispose();
        }
    }

    private void notifyBeforeFlushListeners(final Set<TransientEntityChange> changes) {
        if (changes == null || changes.isEmpty()) return;

        if (log.isDebugEnabled()) {
            log.debug("Notify before flush listeners " + this);
        }

        store.forAllListeners(new TransientEntityStoreImpl.ListenerVisitor() {
            public void visit(TransientStoreSessionListener listener) {
                try {
                    listener.beforeFlush(changes);
                } catch (Exception e) {
                    if (log.isErrorEnabled()) {
                        log.error("Exception while inside listener [" + listener + "]", e);
                    }
                    // rethrow exception, because we are before constraints check
                    decodeException(e);
                }
            }
        });
    }

    @Deprecated
    private void notifyBeforeFlushAfterConstraintsCheckListeners() {
        final Set<TransientEntityChange> changesDescr = changesTracker.getChangesDescription();

        if (changesDescr == null || changesDescr.isEmpty()) return;

        if (log.isDebugEnabled()) {
            log.debug("Notify before flush after constraints check listeners " + this);
        }

        // check side effects in listeners
        final int changesCount = changes.size();
        store.forAllListeners(new TransientEntityStoreImpl.ListenerVisitor() {
            public void visit(TransientStoreSessionListener listener) {
                try {
                    listener.beforeFlushAfterConstraintsCheck(changesDescr);
                } catch (Exception e) {
                    if (log.isErrorEnabled()) {
                        log.error("Exception while inside listener [" + listener + "]", e);
                    }
                    // do not rethrow exceptipon, because we are after constaints check
                }
            }
        });

        if (changes.size() != changesCount) {
            throw new EntityStoreException("It's not allowed to change database inside listener.beforeFlushAfterConstraintsCheck() method.");
        }
    }

    protected TransientEntity newEntityImpl(final Entity persistent) {
        final EntityId entityId = persistent.getId();
        TransientEntity e = managedEntities.get(entityId);
        if (e == null) {
            e = new TransientEntityImpl((PersistentEntity)persistent, this.getStore());
            managedEntities.put(entityId, e);
        }
        return e;
    }

    void createEntity(@NotNull final TransientEntityImpl e) {
        final PersistentEntity persistentEntity = (PersistentEntity) getPersistentTransaction().newEntity(e.getType());
        e.setPersistentEntity(persistentEntity);
        changesTracker.entityAdded(e);

        addChange(new MyRunnable() {
            public boolean run() {
                getPersistentTransaction().saveEntity(persistentEntity);
                changesTracker.entityAdded(e);
                return true;
            }
        });
    }

    boolean setProperty(@NotNull final TransientEntity e, @NotNull final String propertyName, @NotNull final Comparable propertyNewValue) {
        return addChange(new MyRunnable() {
            @Override
            public boolean run() {
                if (e.getPersistentEntity().setProperty(propertyName, propertyNewValue)) {
                    final Comparable oldValue = getOriginalPropertyValue(e, propertyName);
                    if (propertyNewValue == oldValue || propertyNewValue.equals(oldValue)) {
                        changesTracker.removePropertyChanged(e, propertyName);
                    } else {
                        changesTracker.propertyChanged(e, propertyName);
                    }
                    return true;
                }
                return false;
            }
        }).run();
    }

    boolean deleteProperty(@NotNull final TransientEntity e, @NotNull final String propertyName) {
        return addChange(new MyRunnable() {
            @Override
            public boolean run() {
                if (e.getPersistentEntity().deleteProperty(propertyName)) {
                    final Comparable oldValue = getOriginalPropertyValue(e, propertyName);
                    if (oldValue == null) {
                        changesTracker.removePropertyChanged(e, propertyName);
                    } else {
                        changesTracker.propertyChanged(e, propertyName);
                    }
                    return true;
                }
                return false;
            }
        }).run();
    }

    void setBlob(@NotNull final TransientEntity e, @NotNull final String blobName, @NotNull final InputStream file) {
        addChange(new MyRunnable() {
            public boolean run() {
                e.getPersistentEntity().setBlob(blobName, file);
                changesTracker.propertyChanged(e, blobName);
                return true;
            }
        }).run();
    }

    void setBlob(@NotNull final TransientEntity e, @NotNull final String blobName, @NotNull final File file) {
        addChange(new MyRunnable() {
            public boolean run() {
                e.getPersistentEntity().setBlob(blobName, file);
                changesTracker.propertyChanged(e, blobName);
                return true;
            }
        }).run();
    }

    boolean setBlobString(@NotNull final TransientEntity e, @NotNull final String blobName, @NotNull final String newValue) {
        return addChange(new MyRunnable() {
            public boolean run() {
                if (e.getPersistentEntity().setBlobString(blobName, newValue)) {
                    final Comparable oldValue = getOriginalBlobStringValue(e, blobName);
                    if (newValue == oldValue || newValue.equals(oldValue)) {
                        changesTracker.removePropertyChanged(e, blobName);
                    } else {
                        changesTracker.propertyChanged(e, blobName);
                    }
                    return true;
                }
                return false;
            }
        }).run();
    }

    boolean deleteBlob(@NotNull final TransientEntity e, @NotNull final String blobName) {
        return addChange(new MyRunnable() {
            public boolean run() {
                if (e.getPersistentEntity().deleteBlob(blobName)) {
                    final InputStream oldValue = getOriginalBlobValue(e, blobName);
                    if (oldValue == null) {
                        changesTracker.removePropertyChanged(e, blobName);
                    } else {
                        changesTracker.propertyChanged(e, blobName);
                    }
                    return true;
                }
                return false;
            }
        }).run();
    }

    boolean setLink(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
        return addChange(new MyRunnable() {
            public boolean run() {
                final TransientEntity oldTarget = (TransientEntity) source.getLink(linkName);
                if (source.getPersistentEntity().setLink(linkName, target.getPersistentEntity())) {
                    changesTracker.linkChanged(source, linkName, target, oldTarget, true);
                    return true;
                }

                return false;
            }
        }).run();
    }

    boolean addLink(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
        return addChange(new MyRunnable() {
            public boolean run() {
                if (source.getPersistentEntity().addLink(linkName, target.getPersistentEntity())) {
                    changesTracker.linkChanged(source, linkName, target, null, true);
                    return true;
                }

                return false;
            }
        }).run();
    }

    boolean deleteLink(@NotNull final TransientEntity source, @NotNull final String linkName, @NotNull final TransientEntity target) {
        return addChange(new MyRunnable() {
            public boolean run() {
                if (source.getPersistentEntity().deleteLink(linkName, target.getPersistentEntity())) {
                    changesTracker.linkChanged(source, linkName, target, null, false);
                    return true;
                }

                return false;
            }
        }).run();
    }

    void deleteLinks(@NotNull final TransientEntity source, @NotNull final String linkName) {
        addChange(new MyRunnable() {
            public boolean run() {
                source.getPersistentEntity().deleteLinks(linkName);
                //TODO: somehow save that all links was removed
                changesTracker.entityChanged(source);
                return true;
            }
        }).run();
    }

    boolean deleteEntity(@NotNull final TransientEntity e) {
        return addChange(new MyRunnable() {
            public boolean run() {
                // remember index values first
                final Set<Pair<Index, List<Comparable>>> indexes = getIndexesValuesBeforeDelete(e);
                if (e.getPersistentEntity().delete()) {
                    deleteIndexes(e, indexes);
                    changesTracker.entityRemoved(e);
                }
                return true;
            }
        }).run();
    }

    void addChangeAndRun(MyRunnable change) {
        addChange(change).run();
    }

    @Override
    public void registerEntityIterator(@NotNull EntityIterator iterator) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deregisterEntityIterator(@NotNull EntityIterator iterator) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insertUniqueKey(@NotNull final Index index,
                                @NotNull final List<Comparable> propValues,
                                @NotNull final Entity entity) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteUniqueKey(@NotNull final Index index,
                                @NotNull final List<Comparable> propValues) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void saveEntity(@NotNull Entity entity) {
        throw new UnsupportedOperationException();
    }

    MyRunnable addChange(@NotNull final MyRunnable change) {
        changes.offer(change);
        return change;
    }

    enum State {
        Open("open"),
        Committed("committed"),
        Aborted("aborted");

        private String name;

        State(String name) {
            this.name = name;
        }
    }

    interface MyRunnable {
        boolean run();
    }

}
