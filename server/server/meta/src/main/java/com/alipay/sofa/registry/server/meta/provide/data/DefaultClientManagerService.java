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
package com.alipay.sofa.registry.server.meta.provide.data;

import com.alipay.sofa.registry.common.model.ServerDataBox;
import com.alipay.sofa.registry.common.model.constants.ValueConstants;
import com.alipay.sofa.registry.common.model.metaserver.ClientManagerAddress;
import com.alipay.sofa.registry.common.model.metaserver.ProvideData;
import com.alipay.sofa.registry.common.model.metaserver.ProvideDataChangeEvent;
import com.alipay.sofa.registry.log.Logger;
import com.alipay.sofa.registry.log.LoggerFactory;
import com.alipay.sofa.registry.server.meta.MetaLeaderService;
import com.alipay.sofa.registry.server.meta.bootstrap.config.MetaServerConfig;
import com.alipay.sofa.registry.server.meta.resource.ClientManagerResource;
import com.alipay.sofa.registry.store.api.DBResponse;
import com.alipay.sofa.registry.store.api.meta.ClientManagerAddressRepository;
import com.alipay.sofa.registry.util.ConcurrentUtils;
import com.alipay.sofa.registry.util.LoopRunnable;
import com.alipay.sofa.registry.util.MathUtils;
import com.alipay.sofa.registry.util.WakeUpLoopRunnable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

/**
 * @author xiaojian.xj
 * @version $Id: DefaultClientManagerService.java, v 0.1 2021年05月12日 15:16 xiaojian.xj Exp $
 */
public class DefaultClientManagerService implements ClientManagerService {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultClientManagerService.class);

  private static final Logger taskLogger =
      LoggerFactory.getLogger(ClientManagerResource.class, "[Task]");

  protected final ReadWriteLock lock = new ReentrantReadWriteLock();

  /** The Read lock. */
  protected final Lock readLock = lock.readLock();

  /** The Write lock. */
  protected final Lock writeLock = lock.writeLock();

  private volatile long version;

  private final AtomicReference<ConcurrentHashMap.KeySetView> cache = new AtomicReference<>();

  private final ClientManagerWatcher watcher = new ClientManagerWatcher();

  private final ClientManagerRefresher refresher = new ClientManagerRefresher();

  private volatile boolean refreshFinish;

  @Autowired private ClientManagerAddressRepository ClientManagerAddressRepository;

  @Autowired private DefaultProvideDataNotifier provideDataNotifier;

  @Autowired private MetaServerConfig metaServerConfig;

  @Autowired private MetaLeaderService metaLeaderService;

  private int refreshLimit;

  private void init() {
    writeLock.lock();
    try {
      version = -1L;
      cache.set(new ConcurrentHashMap<>().newKeySet());
      refreshFinish = false;
    } finally {
      writeLock.unlock();
    }

  }

  @PostConstruct
  public void postConstruct() {
    init();

    ConcurrentUtils.createDaemonThread("clientManager_watcher", watcher).start();
    ConcurrentUtils.createDaemonThread("clientManager_refresher", refresher).start();

    refreshLimit = metaServerConfig.getClientManagerRefreshLimit();
  }

  /**
   * client open
   *
   * @param ipSet
   * @return
   */
  @Override
  public boolean clientOpen(Set<String> ipSet) {
    return ClientManagerAddressRepository.clientOpen(ipSet);
  }

  /**
   * client off
   *
   * @param ipSet
   * @return
   */
  @Override
  public boolean clientOff(Set<String> ipSet) {
    return ClientManagerAddressRepository.clientOff(ipSet);
  }

  /**
   * query client off ips
   *
   * @return
   */
  @Override
  public DBResponse<ProvideData> queryClientOffSet() {
    if (!refreshFinish) {
      LOGGER.warn("query client manager cache before refreshFinish");
      return DBResponse.notfound().build();
    }

    readLock.lock();
    try {
      ProvideData provideData =
          new ProvideData(
              new ServerDataBox(cache.get()),
              ValueConstants.CLIENT_OFF_ADDRESS_DATA_ID,
              version);
      return DBResponse.ok(provideData).build();
    } catch (Throwable t) {
      LOGGER.error("query client manager cache error.", t);
      return DBResponse.notfound().build();
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public void becomeLeader() {
    init();
    refresher.wakeup();
  }

  @Override
  public void loseLeader() {}

  private final class ClientManagerRefresher extends WakeUpLoopRunnable {

    @Override
    public void runUnthrowable() {
      List<ClientManagerAddress> totalRet = listFromStorage();
      if (CollectionUtils.isEmpty(totalRet)) {
        return;
      }

      ClientManagerAggregation aggregation = aggregate(totalRet);

      if (aggregation == EMPTY_AGGREGATION || doRefresh(aggregation)) {
        refreshFinish = true;
        LOGGER.info("finish load clientManager, refreshFinish:{}", refreshFinish);
        fireClientManagerChangeNotify(version, ValueConstants.CLIENT_OFF_ADDRESS_DATA_ID);
      }
    }

    private List<ClientManagerAddress> listFromStorage() {
      if (!metaLeaderService.amILeader()) {
        return null;
      }

      final int total = ClientManagerAddressRepository.queryTotalCount();

      // add 10, query the new records which inserted when scanning
      final int refreshCount = MathUtils.divideCeil(total, refreshLimit) + 10;
      LOGGER.info("begin load clientManager, total count {}, rounds={}", total, refreshCount);

      long maxTemp = -1;
      int refreshTotal = 0;
      List<ClientManagerAddress> totalRet = new ArrayList<>();
      for (int i = 0; i < refreshCount; i++) {
        List<ClientManagerAddress> ClientManagerAddress =
            ClientManagerAddressRepository.queryAfterThan(maxTemp, refreshLimit);
        final int num = ClientManagerAddress.size();
        LOGGER.info("load clientManager in round={}, num={}", i, num);
        if (num == 0) {
          break;
        }

        refreshTotal += num;
        maxTemp = ClientManagerAddress.get(ClientManagerAddress.size() - 1).getId();
        totalRet.addAll(ClientManagerAddress);
      }
      LOGGER.info("finish load clientManager, total={}, maxId={}", refreshTotal, maxTemp);
      return totalRet;
    }

    @Override
    public int getWaitingMillis() {
      return metaServerConfig.getClientManagerRefreshMillis();
    }
  }

  private final class ClientManagerWatcher extends LoopRunnable {

    @Override
    public void runUnthrowable() {
      if (!metaLeaderService.amILeader()) {
        return;
      }

      List<ClientManagerAddress> ClientManagerAddress =
          ClientManagerAddressRepository.queryAfterThan(version);

      if (CollectionUtils.isEmpty(ClientManagerAddress)) {
        return;
      }

      ClientManagerAggregation aggregation = aggregate(ClientManagerAddress);

      LOGGER.info("client manager watcher aggregation:{}", aggregation);
      if (doRefresh(aggregation)) {
        fireClientManagerChangeNotify(version, ValueConstants.CLIENT_OFF_ADDRESS_DATA_ID);
      }
    }

    @Override
    public void waitingUnthrowable() {
      ConcurrentUtils.sleepUninterruptibly(
          metaServerConfig.getClientManagerWatchMillis(), TimeUnit.MILLISECONDS);
    }
  }

  private ClientManagerAggregation aggregate(List<ClientManagerAddress> ClientManagerAddress) {
    if (CollectionUtils.isEmpty(ClientManagerAddress)) {
      return EMPTY_AGGREGATION;
    }

    long max = ClientManagerAddress.get(ClientManagerAddress.size() - 1).getId();
    Set<String> clientOffAddress = new HashSet<>();
    Set<String> clientOpenAddress = new HashSet<>();
    for (ClientManagerAddress clientManagerAddress : ClientManagerAddress) {
      switch (clientManagerAddress.getOperation()) {
        case ValueConstants.CLIENT_OFF:
          clientOffAddress.add(clientManagerAddress.getAddress());
          clientOpenAddress.remove(clientManagerAddress.getAddress());
          break;
        case ValueConstants.CLIENT_OPEN:
          clientOpenAddress.add(clientManagerAddress.getAddress());
          clientOffAddress.remove(clientManagerAddress.getAddress());
          break;
        default:
          LOGGER.error("error operation type: {}", clientManagerAddress);
          break;
      }
    }
    return new ClientManagerAggregation(max, clientOffAddress, clientOpenAddress);
  }

  private boolean doRefresh(ClientManagerAggregation aggregation) {
    long before;
    writeLock.lock();
    try {
      before = version;
      if (before >= aggregation.max) {
        return false;
      }
      version = aggregation.max;
      cache.get().addAll(aggregation.clientOffAddress);
      cache.get().removeAll(aggregation.clientOpenAddress);
    } catch (Throwable t) {
      LOGGER.error("refresh client manager cache error.", t);
      return false;
    } finally {
      writeLock.unlock();
    }
    LOGGER.info(
        "doRefresh success, before:{}, after:{}, clientOff:{}, clientOpen:{}",
        before,
        aggregation.max,
        aggregation.clientOffAddress,
        aggregation.clientOpenAddress);
    return true;
  }

  private void fireClientManagerChangeNotify(Long version, String dataInfoId) {

    ProvideDataChangeEvent provideDataChangeEvent = new ProvideDataChangeEvent(dataInfoId, version);

    if (taskLogger.isInfoEnabled()) {
      taskLogger.info(
          "send CLIENT_MANAGER_CHANGE_NOTIFY_TASK notifyClientManagerChange: {}",
          provideDataChangeEvent);
    }
    provideDataNotifier.notifyProvideDataChange(provideDataChangeEvent);
  }

  private final ClientManagerAggregation EMPTY_AGGREGATION =
      new ClientManagerAggregation(-1L, Sets.newHashSet(), Sets.newHashSet());

  final class ClientManagerAggregation {
    final long max;

    final Set<String> clientOffAddress;

    final Set<String> clientOpenAddress;

    public ClientManagerAggregation(
        long max, Set<String> clientOffAddress, Set<String> clientOpenAddress) {
      this.max = max;
      this.clientOffAddress = clientOffAddress;
      this.clientOpenAddress = clientOpenAddress;
    }

    @Override
    public String toString() {
      return "ClientManagerAggregation{"
          + "max="
          + max
          + ", clientOffAddress="
          + clientOffAddress
          + ", clientOpenAddress="
          + clientOpenAddress
          + '}';
    }
  }

  /**
   * Setter method for property <tt>ClientManagerAddressRepository</tt>.
   *
   * @param ClientManagerAddressRepository value to be assigned to property
   *     ClientManagerAddressRepository
   */
  @VisibleForTesting
  public void setClientManagerAddressRepository(
      ClientManagerAddressRepository ClientManagerAddressRepository) {
    this.ClientManagerAddressRepository = ClientManagerAddressRepository;
  }
}