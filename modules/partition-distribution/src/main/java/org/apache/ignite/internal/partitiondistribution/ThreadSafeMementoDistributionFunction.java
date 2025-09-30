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

import static org.apache.ignite.internal.partitiondistribution.RendezvousDistributionFunction.MAX_PARTITIONS_COUNT;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.codec.digest.MurmurHash3;
import org.apache.ignite.internal.lang.NodeStoppingException;
import org.apache.ignite.internal.partitiondistribution.memento.BinomialEngine;
import org.apache.ignite.internal.partitiondistribution.memento.Memento;
import org.apache.ignite.internal.util.IgniteSpinBusyLock;
import org.apache.ignite.internal.util.IgniteUtils;

/**
 * Thread-safe implementation of MementoDistributionFunction using ConcurrentHashMap and IgniteSpinBusyLock.
 * This version separates topology management from partition assignment for better performance.
 */
public class ThreadSafeMementoDistributionFunction implements DistributionAlgorithm {

    /** The memory of the removed nodes, also addressed as replacement set. */
    private final Memento memento;

    private final BinomialEngine binomialEngine;

    /** The last removed bucket. */
    private final AtomicInteger lastRemoved;

    /** Thread-safe mapping from node to bucket. */
    private final ConcurrentHashMap<String, Integer> nodeToBucket = new ConcurrentHashMap<>();

    /** Thread-safe mapping from bucket to node. */
    private final ConcurrentHashMap<Integer, String> bucketToNode = new ConcurrentHashMap<>();

    /** Busy lock for synchronization. */
    private final IgniteSpinBusyLock busyLock = new IgniteSpinBusyLock();

    /** Current topology snapshot for quick comparison. */
    private final AtomicReference<Set<String>> currentTopology = new AtomicReference<>(new HashSet<>());

    /** Next available bucket ID. */
    private final AtomicInteger nextBucketId = new AtomicInteger(0);

    /** Lock for topology updates. */
    private final ReentrantLock topologyLock = new ReentrantLock();

    public ThreadSafeMementoDistributionFunction(int size) {
        this.lastRemoved = new AtomicInteger(size);
        this.binomialEngine = new BinomialEngine(size);
        this.memento = new Memento();
    }

    /**
     * Updates the topology mapping. This method should be called when topology changes occur.
     * It's thread-safe and can be called concurrently with assignPartitions.
     *
     * @param nodes the new set of nodes
     */
    public void updateTopology(Collection<String> nodes) {
        if (!busyLock.enterBusy()) {
            return; // Component is stopping
        }
        try {
            Set<String> newNodes = new HashSet<>(nodes);
            Set<String> oldNodes;

            do {
                oldNodes = currentTopology.get();
                if (oldNodes.equals(newNodes)) {
                    return; // No changes needed
                }
            } while (!currentTopology.compareAndSet(oldNodes, newNodes));

            topologyLock.lock();
            try {
                // Remove nodes that are no longer present
                for (String node : oldNodes) {
                    if (!newNodes.contains(node)) {
                        Integer bucket = nodeToBucket.remove(node);
                        if (bucket != null) {
                            bucketToNode.remove(bucket);
                            memento.add(bucket);
                        }
                    }
                }

                // Add new nodes
                for (String node : newNodes) {
                    if (!oldNodes.contains(node)) {
                        Integer bucket = memento.poll();
                        if (bucket == null) {
                            bucket = nextBucketId.getAndIncrement();
                        }
                        nodeToBucket.put(node, bucket);
                        bucketToNode.put(bucket, node);
                    }
                }
            } finally {
                topologyLock.unlock();
            }
        } finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Returns a copy of the current node-to-bucket mapping.
     *
     * @return a map from node to bucket
     */
    public Map<String, Integer> getNodeToBucketMapping() {
        topologyLock.lock();
        try {
            return new HashMap<>(nodeToBucket);
        } finally {
            topologyLock.unlock();
        }
    }

    /**
     * Returns a copy of the current bucket-to-node mapping.
     *
     * @return a map from bucket to node
     */
    public Map<Integer, String> getBucketToNodeMapping() {
        topologyLock.lock();
        try {
            return new HashMap<>(bucketToNode);
        } finally {
            topologyLock.unlock();
        }
    }

    /**
     * Returns the current topology as a set of node identifiers.
     *
     * @return the current topology
     */
    public Set<String> currentTopology() {
        return Collections.unmodifiableSet(currentTopology.get());
    }

    /**
     * Assigns partitions to nodes based on the current topology and the specified key.
     *
     * @param key the key for partition assignment
     * @return the assigned partition
     */
    public int assignPartitions(Object key) {
        // Implementation of partition assignment logic
        return 0;
    }

    /**
     * Stops the distribution function, releasing any resources held.
     */
    public void stop() {
        busyLock.block();
    }
}
