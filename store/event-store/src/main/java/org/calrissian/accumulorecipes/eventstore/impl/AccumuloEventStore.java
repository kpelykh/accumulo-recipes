/*
 * Copyright (C) 2013 The Calrissian Authors
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
package org.calrissian.accumulorecipes.eventstore.impl;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.accumulo.core.data.Range.prefix;
import static org.calrissian.accumulorecipes.commons.support.Constants.DEFAULT_PARTITION_SIZE;
import static org.calrissian.accumulorecipes.commons.support.Constants.ONE_BYTE;
import static org.calrissian.accumulorecipes.commons.support.Constants.PREFIX_E;
import static org.calrissian.accumulorecipes.commons.util.Scanners.closeableIterable;
import static org.calrissian.mango.collect.CloseableIterables.transform;
import static org.calrissian.mango.types.LexiTypeEncoders.LEXI_TYPES;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.data.Range;
import org.apache.hadoop.io.Text;
import org.calrissian.accumulorecipes.commons.domain.Auths;
import org.calrissian.accumulorecipes.commons.domain.StoreConfig;
import org.calrissian.accumulorecipes.commons.iterators.EmptyEncodedRowFilter;
import org.calrissian.accumulorecipes.commons.iterators.MetadataExpirationFilter;
import org.calrissian.accumulorecipes.commons.iterators.SelectFieldsExtractor;
import org.calrissian.accumulorecipes.commons.iterators.TimeLimitingFilter;
import org.calrissian.accumulorecipes.commons.iterators.WholeColumnFamilyIterator;
import org.calrissian.accumulorecipes.commons.iterators.WholeColumnQualifierIterator;
import org.calrissian.accumulorecipes.commons.support.qfd.KeyValueIndex;
import org.calrissian.accumulorecipes.commons.support.qfd.planner.visitors.GlobalIndexVisitor;
import org.calrissian.accumulorecipes.eventstore.EventStore;
import org.calrissian.accumulorecipes.eventstore.support.EventGlobalIndexVisitor;
import org.calrissian.accumulorecipes.eventstore.support.EventQfdHelper;
import org.calrissian.accumulorecipes.eventstore.support.shard.DailyShardBuilder;
import org.calrissian.accumulorecipes.eventstore.support.shard.EventShardBuilder;
import org.calrissian.mango.collect.CloseableIterable;
import org.calrissian.mango.criteria.domain.Node;
import org.calrissian.mango.domain.Pair;
import org.calrissian.mango.domain.event.Event;
import org.calrissian.mango.domain.event.EventBuilder;
import org.calrissian.mango.domain.event.EventIdentifier;
import org.calrissian.mango.types.TypeRegistry;

/**
 * The Accumulo implementation of the EventStore which uses deterministic sharding to distribute ingest/queries over
 * the cloud to speed them up.
 */
public class AccumuloEventStore implements EventStore {

    public static final String DEFAULT_IDX_TABLE_NAME = "eventStore_index";
    public static final String DEFAULT_SHARD_TABLE_NAME = "eventStore_shard";

    public static final StoreConfig DEFAULT_STORE_CONFIG = new StoreConfig(3, 100000L, 10000L, 3);
    public static final EventShardBuilder DEFAULT_SHARD_BUILDER = new DailyShardBuilder(DEFAULT_PARTITION_SIZE);
    private final EventShardBuilder shardBuilder;
    private final EventQfdHelper helper;

    /**
     * Simple constructor uses common defaults for many input parameters
     * @param connector
     * @throws TableExistsException
     * @throws AccumuloSecurityException
     * @throws AccumuloException
     * @throws TableNotFoundException
     */
    public AccumuloEventStore(Connector connector) throws TableExistsException, AccumuloSecurityException, AccumuloException, TableNotFoundException {
        this(connector, DEFAULT_IDX_TABLE_NAME, DEFAULT_SHARD_TABLE_NAME, DEFAULT_STORE_CONFIG, LEXI_TYPES, DEFAULT_SHARD_BUILDER);
    }

    public AccumuloEventStore(Connector connector, String indexTable, String shardTable, StoreConfig config, TypeRegistry<String> typeRegistry,
        EventShardBuilder shardBuilder)
        throws TableExistsException, AccumuloSecurityException, AccumuloException, TableNotFoundException {
        checkNotNull(connector);
        checkNotNull(indexTable);
        checkNotNull(shardTable);
        checkNotNull(config);
        checkNotNull(typeRegistry);

        this.shardBuilder = shardBuilder;

        KeyValueIndex<Event> keyValueIndex = new KeyValueIndex(connector, indexTable, shardBuilder, config, typeRegistry);

        helper = new EventQfdHelper(connector, indexTable, shardTable, config, shardBuilder, typeRegistry, keyValueIndex);
    }

    /**
     * Free up any resources used by the store.
     *
     * @throws MutationsRejectedException
     */
    public void shutdown() throws MutationsRejectedException {
        helper.shutdown();
    }



    /**
     * Events get saved into a sharded table to parallelize queries & ingest. Since the data is temporal by default,
     * an index table allows the lookup of events by UUID only (when the event's timestamp is not known).
     *
     * @param events
     * @throws Exception
     */
    @Override
    public void save(Iterable<Event> events) {
        helper.save(events);
    }

    @Override
    public void flush() throws Exception {
        helper.flush();
    }

    /**
     * Shard ids for which to scan are generated from the given start and end time. The given query specifies
     * which events to return. It is propagated all the way down to the iterators so that the query is run in parallel
     * on each tablet that needs to be scanned.
     */
    @Override
    public CloseableIterable<Event> query(Date start, Date end, Node query, final Set<String> selectFields, Auths auths) {
        return query(start, end, Collections.singleton(""), query, selectFields, auths);
    }

    @Override
    public CloseableIterable<Event> query(Date start, Date end, Set<String> types, Node node, Set<String> selectFields, Auths auths) {
        checkNotNull(start);
        checkNotNull(end);
        checkNotNull(types);
        checkNotNull(node);
        checkNotNull(auths);

        BatchScanner indexScanner = helper.buildIndexScanner(auths.getAuths());
        GlobalIndexVisitor globalIndexVisitor = new EventGlobalIndexVisitor(start, end, types, indexScanner, shardBuilder);
        BatchScanner scanner = helper.buildShardScanner(auths.getAuths());

        IteratorSetting timeFilter = new IteratorSetting(5, TimeLimitingFilter.class);
        TimeLimitingFilter.setCurrentTime(timeFilter, end.getTime());
        TimeLimitingFilter.setTTL(timeFilter, end.getTime() - start.getTime());
        scanner.addScanIterator(timeFilter);

        CloseableIterable<Event> events = helper.query(scanner, globalIndexVisitor, types, node, helper.buildQueryXform(), selectFields, auths);
        indexScanner.close();

        return events;
    }

    @Override
    public CloseableIterable<Event> query(Date start, Date end, Node node, Auths auths) {
        return query(start, end, node, null, auths);
    }

    @Override
    public CloseableIterable<Event> query(Date start, Date end, Set<String> types, Node node, Auths auths) {
        return query(start, end, types, node, null, auths);
    }

    /**
     * This method will batch get a bunch of events by uuid (and optionally timestamp). If another store is used to
     * index into events in this store in a specially designed way (i.e. getting the last-n events, etc...) then
     * the most optimal way to store the index would be the UUID and the timestamp. However, if all that is known
     * about an event is the uuid, this method will do the extra fetch of the timestamp from the index table.
     */
    @Override
    public CloseableIterable<Event> get(Collection<EventIdentifier> uuids, Set<String> selectFields, Auths auths) {
        checkNotNull(uuids);
        checkNotNull(auths);
        try {

            BatchScanner eventScanner = helper.buildShardScanner(auths.getAuths());
            Collection<Range> eventRanges = new LinkedList<Range>();

            for (EventIdentifier curIndex : uuids) {
                String shardId = shardBuilder.buildShard(EventBuilder.create(curIndex.getType(), curIndex.getId(), curIndex.getTimestamp()).build());
                eventRanges.add(prefix(shardId, PREFIX_E + ONE_BYTE + curIndex.getType() + ONE_BYTE + curIndex.getId()));
            }

            eventScanner.setRanges(eventRanges);

            if (selectFields != null && selectFields.size() > 0) {
                IteratorSetting iteratorSetting = new IteratorSetting(25, SelectFieldsExtractor.class);
                SelectFieldsExtractor.setSelectFields(iteratorSetting, selectFields);
                eventScanner.addScanIterator(iteratorSetting);
            }

            IteratorSetting expirationFilter = new IteratorSetting(23, "metaExpiration", MetadataExpirationFilter.class);
            eventScanner.addScanIterator(expirationFilter);
            IteratorSetting emptyDataFilter = new IteratorSetting(24, "emptyFilter", EmptyEncodedRowFilter.class);
            eventScanner.addScanIterator(emptyDataFilter);

            IteratorSetting setting = new IteratorSetting(26, WholeColumnQualifierIterator.class);
            eventScanner.addScanIterator(setting);

            return transform(closeableIterable(eventScanner), helper.buildWholeColFXform());
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CloseableIterable<Event> getAllByType(Date start, Date stop, Set<String> types, Set<String> selectFields, Auths auths) {
        checkNotNull(types);
        checkNotNull(auths);
        try {

            BatchScanner scanner = helper.buildShardScanner(auths.getAuths());

            Collection<Range> ranges = new LinkedList<Range>();
            for (String type : types) {
                Set<Text> shards = shardBuilder.buildShardsInRange(start, stop);
                for (Text shard : shards)
                    ranges.add(prefix(shard.toString(), PREFIX_E + ONE_BYTE + type + ONE_BYTE));
            }

            scanner.setRanges(ranges);

            if (selectFields != null && selectFields.size() > 0) {
                IteratorSetting iteratorSetting = new IteratorSetting(16, SelectFieldsExtractor.class);
                SelectFieldsExtractor.setSelectFields(iteratorSetting, selectFields);
                scanner.addScanIterator(iteratorSetting);
            }

            IteratorSetting timeFilter = new IteratorSetting(12, TimeLimitingFilter.class);
            TimeLimitingFilter.setCurrentTime(timeFilter, stop.getTime());

            TimeLimitingFilter.setTTL(timeFilter, stop.getTime() - start.getTime());
            scanner.addScanIterator(timeFilter);

            IteratorSetting setting = new IteratorSetting(18, WholeColumnFamilyIterator.class);
            scanner.addScanIterator(setting);

            IteratorSetting expirationFilter = new IteratorSetting(13, "metaExpiration", MetadataExpirationFilter.class);
            scanner.addScanIterator(expirationFilter);
            IteratorSetting emptyDataFilter = new IteratorSetting(14, "emptyFilter", EmptyEncodedRowFilter.class);
            scanner.addScanIterator(emptyDataFilter);

            return transform(closeableIterable(scanner), helper.buildWholeColFXform());
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CloseableIterable<Event> getAllByType(Date start, Date stop, Set<String> types, Auths auths) {
        return getAllByType(start, stop, types, null, auths);
    }

    @Override
    public CloseableIterable<Event> get(Collection<EventIdentifier> indexes, Auths auths) {
        return get(indexes, auths);
    }

    public CloseableIterable<Pair<String,String>> uniqueKeys(String prefix, String type, Auths auths) {
        return helper.getKeyValueIndex().uniqueKeys(prefix, type, auths);
    }

    public CloseableIterable<Object> uniqueValuesForKey(String prefix, String type, String alias, String key, Auths auths) {
        return helper.getKeyValueIndex().uniqueValuesForKey(prefix, type, alias, key, auths);
    }

    public CloseableIterable<String> getTypes(String prefix, Auths auths) {
        return helper.getKeyValueIndex().getTypes(prefix, auths);
    }
}
