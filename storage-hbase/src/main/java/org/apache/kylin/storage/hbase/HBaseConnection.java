/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.storage.hbase;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.persistence.StorageException;
import org.apache.kylin.engine.mr.HadoopUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author yangli9
 * 
 */
public class HBaseConnection {

    public static final String HTABLE_UUID_TAG = "UUID";

    private static final Logger logger = LoggerFactory.getLogger(HBaseConnection.class);

    private static final Map<String, Configuration> configCache = new ConcurrentHashMap<String, Configuration>();
    private static final Map<String, HConnection> connPool = new ConcurrentHashMap<String, HConnection>();
    private static final ThreadLocal<Configuration> configThreadLocal = new ThreadLocal<>();

    private static ExecutorService coprocessorPool = null;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                closeCoprocessorPool();

                for (HConnection conn : connPool.values()) {
                    try {
                        conn.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    public static ExecutorService getCoprocessorPool() {
        if (coprocessorPool != null) {
            return coprocessorPool;
        }

        synchronized (HBaseConnection.class) {
            if (coprocessorPool != null) {
                return coprocessorPool;
            }

            KylinConfig config = KylinConfig.getInstanceFromEnv();

            // copy from HConnectionImplementation.getBatchPool()
            int maxThreads = config.getHBaseMaxConnectionThreads();
            int coreThreads = config.getHBaseCoreConnectionThreads();
            long keepAliveTime = config.getHBaseConnectionThreadPoolAliveSeconds();
            LinkedBlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<Runnable>(maxThreads * 100);
            ThreadPoolExecutor tpe = new ThreadPoolExecutor(coreThreads, maxThreads, keepAliveTime, TimeUnit.SECONDS, workQueue, //
                    Threads.newDaemonThreadFactory("kylin-coproc-"));
            tpe.allowCoreThreadTimeOut(true);

            logger.info("Creating coprocessor thread pool with max of " + maxThreads);
            
            coprocessorPool = tpe;
            return coprocessorPool;
        }
    }

    private static void closeCoprocessorPool() {
        if (coprocessorPool == null)
            return;

        coprocessorPool.shutdown();
        try {
            if (!coprocessorPool.awaitTermination(10, TimeUnit.SECONDS)) {
                coprocessorPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            coprocessorPool.shutdownNow();
        }
    }

    public static void clearConnCache() {
        connPool.clear();
    }

    public static Configuration getCurrentHBaseConfiguration() {
        if (configThreadLocal.get() == null) {
            String storageUrl = KylinConfig.getInstanceFromEnv().getStorageUrl();
            configThreadLocal.set(newHBaseConfiguration(storageUrl));
        }
        return configThreadLocal.get();
    }

    private static Configuration newHBaseConfiguration(String url) {
        Configuration conf = HBaseConfiguration.create(HadoopUtil.getCurrentConfiguration());

        // using a hbase:xxx URL is deprecated, instead hbase config is always loaded from hbase-site.xml in classpath
        if (!(StringUtils.isEmpty(url) || "hbase".equals(url)))
            throw new IllegalArgumentException("to use hbase storage, pls set 'kylin.storage.url=hbase' in kylin.properties");

        // support hbase using a different FS
        String hbaseClusterFs = KylinConfig.getInstanceFromEnv().getHBaseClusterFs();
        if (StringUtils.isNotEmpty(hbaseClusterFs)) {
            conf.set(FileSystem.FS_DEFAULT_NAME_KEY, hbaseClusterFs);
        }

        // https://issues.apache.org/jira/browse/KYLIN-953
        if (StringUtils.isBlank(conf.get("hadoop.tmp.dir"))) {
            conf.set("hadoop.tmp.dir", "/tmp");
        }
        if (StringUtils.isBlank(conf.get("hbase.fs.tmp.dir"))) {
            conf.set("hbase.fs.tmp.dir", "/tmp");
        }

        // reduce rpc retry
        conf.set(HConstants.HBASE_CLIENT_PAUSE, "3000");
        conf.set(HConstants.HBASE_CLIENT_RETRIES_NUMBER, "5");
        conf.set(HConstants.HBASE_CLIENT_OPERATION_TIMEOUT, "60000");
        // conf.set(ScannerCallable.LOG_SCANNER_ACTIVITY, "true");

        return conf;
    }

    public static String makeQualifiedPathInHBaseCluster(String path) {
        try {
            FileSystem fs = FileSystem.get(getCurrentHBaseConfiguration());
            return fs.makeQualified(new Path(path)).toString();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot create FileSystem from current hbase cluster conf", e);
        }
    }

    // ============================================================================

    // returned HConnection can be shared by multiple threads and does not require close()
    @SuppressWarnings("resource")
    public static HConnection get(String url) {
        // find configuration
        Configuration conf = configCache.get(url);
        if (conf == null) {
            conf = newHBaseConfiguration(url);
            configCache.put(url, conf);
        }

        HConnection connection = connPool.get(url);
        try {
            while (true) {
                // I don't use DCL since recreate a connection is not a big issue.
                if (connection == null || connection.isClosed()) {
                    logger.info("connection is null or closed, creating a new one");
                    connection = HConnectionManager.createConnection(conf);
                    connPool.put(url, connection);
                }

                if (connection == null || connection.isClosed()) {
                    Thread.sleep(10000);// wait a while and retry
                } else {
                    break;
                }
            }

        } catch (Throwable t) {
            logger.error("Error when open connection " + url, t);
            throw new StorageException("Error when open connection " + url, t);
        }

        return connection;
    }

    public static boolean tableExists(HConnection conn, String tableName) throws IOException {
        HBaseAdmin hbase = new HBaseAdmin(conn);
        try {
            return hbase.tableExists(TableName.valueOf(tableName));
        } finally {
            hbase.close();
        }
    }

    public static boolean tableExists(String hbaseUrl, String tableName) throws IOException {
        return tableExists(HBaseConnection.get(hbaseUrl), tableName);
    }

    public static void createHTableIfNeeded(String hbaseUrl, String tableName, String... families) throws IOException {
        createHTableIfNeeded(HBaseConnection.get(hbaseUrl), tableName, families);
    }

    public static void deleteTable(String hbaseUrl, String tableName) throws IOException {
        deleteTable(HBaseConnection.get(hbaseUrl), tableName);
    }

    public static void createHTableIfNeeded(HConnection conn, String tableName, String... families) throws IOException {
        HBaseAdmin hbase = new HBaseAdmin(conn);

        try {
            if (tableExists(conn, tableName)) {
                logger.debug("HTable '" + tableName + "' already exists");
                return;
            }

            logger.debug("Creating HTable '" + tableName + "'");

            HTableDescriptor desc = new HTableDescriptor(TableName.valueOf(tableName));

            if (null != families && families.length > 0) {
                for (String family : families) {
                    HColumnDescriptor fd = new HColumnDescriptor(family);
                    fd.setInMemory(true); // metadata tables are best in memory
                    desc.addFamily(fd);
                }
            }

            desc.setValue(HTABLE_UUID_TAG, UUID.randomUUID().toString());
            hbase.createTable(desc);

            logger.debug("HTable '" + tableName + "' created");
        } finally {
            hbase.close();
        }
    }

    public static void deleteTable(HConnection conn, String tableName) throws IOException {
        HBaseAdmin hbase = new HBaseAdmin(conn);

        try {
            if (!tableExists(conn, tableName)) {
                logger.debug("HTable '" + tableName + "' does not exists");
                return;
            }

            logger.debug("delete HTable '" + tableName + "'");

            if (hbase.isTableEnabled(tableName)) {
                hbase.disableTable(tableName);
            }
            hbase.deleteTable(tableName);

            logger.debug("HTable '" + tableName + "' deleted");
        } finally {
            hbase.close();
        }
    }

}
