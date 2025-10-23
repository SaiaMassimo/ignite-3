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

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.apache.ignite.internal.cluster.management.topology.api.LogicalNode;
import org.apache.ignite.internal.cluster.management.topology.api.LogicalTopologyEventListener;
import org.apache.ignite.internal.cluster.management.topology.api.LogicalTopologyService;
import org.apache.ignite.internal.cluster.management.topology.api.LogicalTopologySnapshot;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;

/**
 * Pre-warmer for partition distribution algorithm.
 * Listens to topology changes and proactively updates the distribution function's topology mapping.
 * This optimizes performance by avoiding topology checks during partition assignment.
 */
public class PartitionDistributionPreWarmer implements LogicalTopologyEventListener {
    private static final IgniteLogger LOG = Loggers.forClass(PartitionDistributionPreWarmer.class);

    private final ThreadSafeMementoDistributionFunction distributionFunction;
    private final LogicalTopologyService topologyService;
    private final Executor asyncExecutor;

    /** Statistics counters. */
    private final AtomicLong topologyUpdatesCount = new AtomicLong(0);
    private final AtomicLong topologyUpdateErrors = new AtomicLong(0);

    /**
     * Creates a new pre-warmer.
     *
     * @param distributionFunction The distribution function to pre-warm
     * @param topologyService The topology service to listen to
     * @param asyncExecutor Executor for async topology updates
     */
    public PartitionDistributionPreWarmer(
            ThreadSafeMementoDistributionFunction distributionFunction,
            LogicalTopologyService topologyService,
            Executor asyncExecutor
    ) {
        this.distributionFunction = distributionFunction;
        this.topologyService = topologyService;
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * Starts the pre-warmer by registering it as a topology listener.
     * Also performs an initial topology update.
     */
    public void start() {
        topologyService.addEventListener(this);

        // Perform initial topology update
        updateTopologyAsync()
                .thenRun(() -> LOG.info("Pre-warmer started successfully"))
                .exceptionally(ex -> {
                    LOG.warn("Failed to perform initial topology update", ex);
                    return null;
                });
    }

    /**
     * Stops the pre-warmer by unregistering it and stopping the distribution function.
     */
    public void stop() {
        topologyService.removeEventListener(this);
        distributionFunction.stop();
        LOG.info("Pre-warmer stopped");
    }

    @Override
    public void onNodeJoined(LogicalNode joinedNode, LogicalTopologySnapshot newTopology) {
        LOG.info("Node joined: {}, updating topology", joinedNode.name());
        updateTopologyAsync(newTopology);
    }

    @Override
    public void onNodeLeft(LogicalNode leftNode, LogicalTopologySnapshot newTopology) {
        LOG.info("Node left: {}, updating topology", leftNode.name());
        updateTopologyAsync(newTopology);
    }

    @Override
    public void onTopologyLeap(LogicalTopologySnapshot newTopology) {
        LOG.info("Topology leap detected, updating topology");
        updateTopologyAsync(newTopology);
    }

    /**
     * Asynchronously updates the topology from the current logical topology.
     */
    private CompletableFuture<Void> updateTopologyAsync() {
        return CompletableFuture.supplyAsync(() -> {
            LogicalTopologySnapshot topology = topologyService.localLogicalTopology();
            return topology;
        }, asyncExecutor)
                .thenCompose(this::updateTopologyAsync)
                .exceptionally(ex -> {
                    topologyUpdateErrors.incrementAndGet();
                    LOG.error("Failed to update topology", ex);
                    return null;
                });
    }

    /**
     * Asynchronously updates the topology from the given snapshot.
     */
    private CompletableFuture<Void> updateTopologyAsync(LogicalTopologySnapshot topology) {
        return CompletableFuture.runAsync(() -> {
            Set<String> nodeNames = topology.nodes().stream()
                    .map(LogicalNode::name)
                    .collect(Collectors.toSet());

            distributionFunction.updateTopology(nodeNames);
            topologyUpdatesCount.incrementAndGet();

            LOG.debug("Topology updated with {} nodes", nodeNames.size());
        }, asyncExecutor);
    }

    /**
     * Gets the number of topology updates performed.
     */
    public long getTopologyUpdatesCount() {
        return topologyUpdatesCount.get();
    }

    /**
     * Gets the number of topology update errors.
     */
    public long getTopologyUpdateErrors() {
        return topologyUpdateErrors.get();
    }
}

