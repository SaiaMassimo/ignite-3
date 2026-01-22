# Spiegazione di `assignPartitions`

## Cosa restituisce `assignPartitions`?

Il metodo `assignPartitions` restituisce una **`List<Set<Assignment>>`** che rappresenta la distribuzione delle partizioni sui nodi del cluster.

## Struttura del risultato

```java
List<Set<Assignment>> assignPartitions(
    Collection<String> nodes,        // Lista dei nodi del cluster
    List<List<String>> currentDistribution,
    int partitions,                   // Numero di partizioni da assegnare
    int replicaFactor,                 // Numero di repliche per partizione
    int consensusGroupSize            // Numero di nodi sincroni (peers)
);
```

### Struttura della Lista

- **`List<Set<Assignment>>`**: Una lista dove ogni elemento rappresenta una partizione
- **Indice**: L'indice nella lista corrisponde all'ID della partizione (0, 1, 2, ..., partitions-1)
- **`Set<Assignment>`**: Ogni elemento contiene gli assegnamenti dei nodi per quella partizione

## Esempio pratico

Supponiamo di avere:
- **3 nodi**: `node-1`, `node-2`, `node-3`
- **4 partizioni**: `0`, `1`, `2`, `3`
- **2 repliche** per partizione (replicaFactor = 2)
- **1 nodo sincrono** per partizione (consensusGroupSize = 1)

### Risultato atteso:

```java
List<Set<Assignment>> result = assignPartitions(...);

// Partizione 0
result.get(0) = [
    Assignment.forPeer("node-1"),    // Peer: sincrono
    Assignment.forLearner("node-2")  // Learner: asincrono
]

// Partizione 1
result.get(1) = [
    Assignment.forPeer("node-2"),    // Peer: sincrono
    Assignment.forLearner("node-3")  // Learner: asincrono
]

// Partizione 2
result.get(2) = [
    Assignment.forPeer("node-3"),    // Peer: sincrono
    Assignment.forLearner("node-1")  // Learner: asincrono
]

// Partizione 3
result.get(3) = [
    Assignment.forPeer("node-1"),    // Peer: sincrono
    Assignment.forLearner("node-3")  // Learner: asincrono
]
```

## Cosa rappresenta `Assignment`?

`Assignment` è una classe che rappresenta l'assegnazione di una partizione a un nodo specifico con un ruolo specifico:

```java
public class Assignment {
    private final String consistentId;  // ID del nodo
    private final boolean isPeer;       // true = Peer, false = Learner
}
```

### Due tipi di Assignment:

#### 1. **Peer** (`Assignment.forPeer(nodeId)`)
- **Ruolo**: Membro sincrono del gruppo di replicazione
- **Comportamento**: Riceve aggiornamenti sincronamente durante le operazioni di scrittura
- **Necessario per**: Consenso e coerenza forte
- **Usato per**: I primi `consensusGroupSize` nodi

#### 2. **Learner** (`Assignment.forLearner(nodeId)`)
- **Ruolo**: Membro asincrono del gruppo di replicazione
- **Comportamento**: Riceve aggiornamenti con consistenza eventuale
- **Necessario per**: Ridondanza e disponibilità
- **Usato per**: I nodi dopo `consensusGroupSize`

## Esempio dettagliato con 3 repliche

Supponiamo:
- **replicaFactor = 3** (3 repliche per partizione)
- **consensusGroupSize = 2** (2 nodi sincroni)

```java
// Partizione 0
result.get(0) = [
    Assignment.forPeer("node-1"),      // Peer #1 (sincrono)
    Assignment.forPeer("node-2"),      // Peer #2 (sincrono)
    Assignment.forLearner("node-3")    // Learner (asincrono)
]
```

### Comportamento durante le scritture:
1. **Scrittura alla partizione 0**
   - ✅ `node-1` riceve l'aggiornamento **sincronamente**
   - ✅ `node-2` riceve l'aggiornamento **sincronamente**
   - ⏳ `node-3` riceve l'aggiornamento **asincronamente** (consistenza eventuale)

## Visualizzazione grafica

```
Cluster: [node-1, node-2, node-3]
Partizioni: 4
Repliche: 2
Consensus: 1

Risultato di assignPartitions:

┌─────────────────────────────────────────┐
│         List<Set<Assignment>>          │
├─────────────────────────────────────────┤
│ [0] → Set<Assignment>                  │
│      ├─ Assignment.forPeer("node-1")   │
│      └─ Assignment.forLearner("node-2") │
├─────────────────────────────────────────┤
│ [1] → Set<Assignment>                  │
│      ├─ Assignment.forPeer("node-2")   │
│      └─ Assignment.forLearner("node-3") │
├─────────────────────────────────────────┤
│ [2] → Set<Assignment>                  │
│      ├─ Assignment.forPeer("node-3")   │
│      └─ Assignment.forLearner("node-1") │
├─────────────────────────────────────────┤
│ [3] → Set<Assignment>                  │
│      ├─ Assignment.forPeer("node-1")   │
│      └─ Assignment.forLearner("node-2") │
└─────────────────────────────────────────┘
```

## Come viene usato in Ignite

Il risultato di `assignPartitions` viene utilizzato da:

1. **`PartitionDistributionUtils`** - Calcola gli assegnamenti delle partizioni
2. **`DistributionZoneManager`** - Gestisce la distribuzione delle zone
3. **`PartitionReplicaLifecycleManager`** - Gestisce il ciclo di vita delle repliche
4. **`RaftService`** - Configura i gruppi di consensus per le partizioni

## Algoritmi di distribuzione

Differenti algoritmi calcolano diversamente gli assegnamenti:

### RendezvousDistributionFunction
- Usa **consistent hashing** (HRW - Highest Random Weight)
- **Stateless**: Ogni chiamata calcola da zero
- **Deterministico**: Stessi nodi → stessi assegnamenti

### MementoDistributionFunction / ThreadSafeMementoDistributionFunction
- Usa **consistent hashing** con **memento pattern**
- **Stateful**: Mantiene memoria dei nodi rimossi
- **Minimizza movimento dati**: Quando un nodo viene rimosso, le altre partizioni non cambiano assegnamento
- **Deterministico**: Stessi nodi → stessi assegnamenti

## Esempio di utilizzo nel codice

```java
// Esempio di utilizzo
List<String> nodes = Arrays.asList("node-1", "node-2", "node-3");
int partitions = 100;
int replicas = 3;
int consensusGroupSize = 2;

DistributionAlgorithm algo = new RendezvousDistributionFunction();
List<Set<Assignment>> result = algo.assignPartitions(
    nodes, 
    emptyList(),  // Nessuna distribuzione precedente
    partitions, 
    replicas, 
    consensusGroupSize
);

// Ora puoi accedere alle assegnazioni
for (int partitionId = 0; partitionId < partitions; partitionId++) {
    Set<Assignment> partitionAssignments = result.get(partitionId);
    
    System.out.println("Partizione " + partitionId + " assegnata a:");
    for (Assignment assignment : partitionAssignments) {
        if (assignment.isPeer()) {
            System.out.println("  - Peer: " + assignment.consistentId());
        } else {
            System.out.println("  - Learner: " + assignment.consistentId());
        }
    }
}
```

## Riepilogo

- **`List<Set<Assignment>>`**: Lista di assegnamenti partizione per partizione
- **`Set<Assignment>`**: Insieme di nodi assegnati a una partizione specifica
- **`Assignment`**: Rappresenta un nodo con il suo ruolo (Peer o Learner)
- **`Peer`**: Nodo sincrono che riceve aggiornamenti in tempo reale
- **`Learner`**: Nodo asincrono che riceve aggiornamenti con consistenza eventuale

