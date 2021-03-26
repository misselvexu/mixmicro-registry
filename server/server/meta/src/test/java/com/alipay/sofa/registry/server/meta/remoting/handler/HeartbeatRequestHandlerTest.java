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
package com.alipay.sofa.registry.server.meta.remoting.handler;

import static org.mockito.Mockito.*;

import com.alipay.sofa.registry.common.model.GenericResponse;
import com.alipay.sofa.registry.common.model.Node;
import com.alipay.sofa.registry.common.model.metaserver.inter.heartbeat.HeartbeatRequest;
import com.alipay.sofa.registry.common.model.metaserver.nodes.DataNode;
import com.alipay.sofa.registry.common.model.slot.SlotConfig;
import com.alipay.sofa.registry.remoting.Channel;
import com.alipay.sofa.registry.server.meta.AbstractMetaServerTestBase;
import com.alipay.sofa.registry.server.meta.MetaLeaderService;
import com.alipay.sofa.registry.server.meta.bootstrap.config.NodeConfig;
import com.alipay.sofa.registry.server.meta.lease.data.DataServerManager;
import com.alipay.sofa.registry.server.meta.lease.session.SessionServerManager;
import com.alipay.sofa.registry.server.meta.metaserver.impl.DefaultCurrentDcMetaServer;
import com.alipay.sofa.registry.server.meta.slot.manager.DefaultSlotManager;
import java.util.concurrent.TimeoutException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class HeartbeatRequestHandlerTest extends AbstractMetaServerTestBase {

  private HeartbeatRequestHandler handler = new HeartbeatRequestHandler();

  @Mock private Channel channel;

  @Mock private DefaultCurrentDcMetaServer currentDcMetaServer;

  @Mock private SessionServerManager sessionServerManager;

  @Mock private DataServerManager dataServerManager;

  @Mock private MetaLeaderService metaLeaderService;

  private DefaultSlotManager slotManager;

  @Before
  public void beforeHeartbeatRequestHandlerTest() {
    MockitoAnnotations.initMocks(this);
    NodeConfig nodeConfig = mock(NodeConfig.class);
    handler.setNodeConfig(nodeConfig);
    when(nodeConfig.getLocalDataCenter()).thenReturn(getDc());
    slotManager = new DefaultSlotManager(metaLeaderService);
    handler
        .setDefaultSlotManager(slotManager)
        .setCurrentDcMetaServer(currentDcMetaServer)
        .setMetaLeaderElector(metaLeaderService);
    when(currentDcMetaServer.getDataServerManager()).thenReturn(dataServerManager);
    when(currentDcMetaServer.getSessionServerManager()).thenReturn(sessionServerManager);
    when(currentDcMetaServer.getSlotTable()).thenReturn(slotManager.getSlotTable());
  }

  @Test
  public void testDoHandle() throws TimeoutException, InterruptedException {
    makeMetaLeader();
    slotManager.refresh(randomSlotTable(randomDataNodes(3)));
    HeartbeatRequest<Node> heartbeat =
        new HeartbeatRequest<>(
            new DataNode(randomURL(randomIp()), getDc()),
            0,
            getDc(),
            System.currentTimeMillis(),
            new SlotConfig.SlotBasicInfo(
                SlotConfig.SLOT_NUM, SlotConfig.SLOT_REPLICAS, SlotConfig.FUNC));
    Assert.assertTrue(((GenericResponse) handler.doHandle(channel, heartbeat)).isSuccess());
  }

  @Test
  public void testDoHandleWithErrDC() throws TimeoutException, InterruptedException {
    makeMetaLeader();
    slotManager.refresh(randomSlotTable(randomDataNodes(3)));
    HeartbeatRequest<Node> heartbeat =
        new HeartbeatRequest<>(
            new DataNode(randomURL(randomIp()), getDc()),
            0,
            "ERROR_DC",
            System.currentTimeMillis(),
            new SlotConfig.SlotBasicInfo(
                SlotConfig.SLOT_NUM, SlotConfig.SLOT_REPLICAS, SlotConfig.FUNC));
    handler.doHandle(channel, heartbeat);
    verify(channel, times(1)).close();
  }

  @Test
  public void testDoHandleWithErrSlotConfig() throws TimeoutException, InterruptedException {
    makeMetaLeader();
    slotManager.refresh(randomSlotTable(randomDataNodes(3)));
    HeartbeatRequest<Node> heartbeat =
        new HeartbeatRequest<>(
            new DataNode(randomURL(randomIp()), getDc()),
            0,
            getDc(),
            System.currentTimeMillis(),
            new SlotConfig.SlotBasicInfo(
                SlotConfig.SLOT_NUM - 1, SlotConfig.SLOT_REPLICAS, SlotConfig.FUNC));
    handler.doHandle(channel, heartbeat);
    verify(channel, times(1)).close();

    heartbeat =
        new HeartbeatRequest<>(
            new DataNode(randomURL(randomIp()), getDc()),
            0,
            getDc(),
            System.currentTimeMillis(),
            new SlotConfig.SlotBasicInfo(
                SlotConfig.SLOT_NUM, SlotConfig.SLOT_REPLICAS - 1, SlotConfig.FUNC));
    handler.doHandle(channel, heartbeat);
    verify(channel, times(2)).close();

    heartbeat =
        new HeartbeatRequest<>(
            new DataNode(randomURL(randomIp()), getDc()),
            0,
            getDc(),
            System.currentTimeMillis(),
            new SlotConfig.SlotBasicInfo(SlotConfig.SLOT_NUM, SlotConfig.SLOT_REPLICAS, "unknown"));
    handler.doHandle(channel, heartbeat);
    verify(channel, times(3)).close();
  }

  @Test
  public void testInterest() {
    Assert.assertEquals(HeartbeatRequest.class, handler.interest());
  }
}
