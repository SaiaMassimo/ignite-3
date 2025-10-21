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

import static java.util.Collections.emptyList;
import static java.util.Objects.nonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.ignite.internal.lang.IgniteInternalException;
import org.apache.ignite.internal.lang.NodeStoppingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test for ThreadSafeMementoDistributionFunction.
 */
public class ThreadSafeMementoDistributionFunctionTest {
    /** Distribution deviation ratio. */
    private static final double DISTRIBUTION_DEVIATION_RATIO = 0.2;

    private ThreadSafeMementoDistributionFunction distributionFunction;
    private boolean skipTearDown = false;

    @BeforeEach
    public void setUp() {
        distributionFunction = new ThreadSafeMementoDistributionFunction(1);
        skipTearDown = false;
    }

    @AfterEach
    public void tearDown() {
        if (distributionFunction != null && !skipTearDown) {
            distributionFunction.stop();
        }
    }

    @Test
    public void testPartitionDistribution() {
        int nodeCount = 50;
        int parts = 10_000;
        int replicas = 4;

        List<String> nodes = prepareNetworkTopology(nodeCount);

        assertTrue(parts > nodeCount, "Partitions should be more than nodes");

        int ideal = (parts * replicas) / nodeCount;

        // Update topology first
        distributionFunction.updateTopology(nodes);

        List<Set<Assignment>> assignment = distributionFunction.assignPartitions(
                nodes,
                emptyList(),
                parts,
                replicas,
                3
        );

        HashMap<String, ArrayList<Integer>> assignmentByNode = new HashMap<>(nodeCount);

        int part = 0;
        for (Set<Assignment> partNodes : assignment) {
            for (Assignment node : partNodes) {
                ArrayList<Integer> nodeParts = assignmentByNode.computeIfAbsent(node.consistentId(), k -> new ArrayList<>());
                nodeParts.add(part);
            }
            part++;
        }

        for (String node : nodes) {
            ArrayList<Integer> nodeParts = assignmentByNode.get(node);

            assertNotNull(nodeParts);

            assertTrue(nodeParts.size() > ideal * (1 - DISTRIBUTION_DEVIATION_RATIO)
                            && nodeParts.size() < ideal * (1 + DISTRIBUTION_DEVIATION_RATIO),
                    "Partition distribution is too far from ideal [node=" + node
                            + ", size=" + nodeParts.size()
                            + ", idealSize=" + ideal
                            + ", parts=" + compact(nodeParts) + ']');
        }
    }

    @Test
    public void testAllPartitionsDistribution() {
        int nodeCount = 50;
        int parts = 10_000;
        int replicas = DistributionAlgorithm.ALL_REPLICAS;
        int consensusGroupSize = 4;

        List<String> nodes = prepareNetworkTopology(nodeCount);

        assertTrue(parts > nodeCount, "Partitions should be more than nodes");

        // Update topology first
        distributionFunction.updateTopology(nodes);

        List<Set<Assignment>> assignment = distributionFunction.assignPartitions(
                nodes,
                emptyList(),
                parts,
                replicas,
                consensusGroupSize
        );

        assertEquals(parts, assignment.size());

        assignment.forEach(a -> {
            assertEquals(nodeCount, a.size());
            assertEquals(consensusGroupSize, a.stream().filter(Assignment::isPeer).count());
        });
    }

    @ParameterizedTest
    @MethodSource("distributionWithLearnersArguments")
    public void testDistributionWithLearners(int nodeCount, int partitions, int replicas, int consensusReplicas) {
        List<String> nodes = prepareNetworkTopology(nodeCount);

        // Update topology first
        distributionFunction.updateTopology(nodes);

        List<Set<Assignment>> assignments = distributionFunction
                .assignPartitions(nodes, emptyList(), partitions, replicas, consensusReplicas);

        for (int p = 0; p < partitions; p++) {
            Set<Assignment> a = assignments.get(p);
            assertEquals(replicas, a.size());
            assertEquals(consensusReplicas, a.stream().filter(Assignment::isPeer).count());
        }

        List<Set<Assignment>> allAssignments = distributionFunction
                .assignPartitions(nodes, emptyList(), 1, replicas, consensusReplicas);

        Set<Assignment> partitionAssignments = allAssignments.get(0);

        assertEquals(replicas, partitionAssignments.size());
        assertEquals(consensusReplicas, partitionAssignments.stream().filter(Assignment::isPeer).count());
    }

    private static Stream<Arguments> distributionWithLearnersArguments() {
        List<Arguments> arg = new ArrayList<>();

        for (Integer nodes : List.of(1, 2, 3, 4, 5, 7, 10, 20, 50, 100)) {
            for (Integer partitions : List.of(1, 10, 25, 50, 100)) {
                for (Integer replicas : List.of(1, 2, 3, 4, 5, 7, 10, 50, 100)) {
                    for (Integer consensusReplicas : List.of(1, 2, 3, 5, 7, 100)) {
                        if (replicas <= nodes && consensusReplicas <= replicas) {
                            arg.add(Arguments.of(nodes, partitions, replicas, consensusReplicas));
                        }
                    }
                }
            }
        }

        return arg.stream();
    }

    @Test
    public void testConsensusGroupSizeValidation() {
        List<String> nodes = prepareNetworkTopology(3);

        assertThrows(AssertionError.class, () -> distributionFunction.assignPartitions(nodes, emptyList(), 1, 3, 4));
    }

    @Test
    public void testTopologyUpdate() {
        List<String> initialNodes = List.of("node1", "node2", "node3");
        List<String> updatedNodes = List.of("node1", "node2", "node3", "node4");

        // Initial topology
        distributionFunction.updateTopology(initialNodes);
        assertEquals(3, distributionFunction.getCurrentTopology().size());

        // Update topology
        distributionFunction.updateTopology(updatedNodes);
        assertEquals(4, distributionFunction.getCurrentTopology().size());
        assertTrue(distributionFunction.getCurrentTopology().contains("node4"));

        // Verify mappings
        Map<String, Integer> nodeToBucket = distributionFunction.getNodeToBucketMapping();
        Map<Integer, String> bucketToNode = distributionFunction.getBucketToNodeMapping();

        assertEquals(4, nodeToBucket.size());
        assertEquals(4, bucketToNode.size());

        // Verify consistency
        for (Map.Entry<String, Integer> entry : nodeToBucket.entrySet()) {
            assertEquals(entry.getKey(), bucketToNode.get(entry.getValue()));
        }
    }

    @Test
    public void testTopologyRemoval() {
        List<String> initialNodes = List.of("node1", "node2", "node3", "node4");
        List<String> updatedNodes = List.of("node1", "node3");

        // Initial topology
        distributionFunction.updateTopology(initialNodes);
        assertEquals(4, distributionFunction.getCurrentTopology().size());

        // Remove nodes
        distributionFunction.updateTopology(updatedNodes);
        assertEquals(2, distributionFunction.getCurrentTopology().size());
        assertTrue(distributionFunction.getCurrentTopology().contains("node1"));
        assertTrue(distributionFunction.getCurrentTopology().contains("node3"));
        assertTrue(!distributionFunction.getCurrentTopology().contains("node2"));
        assertTrue(!distributionFunction.getCurrentTopology().contains("node4"));

        // Verify mappings
        Map<String, Integer> nodeToBucket = distributionFunction.getNodeToBucketMapping();
        Map<Integer, String> bucketToNode = distributionFunction.getBucketToNodeMapping();

        assertEquals(2, nodeToBucket.size());
        assertEquals(2, bucketToNode.size());
    }

    @Test
    public void testConcurrentTopologyUpdates() throws InterruptedException {
        skipTearDown = true; // Gestione manuale dello stop
        
        int numThreads = 10;
        int updatesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < updatesPerThread; j++) {
                        List<String> nodes = List.of(
                                "node" + (threadId * 10 + j % 10),
                                "node" + ((threadId + 1) * 10 + j % 10),
                                "node" + ((threadId + 2) * 10 + j % 10)
                        );
                        distributionFunction.updateTopology(nodes);
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Test timed out");
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor did not terminate");
        
        assertEquals(numThreads * updatesPerThread, successCount.get());
        
        distributionFunction.stop();
    }

    @Test
    public void testConcurrentAssignPartitions() throws InterruptedException {
        skipTearDown = true; // Gestione manuale dello stop
        
        List<String> nodes = prepareNetworkTopology(10);
        distributionFunction.updateTopology(nodes);

        int numThreads = 20;
        int assignmentsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        ConcurrentHashMap<String, Integer> results = new ConcurrentHashMap<>();

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < assignmentsPerThread; j++) {
                        List<Set<Assignment>> assignments = distributionFunction.assignPartitions(
                                nodes, emptyList(), 100, 3, 2
                        );

                        // Verify assignments
                        assertEquals(100, assignments.size());
                        for (Set<Assignment> assignment : assignments) {
                            assertEquals(3, assignment.size());
                            assertEquals(2, assignment.stream().filter(Assignment::isPeer).count());
                        }

                        successCount.incrementAndGet();
                        results.put("thread-" + Thread.currentThread().getId(), j);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Test timed out");
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor did not terminate");
        
        assertEquals(numThreads * assignmentsPerThread, successCount.get());
        
        distributionFunction.stop();
    }

    @Test
    public void testConcurrentTopologyUpdateAndAssignPartitions() throws InterruptedException {
        skipTearDown = true; // Gestione manuale dello stop
        
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        // Start with initial topology
        List<String> initialNodes = prepareNetworkTopology(5);
        distributionFunction.updateTopology(initialNodes);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 50; j++) {
                        if (threadId % 2 == 0) {
                            // Update topology
                            List<String> nodes = prepareNetworkTopology(5 + (j % 3));
                            distributionFunction.updateTopology(nodes);
                        } else {
                            // Assign partitions
                            List<Set<Assignment>> assignments = distributionFunction.assignPartitions(
                                    initialNodes, emptyList(), 50, 3, 2
                            );
                            assertEquals(50, assignments.size());
                        }
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Test timed out");
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor did not terminate");
        
        assertEquals(numThreads * 50, successCount.get());
        
        distributionFunction.stop();
    }

    @Test
    public void testStopBehavior() {
        List<String> nodes = prepareNetworkTopology(5);
        distributionFunction.updateTopology(nodes);

        // Stop the component
        distributionFunction.stop();

        // Operations should fail after stop
        // inBusyLock lancia IgniteInternalException con causa NodeStoppingException
        IgniteInternalException exception = assertThrows(IgniteInternalException.class, () -> {
            distributionFunction.assignPartitions(nodes, emptyList(), 10, 3, 2);
        });
        
        assertTrue(exception.getCause() instanceof NodeStoppingException,
                "Expected NodeStoppingException as cause, but got: " + exception.getCause());

        // Topology updates should be ignored after stop (inBusyLock restituisce semplicemente)
        distributionFunction.updateTopology(List.of("node1", "node2"));
        // Should not throw, but should be ignored
    }

    @Test
    public void testPerformanceComparison() {
        List<String> nodes = prepareNetworkTopology(100);
        distributionFunction.updateTopology(nodes);

        int partitions = 1000;
        int replicas = 3;
        int consensusGroupSize = 2;

        // Warm up
        for (int i = 0; i < 10; i++) {
            distributionFunction.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
        }

        // Measure performance
        long startTime = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            List<Set<Assignment>> assignments = distributionFunction.assignPartitions(
                    nodes, emptyList(), partitions, replicas, consensusGroupSize
            );
            assertEquals(partitions, assignments.size());
        }
        long endTime = System.nanoTime();

        long totalTime = endTime - startTime;
        double avgTimePerCall = totalTime / 100.0 / 1_000_000.0; // Convert to milliseconds

        System.out.println("Average time per assignPartitions call: " + avgTimePerCall + " ms");

        // Performance should be reasonable (less than 10ms per call for 1000 partitions)
        assertTrue(avgTimePerCall < 10.0, "Performance is too slow: " + avgTimePerCall + " ms per call");
    }

    @Test
    public void testMappingConsistency() {
        List<String> nodes = List.of("node1", "node2", "node3", "node4", "node5");
        distributionFunction.updateTopology(nodes);

        Map<String, Integer> nodeToBucket = distributionFunction.getNodeToBucketMapping();
        Map<Integer, String> bucketToNode = distributionFunction.getBucketToNodeMapping();

        // Verify bidirectional mapping consistency
        assertEquals(nodeToBucket.size(), bucketToNode.size());
        assertEquals(nodes.size(), nodeToBucket.size());

        for (Map.Entry<String, Integer> entry : nodeToBucket.entrySet()) {
            String node = entry.getKey();
            Integer bucket = entry.getValue();

            assertEquals(node, bucketToNode.get(bucket));
            assertTrue(nodes.contains(node));
        }

        for (Map.Entry<Integer, String> entry : bucketToNode.entrySet()) {
            Integer bucket = entry.getKey();
            String node = entry.getValue();

            assertEquals(bucket, nodeToBucket.get(node));
            assertTrue(nodes.contains(node));
        }
    }

    private static List<String> prepareNetworkTopology(int nodes) {
        return IntStream.range(0, nodes)
                .mapToObj(i -> "Node " + i)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Returns sorted and compacted string representation of given {@code col}. Two nearby numbers with difference at most 1 are compacted
     * to one continuous segment. E.g. collection of [1, 2, 3, 5, 6, 7, 10] will be compacted to [1-3, 5-7, 10].
     *
     * @param col Collection of integers.
     * @return Compacted string representation of given collections.
     */
    private static String compact(Collection<Integer> col) {
        return compact(col, i -> i + 1);
    }

    /**
     * Returns sorted and compacted string representation of given {@code col}. Two nearby numbers are compacted to one continuous segment.
     * E.g. collection of [1, 2, 3, 5, 6, 7, 10] with {@code nextValFun = i -> i + 1} will be compacted to [1-3, 5-7, 10].
     *
     * @param col        Collection of numbers.
     * @param nextValFun Function to get nearby number.
     * @return Compacted string representation of given collections.
     */
    private static <T extends Number & Comparable<? super T>> String compact(
            Collection<T> col,
            Function<T, T> nextValFun
    ) {
        assert nonNull(col);
        assert nonNull(nextValFun);

        if (col.isEmpty()) {
            return "[]";
        }

        StringBuilder sb = new StringBuilder();
        sb.append('[');

        List<T> l = new ArrayList<>(col);
        Collections.sort(l);

        T left = l.get(0);
        T right = left;
        for (int i = 1; i < l.size(); i++) {
            T val = l.get(i);

            if (right.compareTo(val) == 0 || nextValFun.apply(right).compareTo(val) == 0) {
                right = val;
                continue;
            }

            if (left.compareTo(right) == 0) {
                sb.append(left);
            } else {
                sb.append(left).append('-').append(right);
            }

            sb.append(',').append(' ');

            left = right = val;
        }

        if (left.compareTo(right) == 0) {
            sb.append(left);
        } else {
            sb.append(left).append('-').append(right);
        }

        sb.append(']');

        return sb.toString();
    }

    @Test
    public void testUpdateTopology() {
        List<String> initialNodes = List.of("node1", "node2", "node3");
        
        // Update topology via dedicated method
        distributionFunction.updateTopology(initialNodes);
        
        // Verify mappings were created
        assertEquals(3, distributionFunction.getNodeToBucketMapping().size());
        assertEquals(3, distributionFunction.getBucketToNodeMapping().size());
        
        // Add more nodes
        List<String> expandedNodes = List.of("node1", "node2", "node3", "node4", "node5");
        distributionFunction.updateTopology(expandedNodes);
        
        assertEquals(5, distributionFunction.getNodeToBucketMapping().size());
        assertTrue(distributionFunction.getNodeToBucketMapping().containsKey("node4"));
        assertTrue(distributionFunction.getNodeToBucketMapping().containsKey("node5"));
        
        // Remove nodes
        List<String> reducedNodes = List.of("node1", "node3", "node5");
        distributionFunction.updateTopology(reducedNodes);
        
        assertEquals(3, distributionFunction.getNodeToBucketMapping().size());
        assertTrue(distributionFunction.getNodeToBucketMapping().containsKey("node1"));
        assertTrue(distributionFunction.getNodeToBucketMapping().containsKey("node3"));
        assertTrue(distributionFunction.getNodeToBucketMapping().containsKey("node5"));
        assertTrue(!distributionFunction.getNodeToBucketMapping().containsKey("node2"));
        assertTrue(!distributionFunction.getNodeToBucketMapping().containsKey("node4"));
    }

    @Test
    public void testUpdateTopologyThenAssignPartitions() {
        List<String> nodes = prepareNetworkTopology(10);
        
        // First: update topology (simulating topology event)
        distributionFunction.updateTopology(nodes);
        
        // Then: assign partitions (should use pre-computed mappings)
        List<Set<Assignment>> assignments = distributionFunction.assignPartitions(
                nodes, emptyList(), 100, 3, 2
        );
        
        assertEquals(100, assignments.size());
        for (Set<Assignment> assignment : assignments) {
            assertEquals(3, assignment.size());
            assertEquals(2, assignment.stream().filter(Assignment::isPeer).count());
        }
    }
}