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

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Stateless distribution utils that produces helper methods for an assignments distribution calculation.
 * 
 * <p>This class supports both static algorithm usage (for backward compatibility) and 
 * pre-warmed algorithm usage (for performance optimization when available).
 */
public class PartitionDistributionUtils {

    private static final DistributionAlgorithm STATIC_DISTRIBUTION_ALGORITHM = MementoDistributionFunction.getInstance(1);
    
    /** Thread-local reference to the pre-warmed distribution function. */
    private static final ThreadLocal<ThreadSafeMementoDistributionFunction> PRE_WARMED_ALGORITHM = new ThreadLocal<>();

    /**
     * Sets the pre-warmed distribution function for the current thread.
     * This allows components to use the optimized pre-warmed algorithm.
     * 
     * @param preWarmedFunction The pre-warmed distribution function
     */
    public static void setPreWarmedAlgorithm(ThreadSafeMementoDistributionFunction preWarmedFunction) {
        PRE_WARMED_ALGORITHM.set(preWarmedFunction);
    }
    
    /**
     * Clears the pre-warmed distribution function for the current thread.
     */
    public static void clearPreWarmedAlgorithm() {
        PRE_WARMED_ALGORITHM.remove();
    }
    
    /**
     * Gets the appropriate distribution algorithm for the current thread.
     * Uses pre-warmed algorithm if available, otherwise falls back to static algorithm.
     * 
     * @return The distribution algorithm to use
     */
    private static DistributionAlgorithm getDistributionAlgorithm() {
        ThreadSafeMementoDistributionFunction preWarmed = PRE_WARMED_ALGORITHM.get();
        return preWarmed != null ? preWarmed : STATIC_DISTRIBUTION_ALGORITHM;
    }

    /**
     * Calculates assignments distribution.
     *
     * @param dataNodes Data nodes.
     * @param partitions Partitions count.
     * @param replicas Replicas count.
     * @return List assignments by partition.
     */
    // TODO https://issues.apache.org/jira/browse/IGNITE-24391 pass the consensus group size as the parameter here
    public static List<Set<Assignment>> calculateAssignments(
            Collection<String> dataNodes,
            int partitions,
            int replicas
    ) {
        return getDistributionAlgorithm().assignPartitions(
                dataNodes,
                emptyList(),
                partitions,
                replicas,
                replicas
        );
    }

    /**
     * Calculates assignments distribution for a single partition.
     *
     * @param dataNodes Data nodes.
     * @param partitionId Partition id.
     * @param partitions Partitions count.
     * @param replicas Replicas count.
     * @return Set of assignments.
     */
    // TODO https://issues.apache.org/jira/browse/IGNITE-24391 pass the consensus group size as the parameter here
    public static Set<Assignment> calculateAssignmentForPartition(
            Collection<String> dataNodes,
            int partitionId,
            int partitions,
            int replicas
    ) {
        List<Set<Assignment>> assignments = getDistributionAlgorithm().assignPartitions(dataNodes, emptyList(), partitions, replicas, replicas);

        return assignments.get(partitionId);
    }

}
