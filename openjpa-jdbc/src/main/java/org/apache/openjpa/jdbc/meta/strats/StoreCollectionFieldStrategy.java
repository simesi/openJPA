/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.    
 */
package org.apache.openjpa.jdbc.meta.strats;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.openjpa.enhance.PersistenceCapable;
import org.apache.openjpa.jdbc.conf.JDBCConfiguration;
import org.apache.openjpa.jdbc.kernel.JDBCFetchConfiguration;
import org.apache.openjpa.jdbc.kernel.JDBCFetchConfigurationImpl;
import org.apache.openjpa.jdbc.kernel.JDBCStore;
import org.apache.openjpa.jdbc.kernel.JDBCStoreManager;
import org.apache.openjpa.jdbc.meta.ClassMapping;
import org.apache.openjpa.jdbc.meta.FieldMapping;
import org.apache.openjpa.jdbc.meta.FieldStrategy;
import org.apache.openjpa.jdbc.meta.ValueMapping;
import org.apache.openjpa.jdbc.schema.Column;
import org.apache.openjpa.jdbc.schema.ForeignKey;
import org.apache.openjpa.jdbc.sql.Joins;
import org.apache.openjpa.jdbc.sql.LogicalUnion;
import org.apache.openjpa.jdbc.sql.Result;
import org.apache.openjpa.jdbc.sql.Select;
import org.apache.openjpa.jdbc.sql.SelectExecutor;
import org.apache.openjpa.jdbc.sql.SelectImpl;
import org.apache.openjpa.jdbc.sql.Union;
import org.apache.openjpa.kernel.OpenJPAStateManager;
import org.apache.openjpa.lib.log.Log;
import org.apache.openjpa.lib.util.Localizer;
import org.apache.openjpa.meta.ClassMetaData;
import org.apache.openjpa.meta.JavaTypes;
import org.apache.openjpa.util.ChangeTracker;
import org.apache.openjpa.util.Id;
import org.apache.openjpa.util.Proxy;

/**
 * Base class for strategies that are stored as a collection, even if
 * their field value is something else. Handles data loading and basic query
 * functionality. Subclasses must implement abstract methods and
 * insert/update/delete behavior as well as overriding
 * {@link FieldStrategy#toDataStoreValue}, {@link FieldStrategy#join}, and
 * {@link FieldStrategy#joinRelation} if necessary.
 *
 * @author Abe White
 */
public abstract class StoreCollectionFieldStrategy
    extends ContainerFieldStrategy {

    private static final Localizer _loc = Localizer.forPackage
        (StoreCollectionFieldStrategy.class);
    
    /**
     * Return the foreign key used to join to the owning field for the given
     * element mapping from {@link #getIndependentElementMappings} (or null).
     */
    protected abstract ForeignKey getJoinForeignKey(ClassMapping elem);

    /**
     * Implement this method to select the elements of this field for the
     * given element mapping from {@link #getIndependentElementMappings}
     * (or null). Elements of the result will be loaded with
     * {@link #loadElement}.
     */
    protected abstract void selectElement(Select sel, ClassMapping elem,
        JDBCStore store, JDBCFetchConfiguration fetch, int eagerMode,
        Joins joins);

    /**
     * Load an element of the collection. The given state manager might be
     * null if the load is for a projection or for processing eager parallel
     * results.
     */
    protected abstract Object loadElement(OpenJPAStateManager sm,
        JDBCStore store, JDBCFetchConfiguration fetch, Result res, Joins joins)
        throws SQLException;

    /**
     * Join this value's table to the table for the given element mapping
     * from {@link #getIndependentElementMappings} (or null).
     *
     * @see FieldMapping#joinRelation
     */
    protected abstract Joins joinElementRelation(Joins joins,
        ClassMapping elem);

    /**
     * Join to the owning field table for the given element mapping from
     * {@link #getIndependentElementMappings} (or null).
     */
    protected abstract Joins join(Joins joins, ClassMapping elem);

    /**
     * Return a large result set proxy for this field.
     */
    protected abstract Proxy newLRSProxy();

    /**
     * Convert the field value to a collection. Handles collections and
     * arrays by default.
     */
    protected Collection toCollection(Object val) {
        if (field.getTypeCode() == JavaTypes.COLLECTION)
            return (Collection) val;
        return JavaTypes.toList(val, field.getElement().getType(), false);
    }

    /**
     * Add an item to the data structure representing a field value.
     * By default, assumes the structure is a collection.
     */
    protected void add(JDBCStore store, Object coll, Object obj) {
        ((Collection) coll).add(obj);
    }

    /**
     * Returns the first independent element mapping, or null.
     */
    private ClassMapping getDefaultElementMapping(boolean traverse) {
        ClassMapping[] elems = getIndependentElementMappings(traverse);
        return (elems.length == 0) ? null : elems[0];
    }

    public int supportsSelect(Select sel, int type, OpenJPAStateManager sm,
        JDBCStore store, JDBCFetchConfiguration fetch) {
        if (field.isLRS())
            return 0;
        if (type == Select.EAGER_PARALLEL)
            return Math.max(1, getIndependentElementMappings(true).length);
        if (type != Select.EAGER_INNER && type != Select.EAGER_OUTER)
            return 0;
        if (getIndependentElementMappings(true).length > 1)
            return 0;
        return (type == Select.EAGER_INNER || store.getDBDictionary().
            canOuterJoin(sel.getJoinSyntax(), getJoinForeignKey
                (getDefaultElementMapping(false)))) ? 1 : 0;
    }

    public void selectEagerParallel(SelectExecutor sel,
        final OpenJPAStateManager sm, final JDBCStore store,
        final JDBCFetchConfiguration fetch, final int eagerMode) {
        if (!(sel instanceof Union))
            selectEager((Select) sel, getDefaultElementMapping(true), sm,
                store, fetch, eagerMode, true, false);
        else {
            final ClassMapping[] elems = getIndependentElementMappings(true);
            Union union = (Union) sel;
            if (fetch.getSubclassFetchMode(field.getElementMapping().
                getTypeMapping()) != fetch.EAGER_JOIN)
                union.abortUnion();
            union.select(new Union.Selector() {
                public void select(Select sel, int idx) {
                    selectEager(sel, elems[idx], sm, store, fetch, eagerMode, 
                        true, false);
                }
            });
        }
    }

    public void selectEagerJoin(Select sel, OpenJPAStateManager sm,
        JDBCStore store, JDBCFetchConfiguration fetch, int eagerMode) {
        // we limit further eager fetches to joins, because after this point
        // the select has been modified such that parallel clones may produce
        // invalid sql
        boolean outer = field.getNullValue() != FieldMapping.NULL_EXCEPTION;
        // force inner join for inner join fetch 
        if (fetch.hasFetchInnerJoin(field.getFullName(false)))
            outer = false;
        selectEager(sel, getDefaultElementMapping(true), sm, store, fetch, 
            JDBCFetchConfiguration.EAGER_JOIN, false,
            outer);
    }

    public boolean isEagerSelectToMany() {
        return true;
    }

    /**
     * Select our data eagerly.
     */
    private void selectEager(Select sel, ClassMapping elem,
        OpenJPAStateManager sm, JDBCStore store, JDBCFetchConfiguration fetch,
        int eagerMode, boolean selectOid, boolean outer) {
        // force distinct if there was a to-many join to avoid dups, but
        // if this is a parallel select don't make distinct based on the
        // eager joins alone if the original wasn't distinct
        if (eagerMode == JDBCFetchConfiguration.EAGER_PARALLEL) {
            if (sel.hasJoin(true))
                sel.setDistinct(true);
            else if (!sel.isDistinct())
                sel.setDistinct(false); // set explicitly so remembered
        }

        // set a variable name that does not conflict with any in the query;
        // using a variable guarantees that the selected data will use different
        // aliases and joins than any existing WHERE conditions on this field
        // that might otherwise limit the elements of the field that match
        if (selectOid)
            sel.orderByPrimaryKey(field.getDefiningMapping(), true, true);
        Joins joins = sel.newJoins().setVariable("*");
        joins = join(joins, elem);

        // order, ref cols
        if (field.getOrderColumn() != null || field.getOrders().length > 0
            || !selectOid) {
            if (outer)
                joins = sel.outer(joins);
            if (!selectOid) {
                Column[] refs = getJoinForeignKey(elem).getColumns();
                if (requiresOrderBy()) {
                    sel.orderBy(refs, true, joins, true);
                } else {
                    sel.select(refs, joins);
                }
            }
            field.orderLocal(sel, elem, joins);
        }

        // select data
        joins = joinElementRelation(joins, elem);
        if (outer)
            joins = sel.outer(joins);
        field.orderRelation(sel, elem, joins);
        selectElement(sel, elem, store, fetch, eagerMode, joins);
    }

    public Object loadEagerParallel(OpenJPAStateManager sm, JDBCStore store,
        JDBCFetchConfiguration fetch, Object res)
        throws SQLException {
        // process batched results if we haven't already
        Map rels;
        if (res instanceof Result)
            rels = processEagerParallelResult(sm, store, fetch, (Result) res);
        else
            rels = (Map) res;

        // look up the collection for this oid, and store in instance
        Object coll = rels.remove(sm.getObjectId());
        if (field.getTypeCode() == JavaTypes.ARRAY)
            sm.storeObject(field.getIndex(), JavaTypes.toArray
                ((Collection) coll, field.getElement().getType()));
        else {
            if (coll == null)
                coll = sm.newProxy(field.getIndex());
            sm.storeObject(field.getIndex(), coll);
        }
        return rels;
    }

    /**
     * Process the given batched result.
     */
    private Map processEagerParallelResult(OpenJPAStateManager sm,
        JDBCStore store, JDBCFetchConfiguration fetch, Result res)
        throws SQLException {
        // do same joins as for load
        //### cheat: we know typical result joins only care about the relation
        //### path; thus we can ignore different mappings
        ClassMapping elem = getDefaultElementMapping(true);
        Joins dataJoins = res.newJoins().setVariable("*");
        dataJoins = join(dataJoins, elem);
        dataJoins = joinElementRelation(dataJoins, elem);
        Joins orderJoins = null;
        if (field.getOrderColumn() != null) {
            orderJoins = res.newJoins().setVariable("*");
            orderJoins = join(orderJoins, elem);
        }

        Map rels = new HashMap();
        ClassMapping ownerMapping = field.getDefiningMapping();
        Object nextOid, oid = null;
        Object coll = null;
        int seq = 0;
        while (res.next()) {
            // extract the owner id value
            nextOid = getNextObjectId(ownerMapping, store, res, oid);
            if (nextOid != oid) {
                // if the old coll was an ordered tracking proxy, set
                // its seq value to the last order val we read
                if (seq != 0 && coll instanceof Proxy)
                    ((Proxy) coll).getChangeTracker().setNextSequence(seq);

                // start a new collection
                oid = nextOid;
                seq = 0;
                if (field.getTypeCode() == JavaTypes.ARRAY)
                    coll = new ArrayList();
                else
                    coll = sm.newProxy(field.getIndex());
                rels.put(oid, coll);
            }

            if (field.getOrderColumn() != null)
                seq = res.getInt(field.getOrderColumn(), orderJoins) + 1;

            // for inverse relation field
            setMappedBy(oid, res);
            Object val = loadElement(null, store, fetch, res, dataJoins);
            add(store, coll, val);
        }
        res.close();

        return rels;
    }

    private void setMappedBy(Object oid, Result res) {
        //  for inverse toOne relation field
        FieldMapping mappedByFieldMapping = field.getMappedByMapping();
        
        if (mappedByFieldMapping != null) {
            ValueMapping val = mappedByFieldMapping.getValueMapping();
            ClassMetaData decMeta = val.getTypeMetaData();
            // this inverse field does not have corresponding classMapping
            // its value may be a collection/map etc.
            if (decMeta == null) 
                return;
            
            res.setMappedByFieldMapping(mappedByFieldMapping);
            res.setMappedByValue(oid);
        }
    }

    private void setMappedBy(Object oid, OpenJPAStateManager sm, Object coll,
        Result res) {
        // for inverseEager field
        FieldMapping mappedByFieldMapping = field.getMappedByMapping();
        PersistenceCapable mappedByValue = null;
        
        if (mappedByFieldMapping != null) {
            ValueMapping val = mappedByFieldMapping.getValueMapping();
            ClassMetaData decMeta = val.getTypeMetaData();
            // this inverse field does not have corresponding classMapping
            // its value may be a collection/map etc.
            if (decMeta == null) 
                return;
        	
            if (oid.equals(sm.getObjectId())) {
                mappedByValue = sm.getPersistenceCapable();
                res.setMappedByFieldMapping(mappedByFieldMapping);
                res.setMappedByValue(mappedByValue);
            } else if (coll instanceof Collection && 
                ((Collection) coll).size() > 0) {
                // Customer (1) <--> Orders(n)
                // coll contains the values of the toMany field (Orders)
                // get the StateManager of this toMany value
                // and find the value of the inverse mappedBy field (Customer)
                // for this toMacdny field
                PersistenceCapable pc = (PersistenceCapable)
                    ((Collection) coll).iterator().next();
                OpenJPAStateManager sm1 = (OpenJPAStateManager) pc.
                    pcGetStateManager();
                
                ClassMapping clm = ((ClassMapping) sm1.getMetaData());
                FieldMapping fm = (FieldMapping) clm.getField(
                    mappedByFieldMapping.getName());
                if (fm == mappedByFieldMapping)
                    res.setMappedByValue(sm1.fetchObject(fm.getIndex()));
            } else {
                res.setMappedByValue(null);
            }
        }        
    }

    /**
     * Extract the oid value from the given result. If the next oid is the
     * same as the given one, returns the given JVM instance.
     */
    private Object getNextObjectId(ClassMapping owner, JDBCStore store,
        Result res, Object oid)
        throws SQLException {
        // if this is a datastore id class we can avoid creating a new oid
        // object for the common case
        if (oid != null && owner.getIdentityType() == ClassMapping.ID_DATASTORE
            && owner.isPrimaryKeyObjectId(true)) {
            long nid = res.getLong(owner.getPrimaryKeyColumns()[0]);
            long id = ((Id) oid).getId();
            return (nid == id) ? oid : store.newDataStoreId(nid, owner, true);
        }

        Object noid = owner.getObjectId(store, res, null, true, null);
        if (noid == null)
            return null;
        return (noid.equals(oid)) ? oid : noid;
    }

    public void loadEagerJoin(OpenJPAStateManager sm, JDBCStore store,
        JDBCFetchConfiguration fetch, Result res)
        throws SQLException {
        // initialize field value
        Object coll;
        if (field.getTypeCode() == JavaTypes.ARRAY)
            coll = new ArrayList();
        else
            coll = sm.newProxy(field.getIndex());

        Joins dataJoins = null;
        Joins refJoins = res.newJoins().setVariable("*");
        join(refJoins, false);

        ClassMapping ownerMapping = field.getDefiningMapping();
        Object ref = null;
        int seq = 0;
        int typeIdx = res.indexOf();
        for (int i = 0; true; i++) {
            // extract the owner id value
            ref = getNextRef(ownerMapping, store, res, ref, refJoins);
            if (ref == null) {
                // if the old coll was an ordered tracking proxy, set
                // its seq value to the last order val we read
                if (seq != 0 && coll instanceof Proxy)
                    ((Proxy) coll).getChangeTracker().setNextSequence(seq);
                if (i != 0)
                    res.pushBack();
                break;
            }

            // do same joins as for load
            if (dataJoins == null) {
                dataJoins = res.newJoins().setVariable("*");
                dataJoins = join(dataJoins, false);
                dataJoins = joinRelation(dataJoins, false, false);
            }

            if (field.getOrderColumn() != null)
                seq = res.getInt(field.getOrderColumn(), refJoins) + 1;
            res.setBaseMapping(null);
            add(store, coll, loadElement(sm, store, fetch, res, dataJoins));
            if (!res.next() || res.indexOf() != typeIdx) {
                res.pushBack();
                break;
            }
        }

        // load the collection into the object
        if (field.getTypeCode() == JavaTypes.ARRAY)
            sm.storeObject(field.getIndex(), JavaTypes.toArray
                ((Collection) coll, field.getElement().getType()));
        else
            sm.storeObject(field.getIndex(), coll);
    }

    /**
     * Extract the reference column value(s) from the given result. If the
     * extracted result is the same as the current one or the current
     * one is null, returns the extracted result. Else returns null.
     */
    private Object getNextRef(ClassMapping mapping, JDBCStore store,
        Result res, Object ref, Joins refJoins)
        throws SQLException {
        Column[] cols = getJoinForeignKey(getDefaultElementMapping(false)).
            getColumns();
        Object val;
        if (cols.length == 1) {
            val = res.getObject(cols[0], null, refJoins);
            if (val == null || (ref != null && !val.equals(ref)))
                return null;
            return val;
        }

        Object[] refs = (Object[]) ref;
        if (refs == null)
            refs = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            val = res.getObject(cols[i], null, refJoins);
            if (val == null)
                return null;
            if (refs[i] != null && !val.equals(refs[i]))
                return null;
            refs[i] = val;
        }
        return refs;
    }

    public void load(final OpenJPAStateManager sm, final JDBCStore store,
        final JDBCFetchConfiguration fetch)
        throws SQLException {
        if (field.isLRS()) {
            Proxy coll = newLRSProxy();

            // if this is ordered we need to know the next seq to use in case
            // objects are added to the collection
            if (field.getOrderColumn() != null) {
                // we don't allow ordering table per class one-many's, so
                // we know we don't need a union
                Select sel = store.getSQLFactory().newSelect();
                sel.setAggregate(true);
                StringBuffer sql = new StringBuffer();
                sql.append("MAX(").
                    append(sel.getColumnAlias(field.getOrderColumn())).
                    append(")");
                sel.select(sql.toString(), field);
                ClassMapping rel = getDefaultElementMapping(false);
                sel.whereForeignKey(getJoinForeignKey(rel),
                    sm.getObjectId(), field.getDefiningMapping(), store);

                Result res = sel.execute(store, fetch);
                try {
                    res.next();
                    coll.getChangeTracker().setNextSequence
                        (res.getInt(field) + 1);
                } finally {
                    res.close();
                }
            }
            sm.storeObjectField(field.getIndex(), coll);
            return;
        }

        //cache union for field here
        // select data for this sm
        boolean found = true;
        final ClassMapping[] elems = getIndependentElementMappings(true);
        final Joins[] resJoins = new Joins[Math.max(1, elems.length)];
        List parmList = null;
        Union union = null;
        SelectImpl sel = null;
        Map<JDBCStoreManager.SelectKey, Object[]> storeCollectionUnionCache = null;
        JDBCStoreManager.SelectKey selKey = null;
        if (!((JDBCStoreManager)store).isQuerySQLCacheOn() || elems.length > 1)
            union = newUnion(sm, store, fetch, elems, resJoins);
        else {
            parmList = new ArrayList();
            JDBCFetchConfiguration fetchClone = new JDBCFetchConfigurationImpl();
            fetchClone.copy(fetch);
           
            // to specify the type so that no cast is needed
            storeCollectionUnionCache = ((JDBCStoreManager)store).
                getCacheMapFromQuerySQLCache(StoreCollectionFieldStrategy.class);
            selKey = 
                new JDBCStoreManager.SelectKey(null, field, fetchClone);
            Object[] objs = storeCollectionUnionCache.get(selKey);
            if (objs != null) {
                union = (Union) objs[0];
                resJoins[0] = (Joins) objs[1];
            }
            else {
                synchronized(storeCollectionUnionCache) {
                    objs = storeCollectionUnionCache.get(selKey);
                    if (objs == null) {
                        // select data for this sm
                        union = newUnion(sm, store, fetch, elems, resJoins);
                        found = false;
                    } else {
                        union = (Union) objs[0];
                        resJoins[0] = (Joins) objs[1];
                    }

                    sel = ((LogicalUnion.UnionSelect)union.getSelects()[0]).
                        getDelegate();
                    if (sel.getSQL() == null) {
                    	((SelectImpl)sel).setSQL(store, fetch);
                        found = false;
                    }

                    // only cache the union when elems length is 1 for now
                    if (!found) { 
                        Object[] objs1 = new Object[2];
                        objs1[0] = union;
                        objs1[1] = resJoins[0];
                        ((JDBCStoreManager)store).addToSqlCache(
                            storeCollectionUnionCache, selKey, objs1);
                     }
                }
            }
            
            Log log = store.getConfiguration().
                getLog(JDBCConfiguration.LOG_JDBC);
            if (log.isTraceEnabled()) {
                if (found)
                    log.trace(_loc.get("cache-hit", field, this.getClass()));
                else
                    log.trace(_loc.get("cache-missed", field, this.getClass())); 
            }
            
            ClassMapping mapping = field.getDefiningMapping();
            Object oid = sm.getObjectId();
            Column[] cols = mapping.getPrimaryKeyColumns();
            if (sel == null)
                sel = ((LogicalUnion.UnionSelect)union.getSelects()[0]).
                getDelegate();

            sel.wherePrimaryKey(mapping, cols, cols, oid, store, 
                	null, null, parmList);
            List nonFKParams = sel.getSQL().getNonFKParameters();
            if (nonFKParams != null && nonFKParams.size() > 0) 
                parmList.addAll(nonFKParams);
        }
        
        // create proxy
        Object coll;
        ChangeTracker ct = null;
        if (field.getTypeCode() == JavaTypes.ARRAY)
            coll = new ArrayList();
        else {
            coll = sm.newProxy(field.getIndex());
            if (coll instanceof Proxy)
                ct = ((Proxy) coll).getChangeTracker();
        }

        // load values
        Result res = union.execute(store, fetch, parmList);
        try {
            int seq = -1;
            while (res.next()) {
                if (ct != null && field.getOrderColumn() != null)
                    seq = res.getInt(field.getOrderColumn());
                setMappedBy(sm.getObjectId(), sm, coll, res);
               	add(store, coll, loadElement(sm, store, fetch, res,
           	        resJoins[res.indexOf()]));
            }
            if (ct != null && field.getOrderColumn() != null)
                ct.setNextSequence(seq + 1);
        } finally {
            res.close();
        }

        // set into sm
        if (field.getTypeCode() == JavaTypes.ARRAY)
            sm.storeObject(field.getIndex(), JavaTypes.toArray
                ((Collection) coll, field.getElement().getType()));
        else
            sm.storeObject(field.getIndex(), coll);
    }

    protected Union newUnion(final OpenJPAStateManager sm, final JDBCStore store,
        final JDBCFetchConfiguration fetch, final ClassMapping[] elems,
        final Joins[] resJoins) {
        Union union = store.getSQLFactory().newUnion
        (Math.max(1, elems.length));
        union.select(new Union.Selector() {
            public void select(Select sel, int idx) {
                ClassMapping elem = (elems.length == 0) ? null : elems[idx];
                resJoins[idx] = selectAll(sel, elem, sm, store, fetch,
                        JDBCFetchConfiguration.EAGER_PARALLEL);
            }
        });
        return union;
    }
    
    /**
     * Select data for loading, starting in field table.
     */
    protected Joins selectAll(Select sel, ClassMapping elem,
        OpenJPAStateManager sm, JDBCStore store, JDBCFetchConfiguration fetch,
        int eagerMode) {
        sel.whereForeignKey(getJoinForeignKey(elem), sm.getObjectId(),
            field.getDefiningMapping(), store);

        // order first, then select so that if the projection introduces
        // additional ordering, it will be after our required ordering
        field.orderLocal(sel, elem, null);
        Joins joins = joinElementRelation(sel.newJoins(), elem);
        field.orderRelation(sel, elem, joins);
        selectElement(sel, elem, store, fetch, eagerMode, joins);
        return joins;
    }

    public Object loadProjection(JDBCStore store, JDBCFetchConfiguration fetch,
        Result res, Joins joins)
        throws SQLException {
        return loadElement(null, store, fetch, res, joins);
    }

    protected ForeignKey getJoinForeignKey() {
        return getJoinForeignKey(getDefaultElementMapping(false));
    }
    
    boolean requiresOrderBy() {
    	return List.class.isAssignableFrom(field.getProxyType());
    }
}
