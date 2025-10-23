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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link PartitionDistributionUtils} with pre-warmed algorithm support.
 */
public class PartitionDistributionUtilsTest {
    private ThreadSafeMementoDistributionFunction preWarmedFunction;

    @BeforeEach
    public void setUp() {
        preWarmedFunction = new ThreadSafeMementoDistributionFunction(1);
    }

    @AfterEach
    public void tearDown() {
        if (preWarmedFunction != null) {
            preWarmedFunction.stop();
        }
        PartitionDistributionUtils.clearPreWarmedAlgorithm();
    }

    @Test
    public void testCalculateAssignmentsWithStaticAlgorithm() {
        List<String> dataNodes = List.of("node1", "node2", "node3");
        int partitions = 5;
        int replicas = 2;

        List<Set<Assignment>> assignments = PartitionDistributionUtils.calculateAssignments(dataNodes, partitions, replicas);

        assertNotNull(assignments);
        assertEquals(partitions, assignments.size());
        
        for (Set<Assignment> partitionAssignments : assignments) {
            assertEquals(replicas, partitionAssignments.size());
        }
    }

    @Test
    public void testCalculateAssignmentsWithPreWarmedAlgorithm() {
        List<String> dataNodes = List.of("node1", "node2", "node3");
        int partitions = 5;
        int replicas = 2;

        // Set pre-warmed algorithm
        PartitionDistributionUtils.setPreWarmedAlgorithm(preWarmedFunction);
        
        // Pre-warm the topology
        preWarmedFunction.updateTopology(dataNodes);

        List<Set<Assignment>> assignments = PartitionDistributionUtils.calculateAssignments(dataNodes, partitions, replicas);

        assertNotNull(assignments);
        assertEquals(partitions, assignments.size());
        
        for (Set<Assignment> partitionAssignments : assignments) {
            assertEquals(replicas, partitionAssignments.size());
        }
    }

    @Test
    public void testCalculateAssignmentForPartition() {
        List<String> dataNodes = List.of("node1", "node2", "node3");
        int partitions = 5;
        int replicas = 2;
        int partitionId = 2;

        Set<Assignment> assignment = PartitionDistributionUtils.calculateAssignmentForPartition(
                dataNodes, partitionId, partitions, replicas);

        assertNotNull(assignment);
        assertEquals(replicas, assignment.size());
    }

    @Test
    public void testThreadLocalIsolation() throws InterruptedException {
        List<String> dataNodes = List.of("node1", "node2", "node3");
        int partitions = 3;
        int replicas = 2;

        CountDownLatch latch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: Use pre-warmed algorithm
        executor.submit(() -> {
            try {
                PartitionDistributionUtils.setPreWarmedAlgorithm(preWarmedFunction);
                preWarmedFunction.updateTopology(dataNodes);
                
                List<Set<Assignment>> assignments1 = PartitionDistributionUtils.calculateAssignments(dataNodes, partitions, replicas);
                assertNotNull(assignments1);
                
                latch.countDown();
            } finally {
                PartitionDistributionUtils.clearPreWarmedAlgorithm();
            }
        });

        // Thread 2: Use static algorithm (no pre-warmed set)
        executor.submit(() -> {
            try {
                List<Set<Assignment>> assignments2 = PartitionDistributionUtils.calculateAssignments(dataNodes, partitions, replicas);
                assertNotNull(assignments2);
                
                latch.countDown();
            } finally {
                PartitionDistributionUtils.clearPreWarmedAlgorithm();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Both threads should complete");
        executor.shutdown();
    }

    @Test
    public void testPreWarmedAlgorithmFallback() {
        List<String> dataNodes = List.of("node1", "node2", "node3");
        int partitions = 3;
        int replicas = 2;

        // Clear any pre-warmed algorithm
        PartitionDistributionUtils.clearPreWarmedAlgorithm();

        // Should fall back to static algorithm
        List<Set<Assignment>> assignments = PartitionDistributionUtils.calculateAssignments(dataNodes, partitions, replicas);

        assertNotNull(assignments);
        assertEquals(partitions, assignments.size());
        
        for (Set<Assignment> partitionAssignments : assignments) {
            assertEquals(replicas, partitionAssignments.size());
        }
    }
}
