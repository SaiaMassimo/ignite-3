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
import static org.apache.ignite.internal.util.IgniteUtils.inBusyLock;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.codec.digest.MurmurHash3;
import org.apache.ignite.internal.partitiondistribution.memento.BinomialEngine;
import org.apache.ignite.internal.partitiondistribution.memento.Memento;
import org.apache.ignite.internal.util.IgniteSpinBusyLock;

/**
 * Thread-safe implementation of MementoDistributionFunction using ConcurrentHashMap and IgniteSpinBusyLock.
 */
public class ThreadSafeMementoDistributionFunction implements DistributionAlgorithm {

    /** The memory of the removed nodes, also addressed as replacement set. */
    private final Memento memento;

    private final BinomialEngine binomialEngine;

    /** The last removed bucket. */
    private volatile int lastRemoved;

    /** Thread-safe mapping from node to bucket. */
    private final ConcurrentHashMap<String, Integer> nodeToBucket = new ConcurrentHashMap<>();

    /** Thread-safe mapping from bucket to node. */
    private final ConcurrentHashMap<Integer, String> bucketToNode = new ConcurrentHashMap<>();

    /** Busy lock for synchronization. */
    private final IgniteSpinBusyLock busyLock = new IgniteSpinBusyLock();

    public ThreadSafeMementoDistributionFunction(int size) {
        this.lastRemoved = size;
        this.binomialEngine = new BinomialEngine(size);
        this.memento = new Memento();
    }

    /**
     * Updates the topology mapping when topology changes occur.
     * This method should be called from topology event listeners.
     * It's thread-safe and optimized for performance.
     *
     * @param nodes the new set of nodes
     */
    public void updateTopology(Collection<String> nodes) {
        if (!busyLock.enterBusy()) {
            return; // Component is stopping, ignore the update
        }
        
        try {
            synchronized (this) {
                Set<String> currentNodes = new HashSet<>(nodeToBucket.keySet());
                Set<String> newNodes = new HashSet<>(nodes);

                // Remove nodes that are no longer present
                for (String node : currentNodes) {
                    if (!newNodes.contains(node)) {
                        Integer bucket = nodeToBucket.remove(node);
                        if (bucket != null) {
                            bucketToNode.remove(bucket);
                            removeBucket(bucket);
                        }
                    }
                }

                // Add new nodes
                for (String node : newNodes) {
                    if (!nodeToBucket.containsKey(node)) {
                        int bucket = addBucket();
                        nodeToBucket.put(node, bucket);
                        bucketToNode.put(bucket, node);
                    }
                }
            }
        } finally {
            busyLock.leaveBusy();
        }
    }

    public synchronized int getBucket(String key) {
        int b = binomialEngine.getBucket(key);

        int replacer = memento.replacer(b);
        while (replacer >= 0) {
            final long h = Math.abs(hash(key, b));
            b = (int) (h % replacer);

            int r = memento.replacer(b);
            while (r >= replacer) {
                b = r;
                r = memento.replacer(b);
            }

            replacer = r;
        }
        return b;
    }

    public synchronized int addBucket() {
        final int bucket = lastRemoved;
        this.lastRemoved = memento.restore(bucket);

        if (bArraySize() <= bucket) {
            binomialEngine.addBucket();
        }
        return bucket;
    }

    public synchronized int removeBucket(int bucket) {
        if (memento.isEmpty() && bucket == binomialEngine.size() - 1) {
            binomialEngine.removeBucket(bucket);
            lastRemoved = bucket;
            return bucket;
        }

        this.lastRemoved = memento.remember(
                bucket,
                size() - 1,
                lastRemoved
        );
        return bucket;
    }
    //deve avere un replacment completo temp piu originale, clonare originale come partenza
    //test coerente rimozione, bilanciato, con minimal distuption e monotonicity

    private synchronized int getBucketWithTempMemento(String key, Memento tempMemento) {
        int b = binomialEngine.getBucket(key);

        int tempReplacer = tempMemento.replacer(b);
        while (tempReplacer >= 0) {
            final long h = Math.abs(hash(key, b));
            b = (int) (h % tempReplacer);

            int r = tempMemento.replacer(b);
            while (r >= tempReplacer) {
                b = r;
                r = tempMemento.replacer(b);
            }
            tempReplacer = r;
        }

        return b;
    }

    @Override
    public List<Set<Assignment>> assignPartitions(Collection<String> nodes, List<List<String>> currentDistribution, int partitions,
            int replicaFactor, int consensusGroupSize) {
        assert partitions <= MAX_PARTITIONS_COUNT : "partitions <= " + MAX_PARTITIONS_COUNT;
        assert partitions > 0 : "parts > 0";
        assert replicaFactor > 0 : "replicas > 0";
        assert consensusGroupSize <= replicaFactor : "consensusGroupSize should be less or equal to replicaFactor";

        return inBusyLock(busyLock, () -> {
            int effectiveReplicas = replicaFactor == ALL_REPLICAS ? nodes.size() : Math.min(replicaFactor, nodes.size());

            // NOTE: Topology update is now done separately via updateTopology()
            // This is called from topology event listeners for better performance
            // However, we still support inline updates for backward compatibility
            Set<String> currentNodes = new HashSet<>(nodeToBucket.keySet());
            Set<String> newNodes = new HashSet<>(nodes);
            
            if (!currentNodes.equals(newNodes)) {
                // Topology has changed, update it inline
                // In production, this should be done via updateTopology() from event listeners
                updateTopologyInternal(nodes);
            }

            List<Set<Assignment>> result = new ArrayList<>(partitions);
            List<String> sortedNodes = new ArrayList<>(nodeToBucket.keySet());
            Collections.sort(sortedNodes);

            for (int part = 0; part < partitions; part++) {
                Set<Assignment> assignments = new LinkedHashSet<>();

                String baseKey = String.valueOf(part);

                if (effectiveReplicas == 1) {
                    int bucket = getBucket(baseKey);

                
                    String node = bucketToNode.get(bucket);
                    assignments.add(Assignment.forPeer(node));
                    
                    
                } else {
                    Memento tempMemento = memento.clone();
                    int currentSize = size();
                    int lastTempRemoved = this.lastRemoved;

                    
                    int bucket = getBucket(baseKey);

                    if (bucketToNode.containsKey(bucket)) {
                        String node = bucketToNode.get(bucket);
                        assignments.add(Assignment.forPeer(node));

                        lastTempRemoved = tempMemento.remember(bucket, currentSize - 1, lastTempRemoved);
                        currentSize--;
                    }
                    

                    while (assignments.size() < effectiveReplicas) {
                        int bucket = getBucketWithTempMemento(baseKey, tempMemento);

                        if (bucketToNode.containsKey(bucket)) {
                            String node = bucketToNode.get(bucket);
                            assignments.add(assignments.size() < consensusGroupSize
                                    ? Assignment.forPeer(node)
                                    : Assignment.forLearner(node));

                            lastTempRemoved = tempMemento.remember(bucket, currentSize - 1, lastTempRemoved);
                            currentSize--;
                        }
                    }
                }

                result.add(assignments);
            }

            return result;
        });
    }

    public int bArraySize() {
        return binomialEngine.size();
    }

    public int size() {
        return binomialEngine.size() - memento.size();
    }

    private long hash(String key, int seed) {
        final byte[] bytes = key.getBytes(StandardCharsets.UTF_8);

        if (seed == 0) {
            return Math.abs(MurmurHash3.hash32x86(bytes));
        }

        return Math.abs(MurmurHash3.hash32x86(
                ByteBuffer
                        .allocate(bytes.length + 4)
                        .put(bytes)
                        .putInt(seed)
                        .array()
        ));
    }

    /**
     * Internal method to update topology (called from within busy lock).
     * This avoids nested busy lock calls.
     */
    private void updateTopologyInternal(Collection<String> nodes) {
        synchronized (this) {
            Set<String> currentNodes = new HashSet<>(nodeToBucket.keySet());
            Set<String> newNodes = new HashSet<>(nodes);

            // Remove nodes that are no longer present
            for (String node : currentNodes) {
                if (!newNodes.contains(node)) {
                    Integer bucket = nodeToBucket.remove(node);
                    if (bucket != null) {
                        bucketToNode.remove(bucket);
                        removeBucket(bucket);
                    }
                }
            }

            // Add new nodes
            for (String node : newNodes) {
                if (!nodeToBucket.containsKey(node)) {
                    int bucket = addBucket();
                    nodeToBucket.put(node, bucket);
                    bucketToNode.put(bucket, node);
                }
            }
        }
    }

    /**
     * Stops the component by blocking the busy lock.
     */
    public void stop() {
        busyLock.block();
    }

    /**
     * Gets the current node-to-bucket mapping.
     *
     * @return a copy of the node-to-bucket mapping
     */
    public Map<String, Integer> getNodeToBucketMapping() {
        return new ConcurrentHashMap<>(nodeToBucket);
    }

    /**
     * Gets the current bucket-to-node mapping.
     *
     * @return a copy of the bucket-to-node mapping
     */
    public Map<Integer, String> getBucketToNodeMapping() {
        return new ConcurrentHashMap<>(bucketToNode);
    }

    /**
     * Gets the current topology as a set of node names.
     *
     * @return a set containing all current node names
     */
    public Set<String> getCurrentTopology() {
        return new HashSet<>(nodeToBucket.keySet());
    }
}