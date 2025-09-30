package org.apache.ignite.internal.partitiondistribution.memento;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.apache.ignite.internal.partitiondistribution.Assignment;
import org.apache.ignite.internal.partitiondistribution.DistributionAlgorithm;

public class BinomialDistributionFunction implements DistributionAlgorithm {
    private final Function<String, Long> hashFunction;

    public BinomialDistributionFunction(Function<String, Long> hashFunction) {
        this.hashFunction = hashFunction;
    }

    @Override
    public List<Set<Assignment>> assignPartitions(
            Collection<String> nodes,
            List<List<String>> currentDistribution,
            int partitions,
            int replicaFactor,
            int consensusGroupSize
    ) {
        List<String> sortedNodes = new ArrayList<>(nodes);
        Collections.sort(sortedNodes);
        BinomialEngine engine = new BinomialEngine(sortedNodes.size());

        List<Set<Assignment>> result = new ArrayList<>(partitions);

        for (int i = 0; i < partitions; i++) {
            Set<Assignment> partitionAssignments = new LinkedHashSet<>();
            int attempt = 0;

            while (partitionAssignments.size() < replicaFactor && attempt < sortedNodes.size() * 2) {
                String key = "partition-" + i + "-replica-" + attempt;
                int bucket = engine.getBucket(key);
                String nodeId = sortedNodes.get(bucket);

                boolean isPeer = partitionAssignments.size() < consensusGroupSize;
                Assignment assignment = isPeer
                        ? Assignment.forPeer(nodeId)
                        : Assignment.forLearner(nodeId);

                partitionAssignments.add(assignment);
                attempt++;
            }

            result.add(partitionAssignments);
        }

        return result;
    }
}