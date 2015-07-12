/**
 * Copyright (c) 2015, The National Archives <pronom@nationalarchives.gsi.gov.uk>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following
 * conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 *  * Neither the name of the The National Archives nor the
 *    names of its contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package uk.gov.nationalarchives.droid.results.handlers;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationMethod;
import uk.gov.nationalarchives.droid.core.interfaces.NodeStatus;
import uk.gov.nationalarchives.droid.core.interfaces.ResourceId;
import uk.gov.nationalarchives.droid.core.interfaces.ResourceType;
import uk.gov.nationalarchives.droid.core.interfaces.resource.ResourceUtils;
import uk.gov.nationalarchives.droid.profile.NodeMetaData;
import uk.gov.nationalarchives.droid.profile.ProfileResourceNode;
import uk.gov.nationalarchives.droid.profile.SqlUtils;
import uk.gov.nationalarchives.droid.profile.referencedata.Format;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;


/**
 * An implementation of the ResultHandlerDao interface, using JDBC to access the profile database directly.
 *
 * @author Matt Palmer
 */
public class JDBCBatchResultHandlerDao implements ResultHandlerDao {

    private static String INSERT_PROFILE_RESOURCE_NODE =
                    "INSERT INTO PROFILE_RESOURCE_NODE " +
                    "(NODE_ID,EXTENSION_MISMATCH,FINISHED_TIMESTAMP,IDENTIFICATION_COUNT," +
                    " EXTENSION,HASH,IDENTIFICATION_METHOD,LAST_MODIFIED_DATE,NAME,NODE_STATUS," +
                    " RESOURCE_TYPE,FILE_SIZE,PARENT_ID,PREFIX,PREFIX_PLUS_ONE,URI) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static String INSERT_IDENTIFICATIONS       = "INSERT INTO IDENTIFICATION (NODE_ID,PUID) VALUES ";
    private static String INSERT_ZERO_IDENTIFICATIONS  = INSERT_IDENTIFICATIONS + "(?,'')";
    private static String INSERT_ONE_IDENTIFICATION    = INSERT_IDENTIFICATIONS + "(?,?)";
    private static String INSERT_TWO_IDENTIFICATIONS   = INSERT_IDENTIFICATIONS + "(?,?),(?,?)";
    private static String INSERT_THREE_IDENTIFICATIONS = INSERT_IDENTIFICATIONS + "(?,?),(?,?),(?,?)";
    private static String INSERT_FOUR_IDENTIFICATIONS  = INSERT_IDENTIFICATIONS + "(?,?),(?,?),(?,?),(?,?)";
    private static String INSERT_FIVE_IDENTIFICATIONS  = INSERT_IDENTIFICATIONS + "(?,?),(?,?),(?,?),(?,?),(?,?)";
    private static String INSERT_SIX_IDENTIFICATIONS   = INSERT_IDENTIFICATIONS + "(?,?),(?,?),(?,?),(?,?),(?,?),(?,?)";
    private static String INSERT_SEVEN_IDENTIFICATIONS = INSERT_IDENTIFICATIONS + "(?,?),(?,?),(?,?),(?,?),(?,?),(?,?),(?,?)";
    private static String INSERT_EIGHT_IDENTIFICATIONS = INSERT_IDENTIFICATIONS + "(?,?),(?,?),(?,?),(?,?),(?,?),(?,?),(?,?),(?,?)";
    private static String INSERT_NINE_IDENTIFICATIONS  = INSERT_IDENTIFICATIONS + "(?,?),(?,?),(?,?),(?,?),(?,?),(?,?),(?,?),(?,?),(?,?)";
    private static String INSERT_TEN_IDENTIFICATIONS   = INSERT_IDENTIFICATIONS + "(?,?),(?,?),(?,?),(?,?),(?,?),(?,?),(?,?),(?,?),(?,?),(?,?)";

    private static String[] INSERT_IDENTIFICATION      = {
            INSERT_ZERO_IDENTIFICATIONS, INSERT_ONE_IDENTIFICATION,
            INSERT_TWO_IDENTIFICATIONS, INSERT_THREE_IDENTIFICATIONS, INSERT_FOUR_IDENTIFICATIONS,
            INSERT_FIVE_IDENTIFICATIONS, INSERT_SIX_IDENTIFICATIONS, INSERT_SEVEN_IDENTIFICATIONS,
            INSERT_EIGHT_IDENTIFICATIONS, INSERT_NINE_IDENTIFICATIONS, INSERT_TEN_IDENTIFICATIONS};

    private static String UPDATE_NODE_STATUS           = "UPDATE PROFILE_RESOURCE_NODE SET NODE_STATUS = ? WHERE NODE_ID = ?";
    private static String DELETE_NODE                  = "DELETE FROM PROFILE_RESOURCE_NODE WHERE NODE_ID = ?";
    private static String SELECT_FORMAT                = "SELECT * FROM FORMAT WHERE PUID = ?";
    private static String SELECT_FORMATS               = "SELECT * FROM FORMAT";
    private static String SELECT_PROFILE_RESOURCE_NODE = "SELECT * FROM PROFILE_RESOURCE_NODE WHERE NODE_ID = ?";
    private static String SELECT_IDENTIFICATIONS       = "SELECT * FROM IDENTIFICATION WHERE NODE_ID = ?";
    private static String MAX_NODE_ID_QUERY            = "SELECT MAX(NODE_ID) FROM PROFILE_RESOURCE_NODE";

    private final Log log = LogFactory.getLog(getClass());

    private DataSource datasource;
    private AtomicLong nodeIds;

    private List<Format> formats;
    private Map<String, Format> puidFormatMap = new HashMap<String,Format>(2500);

    private BlockingQueue<ProfileResourceNode> blockingQueue = new ArrayBlockingQueue<ProfileResourceNode>(128);
    private Thread databaseWriterThread;
    private DatabaseWriter writer;

    @Override
    public void init() {
        formats = loadAllFormats();
        for (final Format format : formats) {
            puidFormatMap.put(format.getPuid(), format);
        }
        nodeIds = new AtomicLong(getMaxNodeId() + 1);

        setupDatabaseWriterThread();
    }

    @Override
    public void save(final ProfileResourceNode node, final ResourceId parentId) {
        setNodeIds(node, parentId);
        try {
            blockingQueue.put(node);
        } catch (InterruptedException e) {
            e.printStackTrace(); //TODO: what to do here?
        }
    }

    @Override
    public void commit() {
        // Give the writer thread a chance to finish processing the queue.
        int attemptCount = 0;
        while (blockingQueue.size() > 0 && attemptCount++ < 24) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
        }
        // interrupt the old thread (forcing a commit), and set up a new one:
        setupDatabaseWriterThread();
    }

    private void setNodeIds(ProfileResourceNode node, ResourceId parentId) {
        final Long nodeId = nodeIds.incrementAndGet();
        node.setId(nodeId);
        String parentsPrefixString = "";
        if (parentId != null) {
            parentsPrefixString = parentId.getPath();
            node.setParentId(parentId.getId());
        }
        final String nodePrefix = parentsPrefixString + ResourceUtils.getBase128Integer(nodeId);
        final String nodePrefixPlusOne =  parentsPrefixString + ResourceUtils.getBase128Integer(nodeId + 1);
        node.setPrefix(nodePrefix);
        node.setPrefixPlusOne(nodePrefixPlusOne);
    }


    @Override
    public Format loadFormat(final String puid) {
        Format format = null;
        try {
            final Connection conn = datasource.getConnection();
            try {
                final PreparedStatement loadFormat = conn.prepareStatement(SELECT_FORMAT);
                try {
                    loadFormat.setString(1, puid);
                    final ResultSet results = loadFormat.executeQuery();
                    try {
                        if (results.next()) {
                            format = SqlUtils.buildFormat(results);
                        }
                    } finally {
                        results.close();
                    }
                } finally {
                    loadFormat.close();
                }
            } finally{
                conn.close();
            }
        } catch (SQLException e) {
            log.error("A database exception occurred loading a format with puid " + puid, e);
        }
        return format;
    }

    @Override
    public List<Format> getAllFormats() {
        return formats;
    }

    @Override
    public Map<String, Format> getPUIDFormatMap() {
        return puidFormatMap;
    }

    @Override
    public ProfileResourceNode loadNode(Long nodeId) {
        ProfileResourceNode node = null;
        try {
            final Connection conn = datasource.getConnection();
            try {
                final PreparedStatement loadNode = conn.prepareStatement(SELECT_PROFILE_RESOURCE_NODE);
                try {
                    loadNode.setLong(1, nodeId);
                    final ResultSet nodeResults = loadNode.executeQuery();
                    try {
                        if (nodeResults.next()) {
                            final PreparedStatement loadIdentifications = conn.prepareStatement(SELECT_IDENTIFICATIONS);
                            loadIdentifications.setLong(1, nodeId);
                            final ResultSet idResults = loadIdentifications.executeQuery();
                            node = SqlUtils.buildProfileResourceNode(nodeResults);
                            SqlUtils.addIdentifications(node, idResults, puidFormatMap);
                        }
                    } finally {
                        nodeResults.close();
                    }
                } finally {
                    loadNode.close();
                }
            } finally{
                conn.close();
            }
        } catch (SQLException e) {
            log.error("A database exception occurred loading a node with id " + nodeId, e);
        }
        return node;
    }

    @Override
    public void deleteNode(Long nodeId) {
        try {
            final Connection conn = datasource.getConnection(); //TODO: check auto commit status.
            try {
                final PreparedStatement statement = conn.prepareStatement(DELETE_NODE);
                try {
                    statement.setLong(1, nodeId);
                    statement.execute();
                    conn.commit();
                } finally {
                    statement.close();
                }
            } finally {
                conn.close();
            }
            //TODO: do we need to explicitly delete formats associated with this node, or does it do cascade?
        } catch (SQLException e) {
            log.error("A database exception occurred deleting a node with id " + nodeId, e);
        }
    }

    public void setDatasource(DataSource datasource) {
        this.datasource = datasource;
    }

    private long getMaxNodeId() {
        long maxId = 0;
        try {
            final Connection conn = datasource.getConnection();
            try {
                final PreparedStatement maxNodes = conn.prepareStatement(MAX_NODE_ID_QUERY);
                try {
                    final ResultSet results = maxNodes.executeQuery();
                    try {
                        if (results.next()) {
                            maxId = results.getLong(1);
                        }
                    } finally {
                        results.close();
                    }
                } finally {
                    maxNodes.close();
                }
            } finally{
                conn.close();
            }
        } catch (SQLException e) {
            log.error("A database exception occurred finding the maximum id in the database", e);
        }
        return maxId;
    }

    private List<Format> loadAllFormats() {
        final List<Format> formats = new ArrayList<Format>(2000);
        try {
            final Connection conn = datasource.getConnection();
            try {
                final PreparedStatement loadFormat = conn.prepareStatement(SELECT_FORMATS);
                try {
                    final ResultSet results = loadFormat.executeQuery();
                    try {
                        while (results.next()) {
                            formats.add(SqlUtils.buildFormat(results));
                        }
                    } finally {
                        results.close();
                    }
                } finally {
                    loadFormat.close();
                }
            } finally{
                conn.close();
            }
        } catch (SQLException e) {
            log.error("A database exception occurred getting all formats.", e);
        }
        return formats;
    }

    private void setupDatabaseWriterThread() {
        if (databaseWriterThread != null) {
            databaseWriterThread.interrupt();
        }
        writer = new DatabaseWriter(blockingQueue, datasource, 50);
        try {
            writer.init();
        } catch (SQLException e) {
            //TODO: not a runtime exception - what to use here?
            throw new RuntimeException("Could not initialise the database writer - fatal error.", e);
        }
        databaseWriterThread = new Thread(writer);
        databaseWriterThread.start();
    }

    private static class DatabaseWriter implements Runnable {

        private final Log log = LogFactory.getLog(getClass());
        private BlockingQueue<ProfileResourceNode> blockingQueue;
        private DataSource datasource;
        private Connection connection;
        private PreparedStatement insertNodeStatement;
        private PreparedStatement updateNodeStatement;
        private Map<Integer, PreparedStatement> insertIdentifications;
        private volatile int batchCount;
        private final int batchLimit;

        DatabaseWriter(final BlockingQueue blockingQueue,
                       final DataSource datasource,
                       final int batchLimit) {
            this.blockingQueue = blockingQueue;
            this.datasource    = datasource;
            this.batchLimit    = batchLimit;
        }

        public void init() throws SQLException {
            connection = datasource.getConnection();
            insertNodeStatement = connection.prepareStatement(INSERT_PROFILE_RESOURCE_NODE);
            updateNodeStatement = connection.prepareStatement(UPDATE_NODE_STATUS);
            insertIdentifications = new HashMap<Integer, PreparedStatement>(64);
            for (int i = 0; i < INSERT_IDENTIFICATION.length; i++) {
                final PreparedStatement insertStatement = connection.prepareStatement(INSERT_IDENTIFICATION[i]);
                insertIdentifications.put(i, insertStatement);
            }
        }

        @Override
        public void run() {
            try {
                while (true) {
                    final ProfileResourceNode node = blockingQueue.take();
                    try {
                        if (node.isStatusUpdate()) {
                            updateNodeStatus(node);
                        } else {
                            batchInsertNode(node);
                        }
                    } catch (SQLException e) {
                        log.error("A database problem occurred inserting the node: " + node, e);
                    }
                }
            } catch (InterruptedException e) {
                commit();
                closeResources();
             }
        }

        private void closeResources() {
            for (final PreparedStatement statement : insertIdentifications.values()) {
                try {
                    statement.close();
                } catch (SQLException s) {
                    log.error("A problem occurred closing a prepared statement.", s);
                }
            }
            try {
                insertNodeStatement.close();
            } catch (SQLException s) {
                log.error("A problem occurred closing a prepared statement.", s);
            }
            try {
                connection.close();
            } catch (SQLException s) {
                log.error("A problem occurred closing a database connection", s);
            }
        }

        private void batchInsertNode(final ProfileResourceNode node) throws SQLException {
            // insert main node:
            final long nodeId = node.getId();
            final NodeMetaData metadata = node.getMetaData();
            final String uri = node.getUri().toString();
            final java.sql.Date finished = new java.sql.Date(new java.util.Date().getTime());
            final boolean mismatch = node.getExtensionMismatch();
            final String name = metadata.getName();
            final String hash = metadata.getHash(); // nullable
            final Long size = metadata.getSize(); // nullable.
            final NodeStatus nodeStatus = metadata.getNodeStatus();
            final ResourceType resourceType = metadata.getResourceType();
            final String extension = metadata.getExtension();
            final Integer numIdentifications = node.getIdentificationCount();
            final Date modDate = metadata.getLastModifiedDate();
            final IdentificationMethod method = metadata.getIdentificationMethod();
            final Long nodeParentId = node.getParentId();
            final String nodePrefix = node.getPrefix();
            final String nodePrefixPlusOne = node.getPrefixPlusOne();
            final PreparedStatement insertNode = insertNodeStatement;
            insertNode.setLong(            1,  nodeId);
            insertNode.setBoolean(         2,  mismatch);
            insertNode.setDate(            3,  finished);
            SqlUtils.setNullableInteger(   4,  numIdentifications, insertNode);
            SqlUtils.setNullableString(    5,  extension, insertNode);
            SqlUtils.setNullableString(    6,  hash, insertNode);
            SqlUtils.setNullableEnumAsInt( 7, method, insertNode);
            SqlUtils.setNullableTimestamp( 8, modDate, insertNode);
            insertNode.setString(          9,  name);
            SqlUtils.setNullableEnumAsInt( 10, nodeStatus, insertNode);
            SqlUtils.setNullableEnumAsInt( 11, resourceType, insertNode);
            SqlUtils.setNullableLong(      12, size, insertNode);
            SqlUtils.setNullableLong(      13, nodeParentId, insertNode);
            SqlUtils.setNullableString(    14, nodePrefix, insertNode);
            SqlUtils.setNullableString(    15, nodePrefixPlusOne, insertNode);
            insertNode.setString(          16, uri);
            insertNode.addBatch();

            // insert its identifications:
            //TODO: check for NULL format weirdness...
            final int identifications = numIdentifications == null? 0 : numIdentifications;
            final PreparedStatement statement = getIdentificationStatement(identifications);
            if (identifications == 0) {
                statement.setLong(1, nodeId);
            } else {
                int parameterCount = 1;
                for (final Format format : node.getFormatIdentifications()) {
                    statement.setLong(parameterCount++, nodeId);
                    String p = format.getPuid();
                    statement.setString(parameterCount++, p == null ? "" : p);
                }
            }
            statement.addBatch();

            commitBatchIfLargeEnough();
        }

        private void updateNodeStatus(final ProfileResourceNode node) throws SQLException {
            final Long nodeId = node.getId();
            if (nodeId != null) {
                NodeMetaData nm = node.getMetaData();
                if (nm != null) {
                    SqlUtils.setNullableEnumAsInt(1, nm.getNodeStatus(), updateNodeStatement);
                    updateNodeStatement.setLong(  2, nodeId);
                    updateNodeStatement.addBatch();
                    commitBatchIfLargeEnough();
                } else {
                    log.error("A node was flagged for status update, but had no status metadata. Node id was: " + nodeId);
                }
            } else {
                log.error("A node was flagged for status update, but it did not have an id already.  Parent id was: " + node.getParentId());
            }
        }

        private void commitBatchIfLargeEnough() {
            // Commit if exceeded batch limit:
            if (batchCount++ >= batchLimit) {
                batchCount = 0;
                try {
                    // Insert new nodes:
                    insertNodeStatement.executeBatch();

                    // Update node status:
                    updateNodeStatement.executeBatch();

                    // Insert identifications of new nodes:
                    for (final PreparedStatement identifications : insertIdentifications.values()) {
                        identifications.executeBatch(); //TODO: what about identifications not used in this run?
                    }
                    connection.commit();
                } catch (SQLException e) {
                    log.error("A problem occurred attempting to batch commit nodes into the database. ", e);
                }
            }
        }

        public void commit() {
            batchCount = Integer.MAX_VALUE - 10;
            commitBatchIfLargeEnough();
        }

        private PreparedStatement getIdentificationStatement(final int numIdentifications) throws SQLException {
            PreparedStatement statement = insertIdentifications.get(numIdentifications);
            if (statement == null) {
                final String newIdentificationSQL = buildInsertIdentificationString(numIdentifications);
                statement = connection.prepareStatement(newIdentificationSQL);
                insertIdentifications.put(numIdentifications, statement);
            }
            return statement;
        }

        private String buildInsertIdentificationString(final int numIdentifications) {
            final StringBuilder builder = new StringBuilder(60 + numIdentifications * 6);
            builder.append(INSERT_IDENTIFICATIONS);
            for (int i = 0; i < numIdentifications - 1; i++) {
                builder.append("(?,?),");
            }
            builder.append("(?,?)");
            return builder.toString();
        }

    }

}
