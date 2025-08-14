package org.apache.ignite.internal.partitiondistribution;

import static org.apache.ignite.internal.partitiondistribution.RendezvousDistributionFunction.MAX_PARTITIONS_COUNT;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.codec.digest.MurmurHash3;
import org.apache.ignite.internal.partitiondistribution.memento.BinomialEngine;
import org.apache.ignite.internal.partitiondistribution.memento.Memento;

public class MementoDistributionFunction implements DistributionAlgorithm {

    /** Istanza singleton */
    private static MementoDistributionFunction instance;

    /** The memory of the removed nodes, also addressed as replacement set. */
    private final Memento memento;

    private final BinomialEngine binomialEngine;

    /** The last removed bucket. */
    private int lastRemoved;

    private final Map<String, Integer> nodeToBucket = new HashMap<>();
    private final Map<Integer, String> bucketToNode = new HashMap<>();

    // Costruttore privato per il pattern singleton
    private MementoDistributionFunction(int size) {
        this.lastRemoved = size;
        this.binomialEngine = new BinomialEngine(size);
        this.memento = new Memento();
    }

    /**
     * Ottiene l'istanza singleton della MementoDistributionFunction.
     * Se l'istanza non esiste, la crea con la dimensione specificata.
     *
     * @param size dimensione iniziale
     * @return l'istanza singleton
     */
    public static synchronized MementoDistributionFunction getInstance(int size) {
        if (instance == null) {
            instance = new MementoDistributionFunction(size);
        }
        return instance;
    }

    /**
     * Reimposta l'istanza singleton con una nuova dimensione.
     * Utile principalmente per i test.
     *
     * @param size la nuova dimensione
     * @return l'istanza singleton reimpostata
     */
    public static synchronized MementoDistributionFunction reset(int size) {
        instance = new MementoDistributionFunction(size);
        return instance;
    }

    /**
     * Returns the bucket where the given key should be mapped.
     *
     * @param key the key to map
     * @return the related bucket
     */
    public int getBucket( String key )
    {
        int b = binomialEngine.getBucket(key);

        /*
         * We check if the bucket was removed, if not we are done.
         * If the bucket was removed the replacing bucket is >= 0,
         * otherwise it is -1.
         */
        int replacer = memento.replacer( b );
        while( replacer >= 0 )
        {

            /*
             * If the bucket was removed, we must re-hash and find
             * a new bucket in the remaining slots. To know the
             * remaining slots, we look at 'replacer' that also
             * represents the size of the working set when the bucket
             * was removed and get a new bucket in [0,replacer-1].
             */
            final long h = Math.abs( hash(key,b) );
            b = (int)( h % replacer );



            /*
             * If we hit a removed bucket we follow the replacements
             * until we get a working bucket or a bucket in the range
             * [0,replacer-1]
             */
            int r = memento.replacer( b );
            while( r >= replacer )
            {
                b = r;
                r = memento.replacer( b );
            }

            /* Finally we update the entry of the external loop. */
            replacer = r;

        }
        return b;

    }
    public int addBucket() {

        final int bucket = lastRemoved;

        this.lastRemoved = memento.restore( bucket );

        if(bArraySize() <= bucket){
            binomialEngine.addBucket();
        }
        return bucket;
    }
    public int removeBucket(int bucket) {
        if(memento.isEmpty() && bucket == binomialEngine.size() - 1) {
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
    @Override
    public List<Set<Assignment>> assignPartitions(Collection<String> nodes, List<List<String>> currentDistribution, int partitions,
            int replicaFactor, int consensusGroupSize) {
        assert partitions <= MAX_PARTITIONS_COUNT : "partitions <= " + MAX_PARTITIONS_COUNT;
        assert partitions > 0 : "parts > 0";
        assert replicaFactor > 0 : "replicas > 0";
        assert consensusGroupSize <= replicaFactor : "consensusGroupSize should be less or equal to replicaFactor";

        int effectiveReplicas = replicaFactor == ALL_REPLICAS ? nodes.size() : Math.min(replicaFactor, nodes.size());

        Set<String> currentNodes = new HashSet<>(nodeToBucket.keySet());
        Set<String> newNodes = new HashSet<>(nodes);

        for (String node : currentNodes) {
            if (!newNodes.contains(node)) {
                int bucket = nodeToBucket.remove(node);
                bucketToNode.remove(bucket);
                removeBucket(bucket);
            }
        }
        // Aggiungi nuovi nodi
        for (String node : newNodes) {
            if (!nodeToBucket.containsKey(node)) {
                int bucket = addBucket();
                nodeToBucket.put(node, bucket);
                bucketToNode.put(bucket, node);
            }
        }

        List<Set<Assignment>> result = new ArrayList<>(partitions);
        List<String> sortedNodes = new ArrayList<>(nodeToBucket.keySet());
        Collections.sort(sortedNodes);
        for (int part = 0; part < partitions; part++) {
            Set<Assignment> assignments = new LinkedHashSet<>();
            Set<Integer> usedBuckets = new HashSet<>();
            int attempts = 0;
            int maxAttempts = 10 * effectiveReplicas;

            while (assignments.size() < effectiveReplicas/* && attempts < maxAttempts*/) {
                String key = "partition-" + part + "-" + attempts;
                int bucket = getBucket(key);

                if (!usedBuckets.contains(bucket) && bucketToNode.containsKey(bucket)) {
                    String node = bucketToNode.get(bucket);
                    assignments.add(assignments.size() < consensusGroupSize
                            ? Assignment.forPeer(node)
                            : Assignment.forLearner(node));
                    usedBuckets.add(bucket);
                }

                /*if(bucketToNode.containsKey(bucket)) {
                    String node = bucketToNode.get(bucket);
                    assignments.add(assignments.size() < consensusGroupSize
                            ? Assignment.forPeer(node)
                            : Assignment.forLearner(node));
                }*/
                attempts++;
            }
            System.out.println("Numero tentativi:"+attempts);
            result.add(assignments);
        }

        return result;
    }
    public int bArraySize(){

        return binomialEngine.size();
    }
    public int size()
    {
        return binomialEngine.size() - memento.size();
    }
    /**
     * Performs the hashing of the given string
     * using the diven seed.
     *
     * @param key  the key to hash
     * @param seed the seed to use
     * @return the related hash value
     */
    private long hash( String key, int seed )
    {
        final byte[] bytes = key.getBytes( StandardCharsets.UTF_8 );
        /*
         * If the seed is 0 we don't take it into consideration
         * therefore we return the hash without seed.
         */
        if( seed == 0 )
            return Math.abs( MurmurHash3.hash32x86(bytes) );



        return Math.abs( MurmurHash3.hash32x86(
                ByteBuffer
                        .allocate( bytes.length + 4 )
                        .put( bytes )
                        .putInt( seed )
                        .array()
        ));

    }
}