/*
 * Copyright 1999-2017 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.stat;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.support.logging.Log;
import com.alibaba.druid.support.logging.LogFactory;
import com.alibaba.druid.util.DruidDataSourceUtils;
import com.alibaba.druid.util.JMXUtils;

@SuppressWarnings("rawtypes")
public class DruidDataSourceStatManager implements DruidDataSourceStatManagerMBean {

    private final static Lock                       staticLock                     = new ReentrantLock();

    public final static String                      SYS_PROP_INSTANCES             = "druid.dataSources";
    public final static String                      SYS_PROP_REGISTER_SYS_PROPERTY = "druid.registerToSysProperty";

    private final static Log                        LOG                            = LogFactory.getLog(DruidDataSourceStatManager.class);

    private final static DruidDataSourceStatManager instance                       = new DruidDataSourceStatManager();

    private final AtomicLong                        resetCount                     = new AtomicLong();

    // global instances
    /**
     * 一旦一个共享变量（类的成员变量、类的静态成员变量）被volatile修饰之后，那么就具备了两层语义：<br>
		1）保证了不同线程对这个变量进行操作时的可见性，即一个线程修改了某个变量的值，这新值对其他线程来说是立即可见的。<br>
		2）禁止进行指令重排序。
		
		synchronized关键字是防止多个线程同时执行一段代码，那么就会很影响程序执行效率，而volatile关键字在某些情况下性能要优于synchronized，
		但是要注意volatile关键字是无法替代synchronized关键字的，因为volatile关键字无法保证操作的原子性。通常来说，使用volatile必须具备以下2个条件：
		1）对变量的写操作不依赖于当前值
		2）该变量没有包含在具有其他变量的不变式中
     */
    private static volatile Map                     dataSources;

    private final static String                     MBEAN_NAME                     = "com.alibaba.druid:type=DruidDataSourceStat";

    public static DruidDataSourceStatManager getInstance() {
        return instance;
    }

    public static void clear() {
        staticLock.lock();
        try {
            Map<Object, ObjectName> dataSources = getInstances();

            MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
            for (Object item : dataSources.entrySet()) {
                Map.Entry entry = (Map.Entry) item;
                ObjectName objectName = (ObjectName) entry.getValue();

                if (objectName == null) {
                    continue;
                }

                try {
                    mbeanServer.unregisterMBean(objectName);
                } catch (JMException e) {
                    LOG.error(e.getMessage(), e);
                }
            }
        } finally {
            staticLock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<Object, ObjectName> getInstances() {
        Map<Object, ObjectName> tmp = dataSources;
        if (tmp == null) {
            staticLock.lock();
            try {
                if (isRegisterToSystemProperty()) {
                    dataSources = getInstances0();
                } else {
                    tmp = dataSources;
                    if (null == tmp) {
                        dataSources = tmp = Collections.synchronizedMap(new IdentityHashMap<Object, ObjectName>());
                    }
                }
            } finally {
                staticLock.unlock();
            }
        }

        return dataSources;
    }

    public static boolean isRegisterToSystemProperty() {
        String value = System.getProperty(SYS_PROP_REGISTER_SYS_PROPERTY);
        return "true".equals(value);
    }

    @SuppressWarnings("unchecked")
    static Map<Object, ObjectName> getInstances0() {
        Properties properties = System.getProperties();
        Map<Object, ObjectName> instances = (Map<Object, ObjectName>) properties.get(SYS_PROP_INSTANCES);

        if (instances == null) {
            synchronized (properties) {
                instances = (IdentityHashMap<Object, ObjectName>) properties.get(SYS_PROP_INSTANCES);

                if (instances == null) {
                    instances = Collections.synchronizedMap(new IdentityHashMap<Object, ObjectName>());
                    properties.put(SYS_PROP_INSTANCES, instances);
                }
            }
        }

        return instances;
    }

    /**
     * 将新的数据源添加到数据源状态管理里面
     * @param dataSource
     * @param name
     * @return
     */
    public synchronized static ObjectName addDataSource(Object dataSource, String name) {
        final Map<Object, ObjectName> instances = getInstances();

        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        synchronized (instances) {
            if (instances.size() == 0) {
                try {
                    ObjectName objectName = new ObjectName(MBEAN_NAME);
                    if (!mbeanServer.isRegistered(objectName)) {
                        mbeanServer.registerMBean(instance, objectName);
                    }
                } catch (JMException ex) {
                    LOG.error("register mbean error", ex);
                }

                DruidStatService.registerMBean();
            }
        }

        ObjectName objectName = null;
        if (name != null) {
            try {
                objectName = new ObjectName("com.alibaba.druid:type=DruidDataSource,id=" + name);
                mbeanServer.registerMBean(dataSource, objectName);
            } catch (Throwable ex) {
                LOG.error("register mbean error", ex);
                objectName = null;
            }
        }

        if (objectName == null) {
            try {
                int id = System.identityHashCode(dataSource);
                objectName = new ObjectName("com.alibaba.druid:type=DruidDataSource,id=" + id);
                mbeanServer.registerMBean(dataSource, objectName);
            } catch (Throwable ex) {
                LOG.error("register mbean error", ex);
                objectName = null;
            }
        }

        instances.put(dataSource, objectName);
        return objectName;
    }

    public synchronized static void removeDataSource(Object dataSource) {
        Map<Object, ObjectName> instances = getInstances();

        ObjectName objectName = (ObjectName) instances.remove(dataSource);

        if (objectName == null) {
            objectName = DruidDataSourceUtils.getObjectName(dataSource);
        }

        if (objectName == null) {
            LOG.error("unregister mbean failed. url " + DruidDataSourceUtils.getUrl(dataSource));
            return;
        }

        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();

        try {
            mbeanServer.unregisterMBean(objectName);
        } catch (Throwable ex) {
            LOG.error("unregister mbean error", ex);
        }

        if (instances.size() == 0) {
            try {
                mbeanServer.unregisterMBean(new ObjectName(MBEAN_NAME));
            } catch (Throwable ex) {
                LOG.error("unregister mbean error", ex);
            }

            DruidStatService.unregisterMBean();
        }
    }

    @SuppressWarnings("unchecked")
    public static Set<DruidDataSource> getDruidDataSourceInstances() {
        getInstances();
        return dataSources.keySet();
    }

    public void reset() {
        Map<Object, ObjectName> dataSources = getInstances();

        for (Object item : dataSources.keySet()) {
            try {
                Method method = item.getClass().getMethod("resetStat");
                method.invoke(item);
            } catch (Exception e) {
                LOG.error("resetStat error", e);
            }
        }

        resetCount.incrementAndGet();
    }

    public void logAndResetDataSource() {
        Map<Object, ObjectName> dataSources = getInstances();

        for (Object item : dataSources.keySet()) {
            try {
                Method method = item.getClass().getMethod("logStats");
                method.invoke(item);
            } catch (Exception e) {
                LOG.error("resetStat error", e);
            }
        }

        resetCount.incrementAndGet();
    }

    public long getResetCount() {
        return resetCount.get();
    }

    public TabularData getDataSourceList() throws JMException {
        CompositeType rowType = getDruidDataSourceCompositeType();
        String[] indexNames = rowType.keySet().toArray(new String[rowType.keySet().size()]);

        TabularType tabularType = new TabularType("DruidDataSourceStat", "DruidDataSourceStat", rowType, indexNames);
        TabularData data = new TabularDataSupport(tabularType);

        final Set<Object> dataSources = getInstances().keySet();
        for (Object dataSource : dataSources) {
            data.put(getCompositeData(dataSource));
        }

        return data;
    }

    public CompositeDataSupport getCompositeData(Object dataSource) throws JMException {
        CompositeType rowType = getDruidDataSourceCompositeType();

        Map<String, Object> map = DruidDataSourceUtils.getStatDataForMBean(dataSource);

        return new CompositeDataSupport(rowType, map);
    }

    private static CompositeType COMPOSITE_TYPE = null;

    public static CompositeType getDruidDataSourceCompositeType() throws JMException {

        if (COMPOSITE_TYPE != null) {
            return COMPOSITE_TYPE;
        }

        OpenType<?>[] indexTypes = new OpenType<?>[] {
                // 0 - 4
                SimpleType.STRING, //
                SimpleType.STRING, //
                SimpleType.LONG, //
                SimpleType.LONG, //
                SimpleType.LONG, //

                // 5 - 9
                SimpleType.LONG, //
                SimpleType.INTEGER, //
                SimpleType.INTEGER, //
                SimpleType.INTEGER, //
                SimpleType.INTEGER, //

                // 10 - 14
                SimpleType.INTEGER, //
                SimpleType.INTEGER, //
                SimpleType.INTEGER, //
                SimpleType.BOOLEAN, //
                SimpleType.BOOLEAN, //

                // 15 - 19
                SimpleType.BOOLEAN, //
                SimpleType.LONG, //
                SimpleType.LONG, //
                SimpleType.LONG, //
                SimpleType.STRING, //

                // 20 - 24
                SimpleType.STRING, //
                SimpleType.INTEGER, //
                SimpleType.STRING, //
                SimpleType.STRING, //
                SimpleType.LONG, //

                // 25 - 29
                SimpleType.LONG, //
                SimpleType.LONG, //
                SimpleType.LONG, //
                SimpleType.LONG, //
                SimpleType.LONG, //

                // 30 - 34
                SimpleType.LONG, //
                SimpleType.LONG, //
                JMXUtils.getThrowableCompositeType(), //
                JMXUtils.getThrowableCompositeType(), //
                SimpleType.LONG, //

                // 35 - 39
                SimpleType.LONG, //
                SimpleType.LONG, //
                SimpleType.LONG, //
                SimpleType.LONG, //
                SimpleType.STRING, //

                // 40 -
                SimpleType.DATE, //
                SimpleType.DATE, //
                SimpleType.LONG, //
                SimpleType.LONG //
        //
        };

        String[] indexNames = {
                // 0 - 4
                "Name", //
                "URL", //
                "CreateCount", //
                "DestroyCount", //
                "ConnectCount", //

                // 5 - 9
                "CloseCount", //
                "ActiveCount", //
                "PoolingCount", //
                "LockQueueLength", //
                "WaitThreadCount", //

                // 10 - 14
                "InitialSize", //
                "MaxActive", //
                "MinIdle", //
                "PoolPreparedStatements", //
                "TestOnBorrow", //

                // 15 - 19
                "TestOnReturn", //
                "MinEvictableIdleTimeMillis", //
                "ConnectErrorCount", //
                "CreateTimespanMillis", //
                "DbType", //

                // 20 - 24
                "ValidationQuery", //
                "ValidationQueryTimeout", //
                "DriverClassName", //
                "Username", //
                "RemoveAbandonedCount", //

                // 25 - 29
                "NotEmptyWaitCount", //
                "NotEmptyWaitNanos", //
                "ErrorCount", //
                "ReusePreparedStatementCount", //
                "StartTransactionCount", //

                // 30 - 34
                "CommitCount", //
                "RollbackCount", //
                "LastError", //
                "LastCreateError", //
                "PreparedStatementCacheDeleteCount", //

                // 35 - 39
                "PreparedStatementCacheAccessCount", //
                "PreparedStatementCacheMissCount", //
                "PreparedStatementCacheHitCount", //
                "PreparedStatementCacheCurrentCount", //
                "Version" //

                // 40 -
                , "LastErrorTime", //
                "LastCreateErrorTime", //
                "CreateErrorCount", //
                "DiscardCount", //
        //
        };

        String[] indexDescriptions = indexNames;
        COMPOSITE_TYPE = new CompositeType("DataSourceStatistic",
            "DataSource Statistic",
            indexNames,
            indexDescriptions,
            indexTypes);

        return COMPOSITE_TYPE;
    }
}
