/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.partitiondistribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.ignite.internal.cluster.management.topology.api.LogicalNode;
import org.apache.ignite.internal.cluster.management.topology.api.LogicalTopologyEventListener;
import org.apache.ignite.internal.cluster.management.topology.api.LogicalTopologyService;
import org.apache.ignite.internal.cluster.management.topology.api.LogicalTopologySnapshot;
import org.apache.ignite.network.NetworkAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link PartitionDistributionPreWarmer}.
 */
public class PartitionDistributionPreWarmerTest {
    private ThreadSafeMementoDistributionFunction distributionFunction;
    private LogicalTopologyService topologyService;
    private ExecutorService executorService;
    private PartitionDistributionPreWarmer preWarmer;

    @BeforeEach
    public void setUp() {
        distributionFunction = new ThreadSafeMementoDistributionFunction(1);
        topologyService = mock(LogicalTopologyService.class);
        executorService = Executors.newFixedThreadPool(2);
        preWarmer = new PartitionDistributionPreWarmer(distributionFunction, topologyService, executorService);
    }

    @AfterEach
    public void tearDown() {
        if (preWarmer != null) {
            preWarmer.stop();
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    @Test
    public void testStart() {
        LogicalTopologySnapshot topology = createTopology(Set.of("node1", "node2", "node3"));
        when(topologyService.localLogicalTopology()).thenReturn(topology);

        preWarmer.start();

        verify(topologyService).addEventListener(any(LogicalTopologyEventListener.class));
        assertEquals(Set.of("node1", "node2", "node3"), distributionFunction.getCurrentTopology());
    }

    @Test
    public void testOnNodeJoined() throws InterruptedException {
        preWarmer.start();

        CountDownLatch latch = new CountDownLatch(1);
        LogicalTopologySnapshot newTopology = createTopology(Set.of("node1", "node2", "node3", "node4"));
        ClusterNode newNode = createNode("node4");

        preWarmer.onNodeJoined(newNode, newTopology);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(Set.of("node1", "node2", "node3", "node4"), distributionFunction.getCurrentTopology());
    }

    @Test
    public void testOnNodeLeft() throws InterruptedException {
        preWarmer.start();

        // First, add some nodes
        LogicalTopologySnapshot initialTopology = createTopology(Set.of("node1", "node2", "node3"));
        preWarmer.onNodeJoined(null, initialTopology);

        Thread.sleep(100); // Wait for async update

        // Then remove a node
        LogicalTopologySnapshot newTopology = createTopology(Set.of("node1", "node3"));
        ClusterNode leftNode = createNode("node2");

        preWarmer.onNodeLeft(leftNode, newTopology);

        Thread.sleep(100); // Wait for async update
        assertEquals(Set.of("node1", "node3"), distributionFunction.getCurrentTopology());
    }

    @Test
    public void testOnTopologyLeap() throws InterruptedException {
        preWarmer.start();

        LogicalTopologySnapshot newTopology = createTopology(Set.of("node1", "node2", "node5"));
        preWarmer.onTopologyLeap(newTopology);

        Thread.sleep(100); // Wait for async update
        assertEquals(Set.of("node1", "node2", "node5"), distributionFunction.getCurrentTopology());
    }

    @Test
    public void testStop() {
        preWarmer.start();
        preWarmer.stop();

        verify(topologyService).removeEventListener(any(LogicalTopologyEventListener.class));
        
        // Distribution function should be stopped
        assertTrue(distributionFunction.getCurrentTopology().isEmpty());
    }

    @Test
    public void testStatistics() throws InterruptedException {
        preWarmer.start();

        assertEquals(1, preWarmer.getTopologyUpdatesCount()); // Initial update

        LogicalTopologySnapshot topology1 = createTopology(Set.of("node1", "node2"));
        preWarmer.onNodeJoined(null, topology1);

        Thread.sleep(100);

        LogicalTopologySnapshot topology2 = createTopology(Set.of("node1", "node2", "node3"));
        preWarmer.onNodeJoined(null, topology2);

        Thread.sleep(100);

        assertTrue(preWarmer.getTopologyUpdatesCount() >= 3); // Initial + 2 updates
        assertEquals(0, preWarmer.getTopologyUpdateErrors());
    }

    private LogicalTopologySnapshot createTopology(Set<String> nodeNames) {
        List<LogicalNode> nodes = nodeNames.stream()
                .map((String name) -> createNode(name))
                .collect(Collectors.toList());
        return new LogicalTopologySnapshot(1, nodes, Collections.emptySet());
    }

    private LogicalNode createNode(String name) {
        return new LogicalNode(java.util.UUID.randomUUID(), name, new NetworkAddress("localhost", 3344));
    }
}

