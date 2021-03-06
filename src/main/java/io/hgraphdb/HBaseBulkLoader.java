package io.hgraphdb;

import io.hgraphdb.mutators.*;
import org.apache.hadoop.hbase.client.BufferedMutator;
import org.apache.hadoop.hbase.client.BufferedMutatorParams;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

public final class HBaseBulkLoader implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(HBaseBulkLoader.class);

    private HBaseGraph graph;
    private BufferedMutator edgesMutator;
    private BufferedMutator edgeIndicesMutator;
    private BufferedMutator verticesMutator;
    private BufferedMutator vertexIndicesMutator;
    private boolean skipWAL;

    public HBaseBulkLoader(HBaseGraphConfiguration config) {
        this(new HBaseGraph(config, HBaseGraphUtils.getConnection(config)));
    }

    public HBaseBulkLoader(HBaseGraph graph) {
        try {
            this.graph = graph;

            BufferedMutator.ExceptionListener listener = (e, mutator) -> {
                for (int i = 0; i < e.getNumExceptions(); i++) {
                    LOGGER.warn("Failed to send put: " + e.getRow(i));
                }
            };

            HBaseGraphConfiguration config = graph.configuration();

            BufferedMutatorParams edgesMutatorParams =
                    new BufferedMutatorParams(HBaseGraphUtils.getTableName(config, Constants.EDGES)).listener(listener);
            BufferedMutatorParams edgeIndicesMutatorParams =
                    new BufferedMutatorParams(HBaseGraphUtils.getTableName(config, Constants.EDGE_INDICES)).listener(listener);
            BufferedMutatorParams verticesMutatorParams =
                    new BufferedMutatorParams(HBaseGraphUtils.getTableName(config, Constants.VERTICES)).listener(listener);
            BufferedMutatorParams vertexIndicesMutatorParams =
                    new BufferedMutatorParams(HBaseGraphUtils.getTableName(config, Constants.VERTEX_INDICES)).listener(listener);

            edgesMutator = graph.connection().getBufferedMutator(edgesMutatorParams);
            edgeIndicesMutator = graph.connection().getBufferedMutator(edgeIndicesMutatorParams);
            verticesMutator = graph.connection().getBufferedMutator(verticesMutatorParams);
            vertexIndicesMutator = graph.connection().getBufferedMutator(vertexIndicesMutatorParams);

            skipWAL = config.getBulkLoaderSkipWAL();
        } catch (IOException e) {
            throw new HBaseGraphException(e);
        }
    }

    public HBaseGraph getGraph() {
        return graph;
    }

    public Vertex addVertex(final Object... keyValues) {
        try {
            ElementHelper.legalPropertyKeyValueArray(keyValues);
            Object idValue = ElementHelper.getIdValue(keyValues).orElse(null);
            final String label = ElementHelper.getLabelValue(keyValues).orElse(Vertex.DEFAULT_LABEL);

            idValue = HBaseGraphUtils.generateIdIfNeeded(idValue);
            long now = System.currentTimeMillis();
            HBaseVertex vertex = new HBaseVertex(graph, idValue, label, now, now,
                    HBaseGraphUtils.propertiesToMap(keyValues));
            vertex.validate();

            Iterator<IndexMetadata> indices = vertex.getIndices(OperationType.WRITE);
            indexVertex(vertex, indices);

            Creator creator = new VertexWriter(graph, vertex);
            verticesMutator.mutate(getMutationList(creator.constructInsertions()));

            return vertex;
        } catch (IOException e) {
            throw new HBaseGraphException(e);
        }
    }

    public void indexVertex(Vertex vertex, Iterator<IndexMetadata> indices) {
        try {
            VertexIndexWriter writer = new VertexIndexWriter(graph, vertex, indices, null);
            vertexIndicesMutator.mutate(getMutationList(writer.constructInsertions()));
        } catch (IOException e) {
            throw new HBaseGraphException(e);
        }
    }

    public Edge addEdge(Vertex outVertex, Vertex inVertex, String label, Object... keyValues) {
        try {
            if (null == inVertex) throw Graph.Exceptions.argumentCanNotBeNull("inVertex");
            ElementHelper.validateLabel(label);
            ElementHelper.legalPropertyKeyValueArray(keyValues);
            Object idValue = ElementHelper.getIdValue(keyValues).orElse(null);

            idValue = HBaseGraphUtils.generateIdIfNeeded(idValue);
            long now = System.currentTimeMillis();
            HBaseEdge edge = new HBaseEdge(graph, idValue, label, now, now,
                    HBaseGraphUtils.propertiesToMap(keyValues), inVertex, outVertex);
            edge.validate();

            Iterator<IndexMetadata> indices = edge.getIndices(OperationType.WRITE);
            indexEdge(edge, indices);

            EdgeIndexWriter writer = new EdgeIndexWriter(graph, edge, Constants.CREATED_AT);
            edgeIndicesMutator.mutate(getMutationList(writer.constructInsertions()));

            Creator creator = new EdgeWriter(graph, edge);
            edgesMutator.mutate(getMutationList(creator.constructInsertions()));

            return edge;
        } catch (IOException e) {
            throw new HBaseGraphException(e);
        }
    }

    public void indexEdge(Edge edge, Iterator<IndexMetadata> indices) {
        try {
            EdgeIndexWriter indexWriter = new EdgeIndexWriter(graph, edge, indices, null);
            edgeIndicesMutator.mutate(getMutationList(indexWriter.constructInsertions()));
        } catch (IOException e) {
            throw new HBaseGraphException(e);
        }
    }

    public void setProperty(Edge edge, String key, Object value) {
        try {
            HBaseEdge e = (HBaseEdge) edge;
            ElementHelper.validateProperty(key, value);

            graph.validateProperty(e.getElementType(), e.label(), key, value);

            // delete from index model before setting property
            Object oldValue = null;
            boolean hasIndex = e.hasIndex(OperationType.WRITE, key);
            if (hasIndex) {
                // only load old value if using index
                oldValue = e.getProperty(key);
                if (oldValue != null && !oldValue.equals(value)) {
                    EdgeIndexRemover indexRemover = new EdgeIndexRemover(graph, e, key, null);
                    edgeIndicesMutator.mutate(getMutationList(indexRemover.constructMutations()));
                }
            }

            e.getProperties().put(key, value);
            e.updatedAt(System.currentTimeMillis());

            if (hasIndex) {
                if (oldValue == null || !oldValue.equals(value)) {
                    EdgeIndexWriter indexWriter = new EdgeIndexWriter(graph, e, key);
                    edgeIndicesMutator.mutate(getMutationList(indexWriter.constructInsertions()));
                }
            }
            PropertyWriter propertyWriter = new PropertyWriter(graph, e, key, value);
            edgesMutator.mutate(getMutationList(propertyWriter.constructMutations()));
        } catch (IOException e) {
            throw new HBaseGraphException(e);
        }
    }

    public void setProperty(Vertex vertex, String key, Object value) {
        try {
            HBaseVertex v = (HBaseVertex) vertex;
            ElementHelper.validateProperty(key, value);

            graph.validateProperty(v.getElementType(), v.label(), key, value);

            // delete from index model before setting property
            Object oldValue = null;
            boolean hasIndex = v.hasIndex(OperationType.WRITE, key);
            if (hasIndex) {
                // only load old value if using index
                oldValue = v.getProperty(key);
                if (oldValue != null && !oldValue.equals(value)) {
                    VertexIndexRemover indexRemover = new VertexIndexRemover(graph, v, key, null);
                    vertexIndicesMutator.mutate(getMutationList(indexRemover.constructMutations()));
                }
            }

            v.getProperties().put(key, value);
            v.updatedAt(System.currentTimeMillis());

            if (hasIndex) {
                if (oldValue == null || !oldValue.equals(value)) {
                    VertexIndexWriter indexWriter = new VertexIndexWriter(graph, v, key);
                    vertexIndicesMutator.mutate(getMutationList(indexWriter.constructInsertions()));
                }
            }
            PropertyWriter propertyWriter = new PropertyWriter(graph, v, key, value);
            verticesMutator.mutate(getMutationList(propertyWriter.constructMutations()));
        } catch (IOException e) {
            throw new HBaseGraphException(e);
        }
    }

    private List<? extends Mutation> getMutationList(Iterator<? extends Mutation> mutations) {
        return IteratorUtils.list(IteratorUtils.consume(mutations,
                m -> m.setDurability(skipWAL ? Durability.SKIP_WAL : Durability.USE_DEFAULT)));
    }

    public void close() {
        try {
            edgesMutator.close();
            edgeIndicesMutator.close();
            verticesMutator.close();
            vertexIndicesMutator.close();
        } catch (IOException e) {
            throw new HBaseGraphException(e);
        }
    }
}
