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
 * - RendezvousDistributionFunction
 * - ThreadSafeMementoDistributionFunction con pre-warming (Memento)
 */
public class PerformanceTest {
    
    private static final int[] NODE_COUNTS = {10, 50, 100, 200};
    private static final int[] NODE_COUNTS_FULL_REPLICAS = {10, 50};

    private static final int[] PARTITION_COUNTS = {1000, 10000};
    private static final int[] REPLICA_COUNTS = {1, 3, 5};
    
    private static final String RESULTS_DIR = "C:\\Users\\massi\\softwareSW1\\performance-results";
    private PrintWriter csvWriter;
    private String csvFileName;
    private String testType;
    
    private ThreadSafeMementoDistributionFunction threadSafeFunction;

    @BeforeEach
    public void setUp() throws IOException {
        // Reset dell'istanza singleton di MementoDistributionFunction prima di ogni test
        MementoDistributionFunction.reset(1);
        
        // Crea nuova istanza thread-safe
        threadSafeFunction = new ThreadSafeMementoDistributionFunction(1);
    }
    
    @AfterEach
    public void tearDown() {
        if (threadSafeFunction != null) {
            threadSafeFunction.stop();
        }
        if (csvWriter != null) {
            csvWriter.flush();
            csvWriter.close();
        }
    }

    private void setupCsvFile(String testType) throws IOException {
        // Crea la directory se non esiste
        Files.createDirectories(Paths.get(RESULTS_DIR));

        // Genera un nome file unico con timestamp per ogni test
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        csvFileName = RESULTS_DIR + "\\performance_" + testType.toLowerCase() + "_" + timestamp + ".csv";

        csvWriter = new PrintWriter(new FileWriter(csvFileName));
        this.testType = testType;

        // Scrivi l'header del CSV con le nuove colonne
        csvWriter.println("TestType,Nodes,Partitions,Replicas,RendezvousTime_ms,MementoTime_ms,BestTime_ms,Timestamp");
    }
    
    @Test
    public void testPerformanceFewReplicas() throws IOException {
        // Prepara il file CSV per questo test
        setupCsvFile("FewReplicas");
        
        System.out.println("\n╔════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║       TEST DI PERFORMANCE: Confronto Algoritmi di Distribuzione           ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════════════╝\n");
        System.out.println("Confronto tra:");
        System.out.println("  1. RendezvousDistributionFunction");
        System.out.println("  2. ThreadSafeMementoDistributionFunction con pre-warming (Memento)");
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
                    writeToCsv(nodeCount, partitions, replicas, 
                              result.rendezvousTime, result.mementoTime);

                    // Output formattato
                    System.out.printf("Nodi: %-4d | Partizioni: %-6d | Repliche: %-2d%n", 
                                     nodeCount, partitions, replicas);
                    System.out.printf("  ├─ Rendezvous:           %5d ms%n", result.rendezvousTime);
                    System.out.printf("  └─ Memento (pre-warm):   %5d ms  ⭐%n", result.mementoTime);
                    
                    long improvement = result.rendezvousTime - result.mementoTime;
                    double improvementPercent = (improvement * 100.0) / result.rendezvousTime;
                    if (improvement > 0) {
                        System.out.printf("     Memento migliora: %d ms (%.1f%%)%n%n", improvement, improvementPercent);
                    } else {
                        System.out.printf("     Rendezvous migliore: %d ms (%.1f%%)%n%n", -improvement, -improvementPercent);
                    }
                }
            }
        }
        csvWriter.flush();
        System.out.println("\n✓ Risultati FewReplicas salvati in: " + csvFileName);
    }
    
    @Test
    public void testPerformanceFullReplicas() throws IOException {
        // Prepara il file CSV per questo test
        setupCsvFile("FullReplicas");
        
        System.out.println("\n╔════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║               TEST DI PERFORMANCE CON REPLICA COMPLETA                     ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════════════╝\n");
        System.out.println("─".repeat(130) + "\n");
        
        for (int nodeCount : NODE_COUNTS_FULL_REPLICAS) {
            for (int partitions : PARTITION_COUNTS) {
                PerformanceResult result = measurePerformanceFullReplicas(nodeCount, partitions);

                // Scrivi nel CSV
                writeToCsv(nodeCount, partitions, nodeCount, 
                          result.rendezvousTime, result.mementoTime);

                System.out.printf("Nodi: %-4d | Partizioni: %-6d | Repliche: ALL%n", 
                                 nodeCount, partitions);
                System.out.printf("  ├─ Rendezvous:           %5d ms%n", result.rendezvousTime);
                System.out.printf("  └─ Memento (pre-warm):   %5d ms  ⭐%n", result.mementoTime);
                
                long improvement = result.rendezvousTime - result.mementoTime;
                double improvementPercent = (improvement * 100.0) / result.rendezvousTime;
                if (improvement > 0) {
                    System.out.printf("     Memento migliora: %d ms (%.1f%%)%n%n", improvement, improvementPercent);
                } else {
                    System.out.printf("     Rendezvous migliore: %d ms (%.1f%%)%n%n", -improvement, -improvementPercent);
                }
            }
        }
        csvWriter.flush();
        System.out.println("\n✓ Risultati FullReplicas salvati in: " + csvFileName);
    }
    
    @Test
    public void testPerformanceWithHotSpot() throws IOException {
        // Prepara il file CSV per questo test
        setupCsvFile("HotSpotOptimized");
        
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
        long totalRendezvousTime = 0;
        long totalMementoTime = 0;
        
        System.out.println("📊 Esecuzione " + iterations + " iterazioni per calcolare la media...\n");
        
        for (int i = 0; i < iterations; i++) {
            // Test 1: RendezvousDistributionFunction
            DistributionAlgorithm rendezvousAlgo = new RendezvousDistributionFunction();
            long rendezvousStartTime = System.nanoTime();
            rendezvousAlgo.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
            long rendezvousEndTime = System.nanoTime();
            totalRendezvousTime += (rendezvousEndTime - rendezvousStartTime) / 1_000_000;
            
            // Test 2: ThreadSafe CON pre-warming (Memento)
            ThreadSafeMementoDistributionFunction mementoAlgo = new ThreadSafeMementoDistributionFunction(1);
            mementoAlgo.updateTopology(nodes);
            long mementoStartTime = System.nanoTime();
            mementoAlgo.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
            long mementoEndTime = System.nanoTime();
            totalMementoTime += (mementoEndTime - mementoStartTime) / 1_000_000;
            mementoAlgo.stop();
            
            System.out.print(".");
            if ((i + 1) % 10 == 0) System.out.println(" " + (i + 1) + "/" + iterations);
        }
        
        long avgRendezvousTime = totalRendezvousTime / iterations;
        long avgMementoTime = totalMementoTime / iterations;
        
        // Scrivi nel CSV
        writeToCsv(nodeCount, partitions, replicas, 
                  avgRendezvousTime, avgMementoTime);

        System.out.println("\n\n" + "═".repeat(130));
        System.out.println("RISULTATI FINALI (media su " + iterations + " iterazioni):");
        System.out.println("═".repeat(130));
        System.out.printf("Nodi: %-4d | Partizioni: %-6d | Repliche: %-2d%n%n", nodeCount, partitions, replicas);
        System.out.printf("  ├─ Rendezvous:           %5d ms%n", avgRendezvousTime);
        System.out.printf("  └─ Memento (pre-warm):   %5d ms  ⭐%n%n", avgMementoTime);
        
        long improvement = avgRendezvousTime - avgMementoTime;
        double improvementPercent = (improvement * 100.0) / avgRendezvousTime;
        System.out.println("🚀 PERFORMANCE COMPARISON:");
        if (improvement > 0) {
            System.out.printf("   Memento migliora: %d ms (%.1f%%)%n", improvement, improvementPercent);
        } else {
            System.out.printf("   Rendezvous migliore: %d ms (%.1f%%)%n", -improvement, -improvementPercent);
        }
        
        long bestTime = Math.min(avgRendezvousTime, avgMementoTime);
        String winner = (bestTime == avgRendezvousTime) ? "Rendezvous" : "Memento (pre-warm)";
        
        System.out.println("\n🏆 Algoritmo più veloce: " + winner + " (" + bestTime + " ms)");
        System.out.println("═".repeat(130));

        csvWriter.flush();
        csvWriter.close();

        System.out.println("\n✓ Risultati HotSpotOptimized salvati in: " + csvFileName);
    }

    private PerformanceResult measurePerformance(int nodeCount, int partitions, int replicas) {
        List<String> nodes = prepareNetworkTopology(nodeCount);
        int consensusGroupSize = Math.max(1, replicas - 1);

        // Test 1: RendezvousDistributionFunction
        DistributionAlgorithm rendezvousAlgo = new RendezvousDistributionFunction();
        long rendezvousStartTime = System.currentTimeMillis();
        rendezvousAlgo.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
        long rendezvousEndTime = System.currentTimeMillis();
        long rendezvousTime = rendezvousEndTime - rendezvousStartTime;

        // Test 2: ThreadSafeMementoDistributionFunction CON pre-warming (Memento)
        ThreadSafeMementoDistributionFunction mementoAlgo = new ThreadSafeMementoDistributionFunction(1);
        
        // PRE-WARMING: aggiorna la topology PRIMA di chiamare assignPartitions
        mementoAlgo.updateTopology(nodes);
        
        // Ora chiama assignPartitions - dovrebbe trovare la topology già aggiornata
        long mementoStartTime = System.currentTimeMillis();
        mementoAlgo.assignPartitions(nodes, emptyList(), partitions, replicas, consensusGroupSize);
        long mementoEndTime = System.currentTimeMillis();
        long mementoTime = mementoEndTime - mementoStartTime;
        mementoAlgo.stop();

        return new PerformanceResult(rendezvousTime, mementoTime);
    }

    private PerformanceResult measurePerformanceFullReplicas(int nodeCount, int partitions) {
        List<String> nodes = prepareNetworkTopology(nodeCount);
        int consensusGroupSize = Math.min(nodeCount, 5); // Un gruppo di consenso ragionevole

        // Test 1: RendezvousDistributionFunction
        DistributionAlgorithm rendezvousAlgo = new RendezvousDistributionFunction();
        long rendezvousStartTime = System.currentTimeMillis();
        rendezvousAlgo.assignPartitions(nodes, emptyList(), partitions, DistributionAlgorithm.ALL_REPLICAS, consensusGroupSize);
        long rendezvousEndTime = System.currentTimeMillis();
        long rendezvousTime = rendezvousEndTime - rendezvousStartTime;

        // Test 2: ThreadSafeMementoDistributionFunction CON pre-warming (Memento)
        ThreadSafeMementoDistributionFunction mementoAlgo = new ThreadSafeMementoDistributionFunction(1);
        mementoAlgo.updateTopology(nodes);
        long mementoStartTime = System.currentTimeMillis();
        mementoAlgo.assignPartitions(nodes, emptyList(), partitions, DistributionAlgorithm.ALL_REPLICAS, consensusGroupSize);
        long mementoEndTime = System.currentTimeMillis();
        long mementoTime = mementoEndTime - mementoStartTime;
        mementoAlgo.stop();

        return new PerformanceResult(rendezvousTime, mementoTime);
    }

    private void writeToCsv(int nodes, int partitions, int replicas, 
                           long rendezvousTime, long mementoTime) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        long bestTime = Math.min(rendezvousTime, mementoTime);
        csvWriter.printf("%s,%d,%d,%d,%d,%d,%d,%s%n",
                testType, nodes, partitions, replicas, 
                rendezvousTime, mementoTime, bestTime,
                timestamp);
    }
    
    private void warmUp() {
        // Esegui alcune operazioni per fare warm-up del JVM
        List<String> nodes = prepareNetworkTopology(50);
        DistributionAlgorithm rendezvousAlgo = new RendezvousDistributionFunction();
        ThreadSafeMementoDistributionFunction threadSafeAlgo = new ThreadSafeMementoDistributionFunction(1);
        
        for (int i = 0; i < 5; i++) {
            rendezvousAlgo.assignPartitions(nodes, emptyList(), 1000, 3, 2);
            threadSafeAlgo.assignPartitions(nodes, emptyList(), 1000, 3, 2);
        }
        threadSafeAlgo.stop();
    }
    
    private List<String> prepareNetworkTopology(int nodes) {
        return IntStream.range(0, nodes)
                .mapToObj(i -> "Node " + i)
                .collect(Collectors.toUnmodifiableList());
    }

    private static class PerformanceResult {
        final long rendezvousTime;
        final long mementoTime;

        PerformanceResult(long rendezvousTime, long mementoTime) {
            this.rendezvousTime = rendezvousTime;
            this.mementoTime = mementoTime;
        }
    }
}
