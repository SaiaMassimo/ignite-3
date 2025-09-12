package org.apache.ignite.internal.partitiondistribution;

import static java.util.Collections.emptyList;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test di performance per confrontare MementoDistributionFunction e RendezvousDistributionFunction.
 */
public class PerformanceTest {
    
    private static final int[] NODE_COUNTS = {10, 50, 100, 200};
    private static final int[] PARTITION_COUNTS = {1000, 10000};
    private static final int[] REPLICA_COUNTS = {1, 3, 5};
    
    private static final String RESULTS_DIR = "C:\\Users\\massi\\softwareSW1\\performance-results";
    private PrintWriter csvWriter;
    private String csvFileName;

    @BeforeEach
    public void setUp() throws IOException {
        // Reset dell'istanza singleton di MementoDistributionFunction prima di ogni test
        MementoDistributionFunction.reset(1);

        // Prepara il file CSV
        setupCsvFile();
    }

    private void setupCsvFile() throws IOException {
        // Crea la directory se non esiste
        Files.createDirectories(Paths.get(RESULTS_DIR));

        // Genera un nome file unico con timestamp
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        csvFileName = RESULTS_DIR + "\\performance_results_" + timestamp + ".csv";

        csvWriter = new PrintWriter(new FileWriter(csvFileName));

        // Scrivi l'header del CSV
        csvWriter.println("TestType,Nodes,Partitions,Replicas,MementoTime_ms,RendezvousTime_ms,Difference_ms,Timestamp");
    }
    
    @Test
    public void testPerformanceFewReplicas() throws IOException {
        System.out.println("=== TEST DI PERFORMANCE CON POCHE REPLICHE ===");
        System.out.println("Format: [nodi] [partizioni] [repliche] - Memento: [tempo ms] - Rendezvous: [tempo ms] - Diff: [differenza]");
        System.out.println("------------------------------------------------------------------------------");
        
        for (int nodeCount : NODE_COUNTS) {
            for (int partitions : PARTITION_COUNTS) {
                for (int replicas : REPLICA_COUNTS) {
                    // Skip configurazioni non valide
                    if (replicas > nodeCount) {
                        continue;
                    }
                    
                    PerformanceResult result = measurePerformance(nodeCount, partitions, replicas);

                    // Scrivi nel CSV
                    writeToCsv("FewReplicas", result);

                    System.out.printf("Nodi: %-4d Partizioni: %-6d Repliche: %-2d - Memento: %-5d ms - Rendezvous: %-5d ms - Diff: %d ms%n",
                            nodeCount, partitions, replicas, result.mementoTime, result.rendezvousTime, result.difference);
                }
            }
        }
        csvWriter.flush();
    }
    
    @Test
    public void testPerformanceFullReplicas() throws IOException {
        System.out.println("\n=== TEST DI PERFORMANCE CON REPLICA COMPLETA ===");
        System.out.println("Format: [nodi] [partizioni] - Memento: [tempo ms] - Rendezvous: [tempo ms] - Diff: [differenza]");
        System.out.println("------------------------------------------------------------------------------");
        
        for (int nodeCount : NODE_COUNTS) {
            for (int partitions : PARTITION_COUNTS) {
                PerformanceResult result = measurePerformanceFullReplicas(nodeCount, partitions);

                // Scrivi nel CSV
                writeToCsv("FullReplicas", nodeCount, partitions, nodeCount, result.mementoTime, result.rendezvousTime, result.difference);

                System.out.printf("Nodi: %-4d Partizioni: %-6d - Memento: %-5d ms - Rendezvous: %-5d ms - Diff: %d ms%n",
                        nodeCount, partitions, result.mementoTime, result.rendezvousTime, result.difference);
            }
        }
        csvWriter.flush();
    }
    
    @Test
    public void testPerformanceWithHotSpot() throws IOException {
        System.out.println("\n=== TEST DI PERFORMANCE CON TEMPO DI WARM-UP PER JVM HOTSPOT ===");
        System.out.println("Format: [nodi] [partizioni] [repliche] - Memento: [tempo ms] - Rendezvous: [tempo ms] - Diff: [differenza]");
        System.out.println("------------------------------------------------------------------------------");
        
        // Eseguiamo alcuni cicli per far warm-up di JVM HotSpot
        warmUp();
        
        int nodeCount = 100;
        int partitions = 10000;
        int replicas = 3;
        int consensusGroupSize = 2;
        
        List<String> nodes = prepareNetworkTopology(nodeCount);
        
        // Esegui più iterazioni e prendi la media
        int iterations = 5;
        long totalMementoTime = 0;
        long totalRendezvousTime = 0;
        
        for (int i = 0; i < iterations; i++) {
            // Test MementoDistributionFunction
            MementoDistributionFunction.reset(nodeCount);
            DistributionAlgorithm mementoAlgo = MementoDistributionFunction.getInstance(nodeCount);
            long mementoStartTime = System.nanoTime();
            mementoAlgo.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
            long mementoEndTime = System.nanoTime();
            totalMementoTime += (mementoEndTime - mementoStartTime) / 1_000_000; // Conversione da ns a ms
            
            // Test RendezvousDistributionFunction
            DistributionAlgorithm rendezvousAlgo = new RendezvousDistributionFunction();
            long rendezvousStartTime = System.nanoTime();
            rendezvousAlgo.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
            long rendezvousEndTime = System.nanoTime();
            totalRendezvousTime += (rendezvousEndTime - rendezvousStartTime) / 1_000_000; // Conversione da ns a ms
        }
        
        long avgMementoTime = totalMementoTime / iterations;
        long avgRendezvousTime = totalRendezvousTime / iterations;
        long diff = avgMementoTime - avgRendezvousTime;
        
        // Scrivi nel CSV
        writeToCsv("HotSpotOptimized", nodeCount, partitions, replicas, avgMementoTime, avgRendezvousTime, diff);

        System.out.printf("Nodi: %-4d Partizioni: %-6d Repliche: %-2d - Memento: %-5d ms - Rendezvous: %-5d ms - Diff: %d ms (media su %d iterazioni)%n",
                nodeCount, partitions, replicas, avgMementoTime, avgRendezvousTime, diff, iterations);

        csvWriter.flush();
        csvWriter.close();

        System.out.println("\nRisultati salvati in: " + csvFileName);
    }

    private PerformanceResult measurePerformance(int nodeCount, int partitions, int replicas) {
        List<String> nodes = prepareNetworkTopology(nodeCount);
        int consensusGroupSize = Math.max(1, replicas - 1);

        // Test MementoDistributionFunction
        MementoDistributionFunction.reset(nodeCount);
        DistributionAlgorithm mementoAlgo = MementoDistributionFunction.getInstance(nodeCount);
        long mementoStartTime = System.currentTimeMillis();
        mementoAlgo.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
        long mementoEndTime = System.currentTimeMillis();
        long mementoTime = mementoEndTime - mementoStartTime;

        // Test RendezvousDistributionFunction
        DistributionAlgorithm rendezvousAlgo = new RendezvousDistributionFunction();
        long rendezvousStartTime = System.currentTimeMillis();
        rendezvousAlgo.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
        long rendezvousEndTime = System.currentTimeMillis();
        long rendezvousTime = rendezvousEndTime - rendezvousStartTime;

        return new PerformanceResult(mementoTime, rendezvousTime, mementoTime - rendezvousTime);
    }

    private PerformanceResult measurePerformanceFullReplicas(int nodeCount, int partitions) {
        List<String> nodes = prepareNetworkTopology(nodeCount);
        int consensusGroupSize = Math.min(nodeCount, 5); // Un gruppo di consenso ragionevole

        // Test MementoDistributionFunction
        MementoDistributionFunction.reset(nodeCount);
        DistributionAlgorithm mementoAlgo = MementoDistributionFunction.getInstance(nodeCount);
        long mementoStartTime = System.currentTimeMillis();
        mementoAlgo.assignPartitions(nodes, emptyList(), partitions, DistributionAlgorithm.ALL_REPLICAS, consensusGroupSize);
        long mementoEndTime = System.currentTimeMillis();
        long mementoTime = mementoEndTime - mementoStartTime;

        // Test RendezvousDistributionFunction
        DistributionAlgorithm rendezvousAlgo = new RendezvousDistributionFunction();
        long rendezvousStartTime = System.currentTimeMillis();
        rendezvousAlgo.assignPartitions(nodes, emptyList(), partitions, DistributionAlgorithm.ALL_REPLICAS, consensusGroupSize);
        long rendezvousEndTime = System.currentTimeMillis();
        long rendezvousTime = rendezvousEndTime - rendezvousStartTime;

        return new PerformanceResult(mementoTime, rendezvousTime, mementoTime - rendezvousTime);
    }

    private void writeToCsv(String testType, PerformanceResult result) {
        // Per i test con poche repliche, usa i valori dal risultato
        writeToCsv(testType, 0, 0, 0, result.mementoTime, result.rendezvousTime, result.difference);
    }

    private void writeToCsv(String testType, int nodes, int partitions, int replicas, long mementoTime, long rendezvousTime, long difference) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        csvWriter.printf("%s,%d,%d,%d,%d,%d,%d,%s%n",
                testType, nodes, partitions, replicas, mementoTime, rendezvousTime, difference, timestamp);
    }
    
    private void warmUp() {
        // Esegui alcune operazioni per fare warm-up del JVM
        List<String> nodes = prepareNetworkTopology(50);
        DistributionAlgorithm mementoAlgo = MementoDistributionFunction.getInstance(50);
        DistributionAlgorithm rendezvousAlgo = new RendezvousDistributionFunction();
        
        for (int i = 0; i < 5; i++) {
            mementoAlgo.assignPartitions(nodes, emptyList(), 1000, 3, 2);
            rendezvousAlgo.assignPartitions(nodes, emptyList(), 1000, 3, 2);
        }
    }
    
    private List<String> prepareNetworkTopology(int nodes) {
        return IntStream.range(0, nodes)
                .mapToObj(i -> "Node " + i)
                .collect(Collectors.toUnmodifiableList());
    }

    private static class PerformanceResult {
        final long mementoTime;
        final long rendezvousTime;
        final long difference;

        PerformanceResult(long mementoTime, long rendezvousTime, long difference) {
            this.mementoTime = mementoTime;
            this.rendezvousTime = rendezvousTime;
            this.difference = difference;
        }
    }
}
