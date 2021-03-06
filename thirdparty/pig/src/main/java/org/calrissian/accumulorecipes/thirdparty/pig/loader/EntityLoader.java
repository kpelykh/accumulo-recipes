/*
* Copyright (C) 2014 The Calrissian Authors
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
package org.calrissian.accumulorecipes.thirdparty.pig.loader;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Sets.newHashSet;
import static java.util.Arrays.asList;
import static org.apache.accumulo.core.client.mapreduce.lib.impl.ConfiguratorBase.isConnectorInfoSet;
import static org.apache.commons.lang.StringUtils.splitPreserveAllTokens;
import static org.calrissian.mango.types.SimpleTypeEncoders.SIMPLE_TYPES;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

import com.google.common.collect.Iterators;
import com.google.common.collect.Multimap;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.mapreduce.AccumuloInputFormat;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.pig.LoadFunc;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigSplit;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.calrissian.accumulorecipes.commons.hadoop.RecordReaderValueIterator;
import org.calrissian.accumulorecipes.commons.support.GettableTransform;
import org.calrissian.accumulorecipes.entitystore.hadoop.EntityInputFormat;
import org.calrissian.accumulorecipes.entitystore.model.EntityWritable;
import org.calrissian.accumulorecipes.thirdparty.pig.support.AttributeStoreIterator;
import org.calrissian.mango.criteria.builder.QueryBuilder;
import org.calrissian.mango.domain.entity.Entity;
import org.calrissian.mango.types.TypeRegistry;
import org.calrissian.mango.uri.support.UriUtils;

public class EntityLoader extends LoadFunc implements Serializable {

    public static final String USAGE = "Usage: entity://indexTable/shardTable?user=&pass=&inst=&zk=&types=&auths=[&fields=]";

    protected transient AttributeStoreIterator<Entity> itr;
    protected final TypeRegistry<String> registry = SIMPLE_TYPES;
    protected final QueryBuilder qb;

    public EntityLoader(String query) {
        checkArgument(!query.equals(""));
        checkNotNull(query);
        try {
            // call groovy expressions from Java code
            Binding binding = new Binding();
            binding.setVariable("q", QueryBuilder.create());
            GroovyShell shell = new GroovyShell(binding);
            qb = (QueryBuilder) shell.evaluate(query);
        } catch(Exception e) {
            throw new RuntimeException("There was an error parsing the groovy query string. ");
        }

    }

    public EntityLoader() { // this will load all entities by type
        this.qb = null;
    }

    @Override
    public void setLocation(String uri, Job job) throws IOException {

        Configuration conf = job.getConfiguration();

        if(!isConnectorInfoSet(AccumuloInputFormat.class, job.getConfiguration())) {

            String path = uri.substring(uri.indexOf("://")+3, uri.indexOf("?"));

            String[] indexAndShardTable = StringUtils.splitPreserveAllTokens(path, "/");
            if(indexAndShardTable.length != 2)
                throw new IOException("Path portion of URI must contain the index and shard tables. " + USAGE);

            if(uri.startsWith("entity")) {
                String queryPortion = uri.substring(uri.indexOf("?")+1, uri.length());
                Multimap<String, String> queryParams = UriUtils.splitQuery(queryPortion);

                String accumuloUser = getProp(queryParams, "user");
                String accumuloPass = getProp(queryParams, "pass");
                String accumuloInst = getProp(queryParams, "inst");
                String zookeepers = getProp(queryParams, "zk");
                if(accumuloUser == null || accumuloPass == null || accumuloInst == null || zookeepers == null)
                    throw new IOException("Some Accumulo connection information is missing. Must supply username, password, instance, and zookeepers. " + USAGE);

                String types = getProp(queryParams, "types");
                if(types == null)
                    throw new IOException("A comma-separated list of entity types to load is required. " + USAGE);

                String auths = getProp(queryParams, "auths");
                if(auths == null)
                    auths = "";     // default auths to empty

                String selectFields = getProp(queryParams, "fields");

                Set<String> fields = selectFields != null ? newHashSet(asList(splitPreserveAllTokens(selectFields, ","))) : null;
                Set<String> entitytypes = newHashSet(asList(splitPreserveAllTokens(types, ",")));

                EntityInputFormat.setZooKeeperInstance(job, accumuloInst, zookeepers);
                try {
                    EntityInputFormat.setInputInfo(job, accumuloUser, accumuloPass.getBytes(), new Authorizations(auths.getBytes()));
                } catch (AccumuloSecurityException e) {
                    throw new RuntimeException(e);
                }
                try {
                    if(qb != null)
                        EntityInputFormat.setQueryInfo(job, entitytypes, qb.build());
                    else
                        EntityInputFormat.setQueryInfo(job, entitytypes);

                    if(fields != null)
                        EntityInputFormat.setSelectFields(conf, fields);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else {
                throw new IOException("Location uri must begin with event://");
            }
        }
    }

    private String getProp(Multimap<String, String> queryParams, String propKey) {
        Collection<String> props = queryParams.get(propKey);
        if (props.size() > 0)
            return props.iterator().next();
        return null;
    }

    @Override
    public InputFormat getInputFormat() throws IOException {
        return new EntityInputFormat();
    }

    @Override
    public void prepareToRead(RecordReader recordReader, PigSplit pigSplit) throws IOException {
        RecordReaderValueIterator<Key, EntityWritable> rri = new RecordReaderValueIterator<Key, EntityWritable>(recordReader);
        Iterator<Entity> xformed = Iterators.transform(rri, new GettableTransform<Entity>());
        itr = new AttributeStoreIterator<Entity>(xformed);
    }


    @Override
    public Tuple getNext() throws IOException {

        if(!itr.hasNext())
            return null;

        org.calrissian.mango.domain.Attribute entityAttribute = itr.next();

        /**
         * Create the pig attribute and hydrate with entity details. The format of the attribute is as follows:
         * (type, id, key, datatype, value, visiblity)
         */
        Tuple t = TupleFactory.getInstance().newTuple();
        t.append(itr.getTopStore().getType());
        t.append(itr.getTopStore().getId());
        t.append(entityAttribute.getKey());
        t.append(registry.getAlias(entityAttribute.getValue()));
        t.append(registry.encode(entityAttribute.getValue()));


        return t;
    }
}
