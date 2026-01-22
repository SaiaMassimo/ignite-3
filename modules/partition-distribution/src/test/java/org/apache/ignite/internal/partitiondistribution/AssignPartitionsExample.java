package org.apache.ignite.internal.partitiondistribution;

import static java.util.Collections.emptyList;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Esempio pratico di utilizzo di assignPartitions
 */
public class AssignPartitionsExample {
    
    public static void main(String[] args) {
        // Esempio pratico di utilizzo
        example1_BasicUsage();
        example2_PeerVsLearner();
        example3_MultipleReplicas();
    }
    
    /**
     * Esempio 1: Uso base di assignPartitions
     */
    public static void example1_BasicUsage() {
        System.out.println("=== Esempio 1: Uso Base ===");
        
        // Setup
        List<String> nodes = Arrays.asList("node-1", "node-2", "node-3");
        int partitions = 4;
        int replicas = 2;
        int consensusGroupSize = 1;
        
        // Chiamata al metodo
        DistributionAlgorithm algo = new RendezvousDistributionFunction();
        List<Set<Assignment>> result = algo.assignPartitions(
            nodes, 
            emptyList(),  // Nessuna distribuzione precedente
            partitions, 
            replicas, 
            consensusGroupSize
        );
        
        // Stampa risultati
        System.out.println("Cluster con " + nodes.size() + " nodi:");
        System.out.println("Nodi: " + nodes);
        System.out.println("\nDistribuzione delle " + partitions + " partizioni:");
        System.out.println("(Repliche: " + replicas + ", Consensus: " + consensusGroupSize + ")\n");
        
        for (int partitionId = 0; partitionId < partitions; partitionId++) {
            Set<Assignment> partitionAssignments = result.get(partitionId);
            
            System.out.println("Partizione " + partitionId + " assegnata a:");
            for (Assignment assignment : partitionAssignments) {
                String role = assignment.isPeer() ? "Peer (sincrono)" : "Learner (asincrono)";
                System.out.println("  - " + assignment.consistentId() + " [" + role + "]");
            }
            System.out.println();
        }
    }
    
    /**
     * Esempio 2: Differenza tra Peer e Learner
     */
    public static void example2_PeerVsLearner() {
        System.out.println("\n=== Esempio 2: Peer vs Learner ===");
        
        // Setup con 3 repliche e 2 nodi sincroni (consensus)
        List<String> nodes = Arrays.asList("node-A", "node-B", "node-C");
        int partitions = 1;
        int replicas = 3;
        int consensusGroupSize = 2;  // 2 nodi sincroni
        
        DistributionAlgorithm algo = new RendezvousDistributionFunction();
        List<Set<Assignment>> result = algo.assignPartitions(
            nodes, 
            emptyList(), 
            partitions, 
            replicas, 
            consensusGroupSize
        );
        
        System.out.println("Partizione 0 con " + replicas + " repliche:");
        System.out.println("Consensus group size: " + consensusGroupSize + " (nodi sincroni)\n");
        
        Set<Assignment> assignments = result.get(0);
        int peerCount = 0;
        int learnerCount = 0;
        
        for (Assignment assignment : assignments) {
            if (assignment.isPeer()) {
                peerCount++;
                System.out.println("Peer #" + peerCount + ": " + assignment.consistentId());
                System.out.println("  → Riceve aggiornamenti SINCRONAMENTE");
                System.out.println("  → Partecipa al consenso");
                System.out.println("  → Coerenza forte");
            } else {
                learnerCount++;
                System.out.println("Learner #" + learnerCount + ": " + assignment.consistentId());
                System.out.println("  → Riceve aggiornamenti ASINCRONAMENTE");
                System.out.println("  → Non partecipa al consenso");
                System.out.println("  → Consistenza eventuale");
            }
            System.out.println();
        }
        
        System.out.println("Durante una scrittura:");
        System.out.println("  1. Peer confermano l'operazione (consenso)");
        System.out.println("  2. Learner ricevono l'aggiornamento dopo");
    }
    
    /**
     * Esempio 3: Cluster con molte repliche
     */
    public static void example3_MultipleReplicas() {
        System.out.println("\n=== Esempio 3: Cluster con molte repliche ===");
        
        // Setup cluster più grande
        List<String> nodes = Arrays.asList(
            "node-1", "node-2", "node-3", "node-4", "node-5"
        );
        
        int partitions = 10;
        int replicas = 5;  // Ogni partizione ha 5 repliche
        int consensusGroupSize = 3;  // 3 nodi sincroni
        
        DistributionAlgorithm algo = new RendezvousDistributionFunction();
        List<Set<Assignment>> result = algo.assignPartitions(
            nodes, 
            emptyList(), 
            partitions, 
            replicas, 
            consensusGroupSize
        );
        
        System.out.println("Cluster: " + nodes.size() + " nodi");
        System.out.println("Partizioni: " + partitions);
        System.out.println("Repliche per partizione: " + replicas);
        System.out.println("Consensus group size: " + consensusGroupSize + "\n");
        
        // Analisi della distribuzione
        System.out.println("Distribuzione delle prime 5 partizioni:");
        for (int part = 0; part < 5; part++) {
            Set<Assignment> assignments = result.get(part);
            System.out.println("\nPartizione " + part + ":");
            
            int peerIdx = 1;
            int learnerIdx = 1;
            
            for (Assignment assignment : assignments) {
                if (assignment.isPeer()) {
                    System.out.println("  Peer " + peerIdx++ + ": " + assignment.consistentId());
                } else {
                    System.out.println("  Learner " + learnerIdx++ + ": " + assignment.consistentId());
                }
            }
        }
        
        // Statistiche
        System.out.println("\n" + "=".repeat(50));
        System.out.println("Statistiche:");
        System.out.println("  - Ogni partizione ha " + consensusGroupSize + " nodi sincroni (peers)");
        System.out.println("  - Ogni partizione ha " + (replicas - consensusGroupSize) + " nodi asincroni (learners)");
        System.out.println("  - Disponibilità: " + replicas + " copie dei dati");
        System.out.println("  - Consenso: " + consensusGroupSize + " nodi devono confermare");
    }
    
    /**
     * Esempio 4: Confronto tra Rendezvous e Memento
     */
    public static void example4_CompareAlgorithms() {
        System.out.println("\n=== Esempio 4: Confronto Algoritmi ===");
        
        List<String> initialNodes = Arrays.asList("node-1", "node-2", "node-3");
        List<String> nodesAfterRemoval = Arrays.asList("node-1", "node-2");  // node-3 rimosso
        
        int partitions = 100;
        int replicas = 2;
        int consensusGroupSize = 1;
        
        // Test Rendezvous
        DistributionAlgorithm rendezvous = new RendezvousDistributionFunction();
        List<Set<Assignment>> rendezvousInitial = rendezvous.assignPartitions(
            initialNodes, emptyList(), partitions, replicas, consensusGroupSize
        );
        List<Set<Assignment>> rendezvousAfterRemoval = rendezvous.assignPartitions(
            nodesAfterRemoval, emptyList(), partitions, replicas, consensusGroupSize
        );
        
        // Test Memento
        ThreadSafeMementoDistributionFunction memento = new ThreadSafeMementoDistributionFunction(1);
        memento.updateTopology(initialNodes);
        List<Set<Assignment>> mementoInitial = memento.assignPartitions(
            initialNodes, emptyList(), partitions, replicas, consensusGroupSize
        );
        memento.updateTopology(nodesAfterRemoval);
        List<Set<Assignment>> mementoAfterRemoval = memento.assignPartitions(
            nodesAfterRemoval, emptyList(), partitions, replicas, consensusGroupSize
        );
        
        // Analisi
        int rendezvousChanges = countChanges(rendezvousInitial, rendezvousAfterRemoval);
        int mementoChanges = countChanges(mementoInitial, mementoAfterRemoval);
        
        System.out.println("Nodo rimosso: node-3");
        System.out.println("Partizioni totali: " + partitions);
        System.out.println("\nRendezvous:");
        System.out.println("  - Partizioni che cambiano assegnamento: " + rendezvousChanges);
        System.out.println("  - Percentuale di cambiamenti: " + (rendezvousChanges * 100.0 / partitions) + "%");
        
        System.out.println("\nMemento:");
        System.out.println("  - Partizioni che cambiano assegnamento: " + mementoChanges);
        System.out.println("  - Percentuale di cambiamenti: " + (mementoChanges * 100.0 / partitions) + "%");
        
        System.out.println("\nConclusioni:");
        System.out.println("  - Memento minimizza il movimento dei dati");
        System.out.println("  - Meno ripartizionamento = meno overhead");
    }
    
    private static int countChanges(List<Set<Assignment>> before, List<Set<Assignment>> after) {
        int changes = 0;
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).equals(after.get(i))) {
                changes++;
            }
        }
        return changes;
    }
}

