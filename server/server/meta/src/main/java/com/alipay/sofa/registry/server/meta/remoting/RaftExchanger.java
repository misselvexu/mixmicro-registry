/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.registry.server.meta.remoting;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.alipay.remoting.ProtocolCode;
import com.alipay.remoting.ProtocolManager;
import com.alipay.remoting.rpc.protocol.RpcProtocol;
import com.alipay.sofa.jraft.util.ThreadPoolMetricSet;
import com.alipay.sofa.jraft.util.ThreadPoolUtil;
import com.alipay.sofa.registry.jraft.LeaderAware;
import com.alipay.sofa.registry.server.meta.metaserver.CurrentDcMetaServer;
import com.alipay.sofa.registry.util.ConcurrentUtils;
import com.alipay.sofa.registry.util.NamedThreadFactory;
import com.codahale.metrics.MetricRegistry;
import com.google.common.annotations.VisibleForTesting;
import org.springframework.beans.factory.annotation.Autowired;

import com.alipay.sofa.jraft.CliService;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.core.CliServiceImpl;
import com.alipay.sofa.jraft.core.NodeImpl;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.option.CliOptions;
import com.alipay.sofa.jraft.rpc.impl.AbstractClientService;
import com.alipay.sofa.registry.common.model.metaserver.nodes.MetaNode;
import com.alipay.sofa.registry.common.model.store.URL;
import com.alipay.sofa.registry.jraft.bootstrap.RaftClient;
import com.alipay.sofa.registry.jraft.bootstrap.RaftServer;
import com.alipay.sofa.registry.jraft.bootstrap.RaftServerConfig;
import com.alipay.sofa.registry.jraft.processor.FollowerProcessListener;
import com.alipay.sofa.registry.jraft.processor.LeaderProcessListener;
import com.alipay.sofa.registry.log.Logger;
import com.alipay.sofa.registry.log.LoggerFactory;
import com.alipay.sofa.registry.net.NetUtil;
import com.alipay.sofa.registry.server.meta.bootstrap.config.MetaServerConfig;
import com.alipay.sofa.registry.server.meta.bootstrap.config.NodeConfig;
import com.alipay.sofa.registry.server.meta.executor.ExecutorManager;

/**
 * @author shangyu.wh
 * @version $Id: RaftExchanger.java, v 0.1 2018-05-22 15:13 shangyu.wh Exp $
 */
public class RaftExchanger {

    private static final Logger LOGGER         = LoggerFactory.getLogger(RaftExchanger.class);

    private static final Logger METRICS_LOGGER = LoggerFactory.getLogger("META-JRAFT-METRICS");

    private static final Logger LOGGER_START   = LoggerFactory.getLogger("META-START-LOGS");

    @Autowired
    private MetaServerConfig    metaServerConfig;

    @Autowired
    private NodeConfig          nodeConfig;

    @Autowired
    private ThreadPoolExecutor  defaultRequestExecutor;

    @Autowired
    private CurrentDcMetaServer currentDcMetaServer;

    @Autowired
    private List<LeaderAware>   leaderAwares;

    private RaftServer          raftServer;

    private RaftClient          raftClient;

    private CliService          cliService;

    private AtomicBoolean       clientStart    = new AtomicBoolean(false);
    private AtomicBoolean       serverStart    = new AtomicBoolean(false);
    private AtomicBoolean       clsStart       = new AtomicBoolean(false);

    /**
     * Start Raft server
     *
     * @param executorManager
     */
    public void startRaftServer(final ExecutorManager executorManager) {
        try {
            if (serverStart.compareAndSet(false, true)) {
                String serverId = NetUtil.genHost(NetUtil.getLocalAddress().getHostAddress(),
                    metaServerConfig.getRaftServerPort());
                String serverConf = getServerConfig();

                raftServer = new RaftServer(metaServerConfig.getRaftDataPath(), getGroup(),
                    serverId, serverConf);
                raftServer.setLeaderProcessListener(new LeaderProcessListener() {
                    @Override
                    public void startProcess() {
                        LOGGER_START.info("Start leader process...");
                        executorManager.startScheduler();
                        new ConcurrentUtils.SafeParaLoop<LeaderAware>(defaultRequestExecutor, leaderAwares) {
                            @Override
                            protected void doRun0(LeaderAware leaderAware) throws Exception {
                                leaderAware.isLeader();
                            }
                        }.run();
                        LOGGER_START.info("Initialize server scheduler success!");
                        PeerId leader = new PeerId(NetUtil.getLocalAddress().getHostAddress(),
                            metaServerConfig.getRaftServerPort());
                        // refer: https://github.com/sofastack/sofa-registry/issues/30
                        registerCurrentNode();
                        raftServer.sendNotify(leader, "leader");
                    }

                    @Override
                    public void stopProcess() {
                        LOGGER_START.info("Stop leader process...");
                        executorManager.stopScheduler();
                        new ConcurrentUtils.SafeParaLoop<LeaderAware>(defaultRequestExecutor, leaderAwares) {
                            @Override
                            protected void doRun0(LeaderAware leaderAware) throws Exception {
                                leaderAware.notLeader();
                            }
                        }.run();
                        LOGGER_START.info("Stop server scheduler success!");
                        PeerId leader = new PeerId(NetUtil.getLocalAddress().getHostAddress(),
                            metaServerConfig.getRaftServerPort());
                        raftServer.sendNotify(leader, "leader");
                    }
                });

                raftServer.setFollowerProcessListener(new FollowerProcessListener() {
                    @Override
                    public void startProcess(PeerId leader) {
                        LOGGER_START.info("Start follower process leader {}...", leader);
                        // refer: https://github.com/sofastack/sofa-registry/issues/31
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException e) {
                            LOGGER_START.error(e.getMessage(), e);
                        }
                        registerCurrentNode();
                        raftServer.sendNotify(leader, "follower");
                    }

                    @Override
                    public void stopProcess(PeerId leader) {
                        LOGGER_START.info("Stop follower process leader {}...", leader);
                        raftServer.sendNotify(leader, "follower");
                    }
                });
                ThreadPoolExecutor raftExecutor = ThreadPoolUtil
                    .newBuilder()
                    .poolName("Raft-Executor")
                    .enableMetric(true)
                    .coreThreads(metaServerConfig.getRaftExecutorMinSize())
                    .maximumThreads(metaServerConfig.getRaftExecutorMaxSize())
                    .keepAliveSeconds(60L)
                    .workQueue(
                        new LinkedBlockingQueue<>(metaServerConfig.getRaftExecutorQueueSize()))
                    .rejectedHandler(new ThreadPoolExecutor.AbortPolicy())//
                    .threadFactory(new NamedThreadFactory("Raft-Processor", true)) //
                    .build();

                ThreadPoolExecutor raftServerExecutor = ThreadPoolUtil
                    .newBuilder()
                    .poolName("RaftServer-Executor")
                    .enableMetric(true)
                    .coreThreads(metaServerConfig.getRaftServerExecutorMinSize())
                    .maximumThreads(metaServerConfig.getRaftServerExecutorMaxSize())
                    .keepAliveSeconds(60L)
                    .workQueue(
                        new LinkedBlockingQueue<>(metaServerConfig.getRaftServerExecutorQueueSize()))
                    .rejectedHandler(new ThreadPoolExecutor.AbortPolicy())//
                    .threadFactory(new NamedThreadFactory("RaftServer-Processor", true)) //
                    .build();

                ThreadPoolExecutor fsmExecutor = ThreadPoolUtil
                    .newBuilder()
                    .poolName("RaftFsm-Executor")
                    .enableMetric(true)
                    .coreThreads(metaServerConfig.getRaftFsmExecutorMinSize())
                    .maximumThreads(metaServerConfig.getRaftFsmExecutorMaxSize())
                    .keepAliveSeconds(60L)
                    .workQueue(
                        new LinkedBlockingQueue<>(metaServerConfig.getRaftFsmExecutorQueueSize()))
                    .rejectedHandler(new ThreadPoolExecutor.AbortPolicy())//
                    .threadFactory(new NamedThreadFactory("RaftFsm-Processor", true)) //
                    .build();

                raftServer.setRaftExecutor(raftExecutor);
                raftServer.setRaftServerExecutor(raftServerExecutor);
                raftServer.setFsmExecutor(fsmExecutor);
                RaftServerConfig raftServerConfig = new RaftServerConfig();
                raftServerConfig.setMetricsLogger(METRICS_LOGGER);
                raftServerConfig.setEnableMetrics(metaServerConfig.isEnableMetrics());
                raftServerConfig.setElectionTimeoutMs(metaServerConfig.getRaftElectionTimeout());
                if (metaServerConfig.getRockDBCacheSize() > 0) {
                    raftServerConfig.setRockDBCacheSize(metaServerConfig.getRockDBCacheSize());
                }
                raftServer.start(raftServerConfig);

                ThreadPoolExecutor boltDefaultExecutor = (ThreadPoolExecutor) ProtocolManager
                    .getProtocol(ProtocolCode.fromBytes(RpcProtocol.PROTOCOL_CODE))
                    .getCommandHandler().getDefaultExecutor();

                Map<String, ThreadPoolExecutor> executorMap = new HashMap<>();
                executorMap.put("Raft-Executor", raftExecutor);
                executorMap.put("RaftServer-Executor", raftServerExecutor);
                executorMap.put("RaftFsm-Executor", fsmExecutor);
                executorMap.put("Bolt-default-executor", boltDefaultExecutor);
                metricExecutors(raftServer.getNode().getNodeMetrics().getMetricRegistry(),
                    executorMap);
            }
        } catch (Exception e) {
            serverStart.set(false);
            LOGGER_START.error("Start raft server error!", e);
            throw new RuntimeException("Start raft server error!", e);
        }
    }

    private void metricExecutors(MetricRegistry metricRegistry,
                                 Map<String, ThreadPoolExecutor> executorMap) {
        for (String name : executorMap.keySet()) {
            metricRegistry.register(name, new ThreadPoolMetricSet(executorMap.get(name)));
        }
    }

    /**
     * start raft client
     */
    public void startRaftClient() {
        try {
            if (clientStart.compareAndSet(false, true)) {
                String serverConf = getServerConfig();
                if (raftServer != null && raftServer.getNode() != null) {
                    //TODO this cannot be invoke,because RaftAnnotationBeanPostProcessor.getProxy will start first
                    raftClient = new RaftClient(getGroup(), serverConf,
                        (AbstractClientService) (((NodeImpl) raftServer.getNode()).getRpcService()));
                } else {
                    raftClient = new RaftClient(getGroup(), serverConf);
                }
                raftClient.start();
            }
        } catch (Exception e) {
            clientStart.set(false);
            LOGGER_START.error("Start raft client error!", e);
            throw new RuntimeException("Start raft client error!", e);
        }
    }

    /**
     * start cli service
     */
    public void startCliService() {
        if (clsStart.compareAndSet(false, true)) {
            try {
                cliService = new CliServiceImpl();
                cliService.init(new CliOptions());
            } catch (Exception e) {
                LOGGER_START.error("Start raft cliService error!", e);
                throw new RuntimeException("Start raft cliService error!", e);
            }
        }
    }

    private void registerCurrentNode() {
        Map<String, Collection<String>> metaMap = nodeConfig.getMetaNodeIP();
        //if current ip existed in config list,register it
        if (metaMap != null && metaMap.size() > 0) {
            Collection<String> metas = metaMap.get(nodeConfig.getLocalDataCenter());
            String ip = NetUtil.getLocalAddress().getHostAddress();
            if (metas != null && metas.contains(ip)) {
                currentDcMetaServer.renew(new MetaNode(new URL(ip, 0), nodeConfig.getLocalDataCenter()), 0);
            } else {
                LOGGER_START.error(
                    "Register CurrentNode fail!meta node list config not contains current ip {}",
                    ip);
                throw new RuntimeException(
                    "Register CurrentNode fail!meta node list config not contains current ip!");
            }
        }
    }

    /**
     * api for change meta node
     */
    public void changePeer(List<String> ipAddressList) {
        try {
            if (cliService != null) {
                Configuration peersConf = new Configuration();
                for (String ipAddress : ipAddressList) {
                    PeerId peer = new PeerId(ipAddress, metaServerConfig.getRaftServerPort());
                    peersConf.addPeer(peer);
                }

                Status status = cliService.changePeers(getGroup(), getCurrentConfiguration(),
                    peersConf);

                if (!status.isOk()) {
                    LOGGER.error("CliService change peer fail!error message {}",
                        status.getErrorMsg());
                    throw new RuntimeException("CliService change peer fail!error message "
                                               + status.getErrorMsg());
                }
            } else {
                LOGGER.error("cliService can't be null,it must be init first!");
                throw new RuntimeException("cliService can't be null,it must be init first!");
            }
        } catch (Exception e) {
            LOGGER.error("CliService change peer error!", e);
            throw new RuntimeException("CliService change peer error!", e);
        }
    }

    /**
     * api for reset meta node
     */
    public void resetPeer(List<String> ipAddressList) {
        try {
            if (cliService != null) {
                Configuration peersConf = new Configuration();
                for (String ipAddress : ipAddressList) {
                    PeerId peer = new PeerId(ipAddress, metaServerConfig.getRaftServerPort());
                    peersConf.addPeer(peer);
                }
                String ip = NetUtil.getLocalAddress().getHostAddress();
                PeerId localPeer = new PeerId(ip, metaServerConfig.getRaftServerPort());
                Status status = cliService.resetPeer(getGroup(), localPeer, peersConf);
                if (!status.isOk()) {
                    LOGGER.error("CliService reset peer fail!error message {}",
                        status.getErrorMsg());
                    throw new RuntimeException("CliService reset peer fail!error message "
                                               + status.getErrorMsg());
                }
            } else {
                LOGGER.error("cliService can't be null,it must be init first!");
                throw new RuntimeException("cliService can't be null,it must be init first!");
            }
        } catch (Exception e) {
            LOGGER.error("CliService reset peer error!", e);
            throw new RuntimeException("CliService reset peer error!", e);
        }
    }

    /**
     * api for remove meta node
     *
     * @param ipAddress
     */
    public void removePeer(String ipAddress) {
        try {
            if (cliService != null) {
                PeerId peer = new PeerId(ipAddress, metaServerConfig.getRaftServerPort());
                Status status = cliService.removePeer(getGroup(), getCurrentConfiguration(), peer);
                if (!status.isOk()) {
                    LOGGER.error("CliService remove peer fail!error message {}",
                        status.getErrorMsg());
                    throw new RuntimeException("CliService remove peer fail!error message "
                                               + status.getErrorMsg());
                }
            } else {
                LOGGER.error("cliService can't be null,it must be init first!");
                throw new RuntimeException("cliService can't be null,it must be init first!");
            }
        } catch (Exception e) {
            LOGGER.error("CliService remove peer error!", e);
            throw new RuntimeException("CliService remove peer error!", e);
        }
    }

    /**
     * api for get all peers
     */
    public List<PeerId> getPeers() {
        try {
            Configuration currentConf = getCurrentConfiguration();
            return currentConf.getPeers();
        } catch (Exception e) {
            String msg = "Get peers error:" + e.getMessage();
            LOGGER.error(msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    private Configuration getCurrentConfiguration() {
        return ((NodeImpl) raftServer.getNode()).getCurrentConf();
    }

    /**
     * refresh configuration and refresh leader
     */
    public void refreshRaftClient() {
        raftClient.refreshLeader();
    }

    public void shutdown() {
        if (raftServer != null) {
            raftServer.shutdown();
        }
        if (raftClient != null) {
            raftClient.shutdown();
        }
        if (cliService != null) {
            cliService.shutdown();
        }
    }

    private String getServerConfig() {
        String ret = "";
        Set<String> ips = nodeConfig.getDataCenterMetaServers(nodeConfig.getLocalDataCenter());
        if (ips != null && !ips.isEmpty()) {
            ret = ips.stream().map(ip -> ip + ":" + metaServerConfig.getRaftServerPort())
                    .collect(Collectors.joining(","));
        }
        if (ret.isEmpty()) {
            throw new IllegalArgumentException("Init raft server config error!");
        }
        return ret;
    }

    private String getGroup() {
        return metaServerConfig.getRaftGroup() + "_" + nodeConfig.getLocalDataCenter();
    }

    /**
     * Getter method for property <tt>raftClient</tt>.
     *
     * @return property value of raftClient
     */
    public RaftClient getRaftClient() {
        if (raftClient == null) {
            startRaftClient();
            if(LOGGER.isInfoEnabled()) {
                LOGGER.info("[getRaftClient]Raft client before started!");
            }
        }
        return raftClient;
    }

    /**
     * Getter method for property <tt>clientStart</tt>.
     *
     * @return property value of clientStart
     */
    public AtomicBoolean getClientStart() {
        return clientStart;
    }

    /**
     * Getter method for property <tt>serverStart</tt>.
     *
     * @return property value of serverStart
     */
    public AtomicBoolean getServerStart() {
        return serverStart;
    }

    /**
     * Getter method for property <tt>clsStart</tt>.
     *
     * @return property value of clsStart
     */
    public AtomicBoolean getClsStart() {
        return clsStart;
    }

    /**
     * Getter method for property <tt>raftServer</tt>.
     *
     * @return property value of raftServer
     */
    public RaftServer getRaftServer() {
        return raftServer;
    }

    @VisibleForTesting
    public RaftExchanger setMetaServerConfig(MetaServerConfig metaServerConfig) {
        this.metaServerConfig = metaServerConfig;
        return this;
    }

    @VisibleForTesting
    public RaftExchanger setNodeConfig(NodeConfig nodeConfig) {
        this.nodeConfig = nodeConfig;
        return this;
    }

    @VisibleForTesting
    public RaftExchanger setCurrentDcMetaServer(CurrentDcMetaServer currentDcMetaServer) {
        this.currentDcMetaServer = currentDcMetaServer;
        return this;
    }

    @VisibleForTesting
    public RaftExchanger setLeaderAwares(List<LeaderAware> leaderAwares) {
        this.leaderAwares = leaderAwares;
        return this;
    }
}