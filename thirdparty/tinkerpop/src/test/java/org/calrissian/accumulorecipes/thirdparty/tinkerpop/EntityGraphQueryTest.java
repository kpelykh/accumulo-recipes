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
package org.calrissian.accumulorecipes.thirdparty.tinkerpop;

import java.util.Arrays;
import java.util.HashSet;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.GraphQuery;
import com.tinkerpop.blueprints.Vertex;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.mock.MockInstance;
import org.calrissian.accumulorecipes.commons.domain.Auths;
import org.calrissian.accumulorecipes.commons.support.attribute.MetadataBuilder;
import org.calrissian.accumulorecipes.graphstore.impl.AccumuloEntityGraphStore;
import org.calrissian.accumulorecipes.graphstore.model.EdgeEntity;
import org.calrissian.accumulorecipes.thirdparty.tinkerpop.model.EntityEdge;
import org.calrissian.accumulorecipes.thirdparty.tinkerpop.model.EntityVertex;
import org.calrissian.mango.collect.CloseableIterable;
import org.calrissian.mango.domain.Attribute;
import org.calrissian.mango.domain.entity.BaseEntity;
import org.calrissian.mango.domain.entity.Entity;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class EntityGraphQueryTest {


    AccumuloEntityGraphStore entityGraphStore;
    EntityGraph graph;
    Connector connector;
    GraphQuery query;

    Entity vertex1 = new BaseEntity("vertexType1", "id1");
    Entity vertex2 = new BaseEntity("vertexType2", "id2");
    Entity edge = new EdgeEntity("edgeType1", "edgeId", vertex1, "", vertex2, "", "label1");
    Entity edge2 = new EdgeEntity("edgeType2", "edgeId2", vertex1, "", vertex2, "", "label2");

    @Before
    public void start() throws AccumuloSecurityException, AccumuloException, TableExistsException, TableNotFoundException {
        Instance instance = new MockInstance();
        connector = instance.getConnector("root", "".getBytes());
        entityGraphStore = new AccumuloEntityGraphStore(connector);
        graph = new EntityGraph(entityGraphStore, Sets.newHashSet("vertexType1", "vertexType2"),
                Sets.newHashSet("edgeType1", "edgeType2"),
                new Auths("U,ADMIN"));

        Attribute v1t1 = new Attribute("key1", "val1", new MetadataBuilder().setVisibility("U").build());
        vertex1.put(v1t1);

        Attribute v1t2 = new Attribute("key2", "val2", new MetadataBuilder().setVisibility("U").build());
        vertex1.put(v1t2);

        Attribute v2t1 = new Attribute("key3", "val3", new MetadataBuilder().setVisibility("U").build());
        vertex2.put(v2t1);

        Attribute v2t2 = new Attribute("key4", "val4", new MetadataBuilder().setVisibility("U").build());
        vertex2.put(v2t2);

        Attribute keyVal = new Attribute("key", "val", new MetadataBuilder().setVisibility("U").build());
        vertex1.put(keyVal);
        vertex2.put(keyVal);

        Attribute e1t1 = new Attribute("edgeProp1", "edgeVal1", new MetadataBuilder().setVisibility("ADMIN").build());
        edge.put(e1t1);

        Attribute e1t2 = new Attribute("edgeProp2", "edgeVal2", new MetadataBuilder().setVisibility("U").build());
        edge.put(e1t2);

        Attribute e2t1 = new Attribute("edgeProp3", "edgeVal3", new MetadataBuilder().setVisibility("ADMIN").build());
        edge2.put(e2t1);

        Attribute e2t2 = new Attribute("edgeProp4", "edgeVal4", new MetadataBuilder().setVisibility("U").build());
        edge2.put(e2t2);

        Attribute edgeKeyVal = new Attribute("edgeProp", "edgeVal", new MetadataBuilder().setVisibility("U").build());

        edge.put(edgeKeyVal);
        edge2.put(edgeKeyVal);



        entityGraphStore.save(Arrays.asList(new Entity[]{vertex1, vertex2, edge, edge2}));
    }

    @Test
    public void testVertices_HasFullProperties_resultsReturned() {

        query = graph.query();
        CloseableIterable<Vertex> vertices = (CloseableIterable<Vertex>) query.has("key1", "val1").has("key2", "val2").vertices();
        Assert.assertEquals(1, Iterables.size(vertices));
        assertEntitiesEqual(vertex1, ((EntityVertex) Iterables.get(vertices, 0)).getEntity());

        query = graph.query();
        vertices = (CloseableIterable<Vertex>) query.has("key", "val").vertices();
        Assert.assertEquals(2, Iterables.size(vertices));
        assertEntitiesEqual(vertex1, ((EntityVertex) Iterables.get(vertices, 1)).getEntity());
        assertEntitiesEqual(vertex2, ((EntityVertex) Iterables.get(vertices, 0)).getEntity());
    }

    @Test
    public void testVertices_HasKeys_resultsReturned() {

        query = graph.query();
        CloseableIterable<Vertex> vertices = (CloseableIterable<Vertex>) query.has("key1").vertices();
        Assert.assertEquals(1, Iterables.size(vertices));
        assertEntitiesEqual(vertex1, ((EntityVertex) Iterables.get(vertices, 0)).getEntity());

        query = graph.query();
        vertices = (CloseableIterable<Vertex>) query.has("key").vertices();
        Assert.assertEquals(2, Iterables.size(vertices));
        assertEntitiesEqual(vertex1, ((EntityVertex) Iterables.get(vertices, 1)).getEntity());
        assertEntitiesEqual(vertex2, ((EntityVertex) Iterables.get(vertices, 0)).getEntity());
    }

    @Test
    public void testVertices_HasNotKeys_resultsReturned() {

        query = graph.query();
        CloseableIterable<Vertex> vertices = (CloseableIterable<Vertex>) query.hasNot("key1").vertices();
        Assert.assertEquals(1, Iterables.size(vertices));
        assertEntitiesEqual(vertex2, ((EntityVertex) Iterables.get(vertices, 0)).getEntity());

        query = graph.query();
        vertices = (CloseableIterable<Vertex>) query.hasNot("key").vertices();
        Assert.assertEquals(0, Iterables.size(vertices));
    }

    @Test
    public void testVertices_NoQuery_Limit() {

        query = graph.query();
        CloseableIterable<Vertex> vertices = (CloseableIterable<Vertex>) query.limit(1).vertices();
        Assert.assertEquals(1, Iterables.size(vertices));
        assertEntitiesEqual(vertex1, ((EntityVertex) Iterables.get(vertices, 0)).getEntity());

        query = graph.query();
        vertices = (CloseableIterable<Vertex>) query.limit(0).vertices();
        Assert.assertEquals(0, Iterables.size(vertices));
    }

    @Test
    public void testEdges_HasFullProperties_resultsReturned() {

        query = graph.query();
        CloseableIterable<Edge> edges = (CloseableIterable<Edge>) query.has("edgeProp1", "edgeVal1").has("edgeProp2", "edgeVal2").edges();
        Assert.assertEquals(1, Iterables.size(edges));
        assertEntitiesEqual(edge, ((EntityEdge) Iterables.get(edges, 0)).getEntity());

        query = graph.query();
        edges = (CloseableIterable<Edge>) query.has("edgeProp", "edgeVal").edges();
        Assert.assertEquals(2, Iterables.size(edges));
        assertEntitiesEqual(edge, ((EntityEdge) Iterables.get(edges, 0)).getEntity());
        assertEntitiesEqual(edge2, ((EntityEdge) Iterables.get(edges, 1)).getEntity());
    }

    @Test
    public void testEdges_HasKeys_resultsReturned() {
        query = graph.query();
        CloseableIterable<Edge> edges = (CloseableIterable<Edge>) query.has("edgeProp1").edges();
        Assert.assertEquals(1, Iterables.size(edges));
        assertEntitiesEqual(edge, ((EntityEdge) Iterables.get(edges, 0)).getEntity());

        query = graph.query();
        edges = (CloseableIterable<Edge>) query.has("edgeProp").edges();
        Assert.assertEquals(2, Iterables.size(edges));
        assertEntitiesEqual(edge, ((EntityEdge) Iterables.get(edges, 0)).getEntity());
        assertEntitiesEqual(edge2, ((EntityEdge) Iterables.get(edges, 1)).getEntity());
    }

    @Test
    public void testEdges_HasNotKeys_resultsReturned() {
        query = graph.query();
        CloseableIterable<Edge> edges = (CloseableIterable<Edge>) query.hasNot("edgeProp1").edges();
        Assert.assertEquals(1, Iterables.size(edges));
        assertEntitiesEqual(edge2, ((EntityEdge) Iterables.get(edges, 0)).getEntity());

        query = graph.query();
        edges = (CloseableIterable<Edge>) query.hasNot("edgeProp").edges();
        Assert.assertEquals(0, Iterables.size(edges));
    }



    @Test
    public void testEdges_NoQuery_Limit() {

        query = graph.query();
        CloseableIterable<Edge> edges = (CloseableIterable<Edge>) query.limit(1).edges();
        Assert.assertEquals(1, Iterables.size(edges));
        assertEntitiesEqual(edge, ((EntityEdge) Iterables.get(edges, 0)).getEntity());

        query = graph.query();
        edges = (CloseableIterable<Edge>) query.limit(0).edges();
        Assert.assertEquals(0, Iterables.size(edges));
    }

    private void assertEntitiesEqual(Entity expected, Entity actual) {

        Assert.assertEquals(expected.getType(), actual.getType());
        Assert.assertEquals(expected.getId(), actual.getId());
        Assert.assertEquals(new HashSet(expected.getAttributes()), new HashSet(actual.getAttributes()));
    }

}
