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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test di performance per confrontare:
 * - MementoDistributionFunction (originale)
 * - RendezvousDistributionFunction
 * - ThreadSafeMementoDistributionFunction senza pre-warming
 * - ThreadSafeMementoDistributionFunction con pre-warming
 */
public class PerformanceTest {
    
    private static final int[] NODE_COUNTS = {10, 50, 100, 200};
    private static final int[] PARTITION_COUNTS = {1000, 10000};
    private static final int[] REPLICA_COUNTS = {1, 3, 5};
    
    private static final String RESULTS_DIR = "C:\\Users\\massi\\softwareSW1\\performance-results";
    private PrintWriter csvWriter;
    private String csvFileName;
    
    private ThreadSafeMementoDistributionFunction threadSafeFunction;

    @BeforeEach
    public void setUp() throws IOException {
        // Reset dell'istanza singleton di MementoDistributionFunction prima di ogni test
        MementoDistributionFunction.reset(1);
        
        // Crea nuova istanza thread-safe
        threadSafeFunction = new ThreadSafeMementoDistributionFunction(1);

        // Prepara il file CSV
        setupCsvFile();
    }
    
    @AfterEach
    public void tearDown() {
        if (threadSafeFunction != null) {
            threadSafeFunction.stop();
        }
        if (csvWriter != null) {
            csvWriter.close();
        }
    }

    private void setupCsvFile() throws IOException {
        // Crea la directory se non esiste
        Files.createDirectories(Paths.get(RESULTS_DIR));

        // Genera un nome file unico con timestamp
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        csvFileName = RESULTS_DIR + "\\performance_results_" + timestamp + ".csv";

        csvWriter = new PrintWriter(new FileWriter(csvFileName));

        // Scrivi l'header del CSV con le nuove colonne
        csvWriter.println("TestType,Nodes,Partitions,Replicas,MementoTime_ms,RendezvousTime_ms,"
                + "ThreadSafeNoPreWarm_ms,ThreadSafeWithPreWarm_ms,BestTime_ms,Timestamp");
    }
    
    @Test
    public void testPerformanceFewReplicas() throws IOException {
        System.out.println("\n╔════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║       TEST DI PERFORMANCE: Confronto Algoritmi di Distribuzione           ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════════════╝\n");
        System.out.println("Confronto tra:");
        System.out.println("  1. MementoDistributionFunction (originale)");
        System.out.println("  2. RendezvousDistributionFunction");
        System.out.println("  3. ThreadSafeMementoDistributionFunction SENZA pre-warming");
        System.out.println("  4. ThreadSafeMementoDistributionFunction CON pre-warming");
        System.out.println("\n" + "─".repeat(130) + "\n");
        
        for (int nodeCount : NODE_COUNTS) {
            for (int partitions : PARTITION_COUNTS) {
                for (int replicas : REPLICA_COUNTS) {
                    // Skip configurazioni non valide
                    if (replicas > nodeCount) {
                        continue;
                    }
                    
                    PerformanceResult result = measurePerformance(nodeCount, partitions, replicas);

                    // Scrivi nel CSV
                    writeToCsv("FewReplicas", nodeCount, partitions, replicas, 
                              result.mementoTime, result.rendezvousTime, 
                              result.threadSafeNoPreWarmTime, result.threadSafeWithPreWarmTime);

                    // Output formattato
                    System.out.printf("Nodi: %-4d | Partizioni: %-6d | Repliche: %-2d%n", 
                                     nodeCount, partitions, replicas);
                    System.out.printf("  ├─ Memento (orig):       %5d ms%n", result.mementoTime);
                    System.out.printf("  ├─ Rendezvous:           %5d ms%n", result.rendezvousTime);
                    System.out.printf("  ├─ ThreadSafe NO pre:    %5d ms%n", result.threadSafeNoPreWarmTime);
                    System.out.printf("  └─ ThreadSafe CON pre:   %5d ms  ⭐%n", result.threadSafeWithPreWarmTime);
                    
                    long improvement = result.threadSafeNoPreWarmTime - result.threadSafeWithPreWarmTime;
                    double improvementPercent = (improvement * 100.0) / result.threadSafeNoPreWarmTime;
                    System.out.printf("     Pre-warming improvement: %d ms (%.1f%%)%n%n", improvement, improvementPercent);
                }
            }
        }
        csvWriter.flush();
    }
    
    @Test
    public void testPerformanceFullReplicas() throws IOException {
        System.out.println("\n╔════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║               TEST DI PERFORMANCE CON REPLICA COMPLETA                     ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════════════╝\n");
        System.out.println("─".repeat(130) + "\n");
        
        for (int nodeCount : NODE_COUNTS) {
            for (int partitions : PARTITION_COUNTS) {
                PerformanceResult result = measurePerformanceFullReplicas(nodeCount, partitions);

                // Scrivi nel CSV
                writeToCsv("FullReplicas", nodeCount, partitions, nodeCount, 
                          result.mementoTime, result.rendezvousTime, 
                          result.threadSafeNoPreWarmTime, result.threadSafeWithPreWarmTime);

                System.out.printf("Nodi: %-4d | Partizioni: %-6d | Repliche: ALL%n", 
                                 nodeCount, partitions);
                System.out.printf("  ├─ Memento (orig):       %5d ms%n", result.mementoTime);
                System.out.printf("  ├─ Rendezvous:           %5d ms%n", result.rendezvousTime);
                System.out.printf("  ├─ ThreadSafe NO pre:    %5d ms%n", result.threadSafeNoPreWarmTime);
                System.out.printf("  └─ ThreadSafe CON pre:   %5d ms  ⭐%n", result.threadSafeWithPreWarmTime);
                
                long improvement = result.threadSafeNoPreWarmTime - result.threadSafeWithPreWarmTime;
                double improvementPercent = (improvement * 100.0) / result.threadSafeNoPreWarmTime;
                System.out.printf("     Pre-warming improvement: %d ms (%.1f%%)%n%n", improvement, improvementPercent);
            }
        }
        csvWriter.flush();
    }
    
    @Test
    public void testPerformanceWithHotSpot() throws IOException {
        System.out.println("\n╔════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║         TEST DI PERFORMANCE CON JVM HOTSPOT OPTIMIZATION                  ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════════════╝\n");
        
        // Eseguiamo alcuni cicli per far warm-up di JVM HotSpot
        System.out.println("⏳ Esecuzione warm-up JVM HotSpot...");
        warmUp();
        System.out.println("✓ Warm-up completato\n");
        
        int nodeCount = 100;
        int partitions = 10000;
        int replicas = 3;
        int consensusGroupSize = 2;
        
        List<String> nodes = prepareNetworkTopology(nodeCount);
        
        // Esegui più iterazioni e prendi la media
        int iterations = 10;
        long totalMementoTime = 0;
        long totalRendezvousTime = 0;
        long totalThreadSafeNoPreWarmTime = 0;
        long totalThreadSafeWithPreWarmTime = 0;
        
        System.out.println("📊 Esecuzione " + iterations + " iterazioni per calcolare la media...\n");
        
        for (int i = 0; i < iterations; i++) {
            // Test 1: MementoDistributionFunction
            MementoDistributionFunction.reset(nodeCount);
            DistributionAlgorithm mementoAlgo = MementoDistributionFunction.getInstance(nodeCount);
            long mementoStartTime = System.nanoTime();
            mementoAlgo.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
            long mementoEndTime = System.nanoTime();
            totalMementoTime += (mementoEndTime - mementoStartTime) / 1_000_000;
            
            // Test 2: RendezvousDistributionFunction
            DistributionAlgorithm rendezvousAlgo = new RendezvousDistributionFunction();
            long rendezvousStartTime = System.nanoTime();
            rendezvousAlgo.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
            long rendezvousEndTime = System.nanoTime();
            totalRendezvousTime += (rendezvousEndTime - rendezvousStartTime) / 1_000_000;
            
            // Test 3: ThreadSafe SENZA pre-warming
            ThreadSafeMementoDistributionFunction threadSafeNoPreWarm = new ThreadSafeMementoDistributionFunction(1);
            long tsNoPreStart = System.nanoTime();
            threadSafeNoPreWarm.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
            long tsNoPreEnd = System.nanoTime();
            totalThreadSafeNoPreWarmTime += (tsNoPreEnd - tsNoPreStart) / 1_000_000;
            threadSafeNoPreWarm.stop();
            
            // Test 4: ThreadSafe CON pre-warming
            ThreadSafeMementoDistributionFunction threadSafeWithPreWarm = new ThreadSafeMementoDistributionFunction(1);
            threadSafeWithPreWarm.updateTopology(nodes);
            long tsWithPreStart = System.nanoTime();
            threadSafeWithPreWarm.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
            long tsWithPreEnd = System.nanoTime();
            totalThreadSafeWithPreWarmTime += (tsWithPreEnd - tsWithPreStart) / 1_000_000;
            threadSafeWithPreWarm.stop();
            
            System.out.print(".");
            if ((i + 1) % 10 == 0) System.out.println(" " + (i + 1) + "/" + iterations);
        }
        
        long avgMementoTime = totalMementoTime / iterations;
        long avgRendezvousTime = totalRendezvousTime / iterations;
        long avgThreadSafeNoPreWarmTime = totalThreadSafeNoPreWarmTime / iterations;
        long avgThreadSafeWithPreWarmTime = totalThreadSafeWithPreWarmTime / iterations;
        
        // Scrivi nel CSV
        writeToCsv("HotSpotOptimized", nodeCount, partitions, replicas, 
                  avgMementoTime, avgRendezvousTime, avgThreadSafeNoPreWarmTime, avgThreadSafeWithPreWarmTime);

        System.out.println("\n\n" + "═".repeat(130));
        System.out.println("RISULTATI FINALI (media su " + iterations + " iterazioni):");
        System.out.println("═".repeat(130));
        System.out.printf("Nodi: %-4d | Partizioni: %-6d | Repliche: %-2d%n%n", nodeCount, partitions, replicas);
        System.out.printf("  ├─ Memento (orig):       %5d ms%n", avgMementoTime);
        System.out.printf("  ├─ Rendezvous:           %5d ms%n", avgRendezvousTime);
        System.out.printf("  ├─ ThreadSafe NO pre:    %5d ms%n", avgThreadSafeNoPreWarmTime);
        System.out.printf("  └─ ThreadSafe CON pre:   %5d ms  ⭐%n%n", avgThreadSafeWithPreWarmTime);
        
        long improvement = avgThreadSafeNoPreWarmTime - avgThreadSafeWithPreWarmTime;
        double improvementPercent = (improvement * 100.0) / avgThreadSafeNoPreWarmTime;
        System.out.println("🚀 PRE-WARMING PERFORMANCE:");
        System.out.printf("   Miglioramento: %d ms (%.1f%%)%n", improvement, improvementPercent);
        
        long bestTime = Math.min(Math.min(avgMementoTime, avgRendezvousTime), 
                                Math.min(avgThreadSafeNoPreWarmTime, avgThreadSafeWithPreWarmTime));
        String winner = "";
        if (bestTime == avgMementoTime) winner = "Memento (orig)";
        else if (bestTime == avgRendezvousTime) winner = "Rendezvous";
        else if (bestTime == avgThreadSafeNoPreWarmTime) winner = "ThreadSafe NO pre";
        else winner = "ThreadSafe CON pre";
        
        System.out.println("\n🏆 Algoritmo più veloce: " + winner + " (" + bestTime + " ms)");
        System.out.println("═".repeat(130));

        csvWriter.flush();
        csvWriter.close();

        System.out.println("\n✓ Risultati salvati in: " + csvFileName);
    }

    private PerformanceResult measurePerformance(int nodeCount, int partitions, int replicas) {
        List<String> nodes = prepareNetworkTopology(nodeCount);
        int consensusGroupSize = Math.max(1, replicas - 1);

        // Test 1: MementoDistributionFunction (originale)
        MementoDistributionFunction.reset(nodeCount);
        DistributionAlgorithm mementoAlgo = MementoDistributionFunction.getInstance(nodeCount);
        long mementoStartTime = System.currentTimeMillis();
        mementoAlgo.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
        long mementoEndTime = System.currentTimeMillis();
        long mementoTime = mementoEndTime - mementoStartTime;

        // Test 2: RendezvousDistributionFunction
        DistributionAlgorithm rendezvousAlgo = new RendezvousDistributionFunction();
        long rendezvousStartTime = System.currentTimeMillis();
        rendezvousAlgo.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
        long rendezvousEndTime = System.currentTimeMillis();
        long rendezvousTime = rendezvousEndTime - rendezvousStartTime;

        // Test 3: ThreadSafeMementoDistributionFunction SENZA pre-warming
        // (topology aggiornata inline durante assignPartitions)
        ThreadSafeMementoDistributionFunction threadSafeNoPreWarm = new ThreadSafeMementoDistributionFunction(1);
        long threadSafeNoPreWarmStart = System.currentTimeMillis();
        threadSafeNoPreWarm.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
        long threadSafeNoPreWarmEnd = System.currentTimeMillis();
        long threadSafeNoPreWarmTime = threadSafeNoPreWarmEnd - threadSafeNoPreWarmStart;
        threadSafeNoPreWarm.stop();

        // Test 4: ThreadSafeMementoDistributionFunction CON pre-warming
        // (topology già aggiornata prima di assignPartitions)
        ThreadSafeMementoDistributionFunction threadSafeWithPreWarm = new ThreadSafeMementoDistributionFunction(1);
        
        // PRE-WARMING: aggiorna la topology PRIMA di chiamare assignPartitions
        threadSafeWithPreWarm.updateTopology(nodes);
        
        // Ora chiama assignPartitions - dovrebbe trovare la topology già aggiornata
        long threadSafeWithPreWarmStart = System.currentTimeMillis();
        threadSafeWithPreWarm.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
        long threadSafeWithPreWarmEnd = System.currentTimeMillis();
        long threadSafeWithPreWarmTime = threadSafeWithPreWarmEnd - threadSafeWithPreWarmStart;
        threadSafeWithPreWarm.stop();

        return new PerformanceResult(
            mementoTime, 
            rendezvousTime, 
            threadSafeNoPreWarmTime,
            threadSafeWithPreWarmTime
        );
    }

    private PerformanceResult measurePerformanceFullReplicas(int nodeCount, int partitions) {
        List<String> nodes = prepareNetworkTopology(nodeCount);
        int consensusGroupSize = Math.min(nodeCount, 5); // Un gruppo di consenso ragionevole

        // Test 1: MementoDistributionFunction
        MementoDistributionFunction.reset(nodeCount);
        DistributionAlgorithm mementoAlgo = MementoDistributionFunction.getInstance(nodeCount);
        long mementoStartTime = System.currentTimeMillis();
        mementoAlgo.assignPartitions(nodes, emptyList(), partitions, DistributionAlgorithm.ALL_REPLICAS, consensusGroupSize);
        long mementoEndTime = System.currentTimeMillis();
        long mementoTime = mementoEndTime - mementoStartTime;

        // Test 2: RendezvousDistributionFunction
        DistributionAlgorithm rendezvousAlgo = new RendezvousDistributionFunction();
        long rendezvousStartTime = System.currentTimeMillis();
        rendezvousAlgo.assignPartitions(nodes, emptyList(), partitions, DistributionAlgorithm.ALL_REPLICAS, consensusGroupSize);
        long rendezvousEndTime = System.currentTimeMillis();
        long rendezvousTime = rendezvousEndTime - rendezvousStartTime;

        // Test 3: ThreadSafeMementoDistributionFunction SENZA pre-warming
        ThreadSafeMementoDistributionFunction threadSafeNoPreWarm = new ThreadSafeMementoDistributionFunction(1);
        long threadSafeNoPreWarmStart = System.currentTimeMillis();
        threadSafeNoPreWarm.assignPartitions(nodes, emptyList(), partitions, DistributionAlgorithm.ALL_REPLICAS, consensusGroupSize);
        long threadSafeNoPreWarmEnd = System.currentTimeMillis();
        long threadSafeNoPreWarmTime = threadSafeNoPreWarmEnd - threadSafeNoPreWarmStart;
        threadSafeNoPreWarm.stop();

        // Test 4: ThreadSafeMementoDistributionFunction CON pre-warming
        ThreadSafeMementoDistributionFunction threadSafeWithPreWarm = new ThreadSafeMementoDistributionFunction(1);
        threadSafeWithPreWarm.updateTopology(nodes);
        long threadSafeWithPreWarmStart = System.currentTimeMillis();
        threadSafeWithPreWarm.assignPartitions(nodes, emptyList(), partitions, DistributionAlgorithm.ALL_REPLICAS, consensusGroupSize);
        long threadSafeWithPreWarmEnd = System.currentTimeMillis();
        long threadSafeWithPreWarmTime = threadSafeWithPreWarmEnd - threadSafeWithPreWarmStart;
        threadSafeWithPreWarm.stop();

        return new PerformanceResult(
            mementoTime, 
            rendezvousTime, 
            threadSafeNoPreWarmTime,
            threadSafeWithPreWarmTime
        );
    }

    private void writeToCsv(String testType, PerformanceResult result) {
        // Non serve più questo metodo, usa direttamente l'altro
    }

    private void writeToCsv(String testType, int nodes, int partitions, int replicas, 
                           long mementoTime, long rendezvousTime, long threadSafeNoPreWarm, long threadSafeWithPreWarm) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        long bestTime = Math.min(Math.min(mementoTime, rendezvousTime), 
                                Math.min(threadSafeNoPreWarm, threadSafeWithPreWarm));
        csvWriter.printf("%s,%d,%d,%d,%d,%d,%d,%d,%d,%s%n",
                testType, nodes, partitions, replicas, 
                mementoTime, rendezvousTime, threadSafeNoPreWarm, threadSafeWithPreWarm, bestTime,
                timestamp);
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
        final long threadSafeNoPreWarmTime;
        final long threadSafeWithPreWarmTime;

        PerformanceResult(long mementoTime, long rendezvousTime, 
                         long threadSafeNoPreWarmTime, long threadSafeWithPreWarmTime) {
            this.mementoTime = mementoTime;
            this.rendezvousTime = rendezvousTime;
            this.threadSafeNoPreWarmTime = threadSafeNoPreWarmTime;
            this.threadSafeWithPreWarmTime = threadSafeWithPreWarmTime;
        }
    }
}
