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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Benchmark test to demonstrate the benefits of pre-warming topology updates.
 */
public class PreWarmingBenchmarkTest {
    
    @Test
    public void testPreWarmingPerformanceBenefit() {
        int nodeCount = 100;
        int partitions = 1000;
        int replicas = 3;
        int iterations = 100;
        
        List<String> nodes = prepareNetworkTopology(nodeCount);
        
        // ============================================
        // Scenario 1: WITHOUT Pre-warming (inline update)
        // ============================================
        ThreadSafeMementoDistributionFunction withoutPreWarming = 
            new ThreadSafeMementoDistributionFunction(1);
        
        long startWithout = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            // Simula cambio topologia (aggiunge/rimuove un nodo)
            List<String> modifiedNodes = new ArrayList<>(nodes);
            if (i % 2 == 0) {
                modifiedNodes.add("TempNode" + i);
            } else {
                modifiedNodes.remove(modifiedNodes.size() - 1);
            }
            
            // assignPartitions deve aggiornare topology INLINE
            List<Set<Assignment>> assignments = withoutPreWarming.assignPartitions(
                modifiedNodes, emptyList(), partitions, replicas, replicas
            );
        }
        long endWithout = System.nanoTime();
        double timeWithoutMs = (endWithout - startWithout) / 1_000_000.0;
        
        withoutPreWarming.stop();
        
        // ============================================
        // Scenario 2: WITH Pre-warming (topology pre-computed)
        // ============================================
        ThreadSafeMementoDistributionFunction withPreWarming = 
            new ThreadSafeMementoDistributionFunction(1);
        
        long startWith = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            List<String> modifiedNodes = new ArrayList<>(nodes);
            if (i % 2 == 0) {
                modifiedNodes.add("TempNode" + i);
            } else {
                modifiedNodes.remove(modifiedNodes.size() - 1);
            }
            
            // PRE-WARMING: aggiorna topology PRIMA
            withPreWarming.updateTopology(modifiedNodes);
            
            // assignPartitions trova topology già aggiornata!
            List<Set<Assignment>> assignments = withPreWarming.assignPartitions(
                modifiedNodes, emptyList(), partitions, replicas, replicas
            );
        }
        long endWith = System.nanoTime();
        double timeWithMs = (endWith - startWith) / 1_000_000.0;
        
        withPreWarming.stop();
        
        // ============================================
        // Results
        // ============================================
        System.out.println("\n========== PRE-WARMING BENCHMARK RESULTS ==========");
        System.out.println("Nodes: " + nodeCount + ", Partitions: " + partitions + ", Iterations: " + iterations);
        System.out.println();
        System.out.println("WITHOUT Pre-warming (inline update):  " + String.format("%.2f", timeWithoutMs) + " ms");
        System.out.println("WITH Pre-warming (topology cached):   " + String.format("%.2f", timeWithMs) + " ms");
        System.out.println();
        
        double improvement = ((timeWithoutMs - timeWithMs) / timeWithoutMs) * 100;
        System.out.println("Performance improvement: " + String.format("%.1f", improvement) + "%");
        System.out.println("===================================================\n");
        
        // Pre-warming dovrebbe essere più veloce o uguale
        assertTrue(timeWithMs <= timeWithoutMs, 
            "Pre-warming should be faster or equal. Without: " + timeWithoutMs + "ms, With: " + timeWithMs + "ms");
    }
    
    @Test
    public void testPreWarmingWithStableTopology() {
        int nodeCount = 100;
        int partitions = 1000;
        int replicas = 3;
        int iterations = 100;
        
        List<String> nodes = prepareNetworkTopology(nodeCount);
        
        // ============================================
        // Scenario: Topologia STABILE (nessun cambio)
        // ============================================
        ThreadSafeMementoDistributionFunction withPreWarming = 
            new ThreadSafeMementoDistributionFunction(1);
        
        // Pre-warm ONCE
        withPreWarming.updateTopology(nodes);
        
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            // Topologia NON cambia
            List<Set<Assignment>> assignments = withPreWarming.assignPartitions(
                nodes, emptyList(), partitions, replicas, replicas
            );
        }
        long end = System.nanoTime();
        double timeMs = (end - start) / 1_000_000.0;
        
        withPreWarming.stop();
        
        double avgPerCall = timeMs / iterations;
        
        System.out.println("\n========== STABLE TOPOLOGY BENCHMARK ==========");
        System.out.println("Total time: " + String.format("%.2f", timeMs) + " ms");
        System.out.println("Avg per call: " + String.format("%.2f", avgPerCall) + " ms");
        System.out.println("===============================================\n");
        
        // Con topologia stabile, dovrebbe essere molto veloce
        assertTrue(avgPerCall < 10.0, 
            "Average time should be < 10ms per call with stable topology, got: " + avgPerCall + "ms");
    }
    
    private static List<String> prepareNetworkTopology(int nodes) {
        return IntStream.range(0, nodes)
                .mapToObj(i -> "Node " + i)
                .collect(Collectors.toUnmodifiableList());
    }
}
