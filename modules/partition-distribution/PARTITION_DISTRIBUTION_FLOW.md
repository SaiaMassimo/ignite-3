# Partition Distribution Flow in Ignite

## Panoramica del Flusso

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    PARTITION DISTRIBUTION IN IGNITE                         │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. CREAZIONE TABELLA / DISTRIBUTION ZONE                                    │
└─────────────────────────────────────────────────────────────────────────────┘

    User Action: CREATE TABLE ... IN ZONE ...
    │
    ├─► TableManager.createTable()
    │   │
    │   └─► TableAssignmentsService.createAndWriteTableAssignmentsToMetastorage()
    │       │
    │       ├─► DistributionZoneManager.dataNodes()
    │       │   └─► Restituisce nodi disponibili: [node1, node2, node3, ...]
    │       │
    │       └─► PartitionDistributionUtils.calculateAssignments()
    │           │  📍 CHIAMATA ALL'ALGORITMO
    │           │
    │           ├─► DistributionAlgorithm.assignPartitions(
    │           │      nodes=[node1,node2,node3],
    │           │      currentDistribution=[],
    │           │      partitions=25,
    │           │      replicaFactor=3,
    │           │      consensusGroupSize=3
    │           │   )
    │           │
    │           ├─► RendezvousDistributionFunction.assignPartitions()
    │           │   │
    │           │   ├─► Per ogni partizione (0..24):
    │           │   │   │
    │           │   │   └─► assignPartition(partitionId, nodes, replicas, ...)
    │           │   │       │
    │           │   │       ├─► Calcola hash(node, partitionId)
    │           │   │       ├─► Ordina nodi per hash
    │           │   │       ├─► Seleziona primi 3 (replicas)
    │           │   │       └─► Crea [Assignment.forPeer(node1),
    │           │   │                                Assignment.forPeer(node2),
    │           │   │                                Assignment.forPeer(node3)]
    │           │   │
    │           │   └─► Ritorna List<Set<Assignment>> per tutte le partizioni
    │           │
    │           └─► Assignments.of(assignments, timestamp)
    │
    └─► MetaStorage: Scrivi assignments per ogni partizione
        │
        ├─► partition.assignments.stable = [node1, node2, node3]
        └─► partition.assignments.chain = [node1, node2, node3] (HA mode)


┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. REBALANCE (Topology Change: node aggiunto/rimosso)                       │
└─────────────────────────────────────────────────────────────────────────────┘

    Topology Change: node aggiunto/rimosso
    │
    ├─► DistributionZoneManager.onTopologyChange()
    │   │
    │   └─► DistributionZoneRebalanceEngine.rebalance()
    │       │
    │       ├─► Per ogni partizione interessata:
    │       │   │
    │       │   └─► PartitionDistributionUtils.calculateAssignments()
    │       │       │  📍 CHIAMATA ALL'ALGORITMO
    │       │       │
    │       │       ├─► DistributionAlgorithm.assignPartitions(
    │       │       │      nodes=[node1,node2,node3,node4],  ← NUOVO NODO
    │       │       │      currentDistribution=[[node1,node2,node3]],
    │       │       │      partitions=25,
    │       │       │      replicaFactor=3,
    │       │       │      consensusGroupSize=3
    │       │       │   )
    │       │       │
    │       │       ├─► Nuova distribuzione calcolata
    │       │       │   Es: [node2, node3, node4] ← può includere nuovo nodo
    │       │       │
    │       │       └─► Calcola differenza con stable
    │       │
    │       ├─► MetaStorage: Scrivi pending assignments
    │       │   │
    │       │   └─► partition.assignments.pending = [node2, node3, node4]
    │       │
    │       ├─► RAFT: Inizia changePeersAndLearnersAsync()
    │       │
    │       └─► Quando RAFT applica configurazione:
    │           │
    │           └─► RebalanceRaftGroupEventsListener.onNewPeersConfigurationApplied()
    │               │
    │               ├─► MetaStorage: Aggiorna stable
    │               │   └─► partition.assignments.stable = [node2, node3, node4]
    │               │
    │               └─► MetaStorage: Aggiorna chain (HA mode)
    │                   └─► partition.assignments.chain = 
    │                       [node1,node2,node3] -> [node2,node3,node4]


┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. DISASTER RECOVERY (Perdita di nodi)                                      │
└─────────────────────────────────────────────────────────────────────────────┘

    Disaster: Perdita di majority (es: 3 nodi su 7 persi)
    │
    ├─► TableManager.onDisasterRecovery()
    │   │
    │   └─► DistributionZoneRebalanceEngine.resetReplicationGroup()
    │       │
    │       ├─► Phase 1: Calcola nuovi assignments
    │       │   │
    │       │   └─► PartitionDistributionUtils.calculateAssignments()
    │       │       │  📍 CHIAMATA ALL'ALGORITMO
    │       │       │
    │       │       ├─► DistributionAlgorithm.assignPartitions(
    │       │       │      nodes=[node1,node2,node3,node4],  ← solo nodi disponibili
    │       │       │      currentDistribution=[],
    │       │       │      partitions=25,
    │       │       │      replicaFactor=3,
    │       │       │      consensusGroupSize=3
    │       │       │   )
    │       │       │
    │       │       └─► Nuova distribuzione senza nodi persi
    │       │
    │       ├─► MetaStorage: Scrivi pending con flag fromReset=true
    │       │
    │       ├─► RAFT: Force change peers
    │       │
    │       ├─► Phase 2: Quando RAFT applica
    │       │   │
    │       │   └─► RebalanceRaftGroupEventsListener.onNewPeersConfigurationApplied()
    │       │       │
    │       │       ├─► Aggiorna stable
    │       │       │
    │       │       └─► Aggiorna chain (usando replaceLast)
    │       │           └─► partition.assignments.chain = 
    │       │               [old] -> [newOnlyWithAvailableNodes]
    │
    │       └─► Sistema ripristinato


┌─────────────────────────────────────────────────────────────────────────────┐
│ 4. ZONE CREATION / MODIFICATION                                              │
└─────────────────────────────────────────────────────────────────────────────┘

    User Action: CREATE ZONE ... WITH PARTITIONS=50, REPLICAS=3
    │
    ├─► DistributionZoneManager.createZone()
    │   │
    │   └─► PartitionReplicaLifecycleManager.writeZoneAssignments()
    │       │
    │       └─► PartitionDistributionUtils.calculateAssignments()
    │           │  📍 CHIAMATA ALL'ALGORITMO
    │           │
    │           ├─► DistributionAlgorithm.assignPartitions(
    │           │      nodes=[...],
    │           │      currentDistribution=[],
    │           │      partitions=50,          ← da zona
    │           │      replicaFactor=3,        ← da zona
    │           │      consensusGroupSize=3
    │           │   )
    │           │
    │           └─► Calcola assignments per tutte le partizioni della zona


┌─────────────────────────────────────────────────────────────────────────────┐
│ 5. COMPONENTI PRINCIPALI                                                     │
└─────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│ TableAssignmentsService                                                      │
│ ├─ Usato per: Creazione tabelle                                             │
│ ├─ Chiama: PartitionDistributionUtils.calculateAssignments()                │
│ └─ Scrive: MetaStorage stable + chain                                       │
└──────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│ DistributionZoneRebalanceEngine                                              │
│ ├─ Usato per: Rebalance e disaster recovery                                 │
│ ├─ Chiama: PartitionDistributionUtils.calculateAssignments()                │
│ └─ Gestisce: Pending → Stable transitions                                   │
└──────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│ PartitionReplicaLifecycleManager                                            │
│ ├─ Usato per: Gestione lifecycle repliche                                   │
│ ├─ Chiama: PartitionDistributionUtils.calculateAssignments()                │
│ └─ Gestisce: Zone assignments                                                │
└──────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│ PartitionDistributionUtils (WRAPPER)                                         │
│ ├─ Metodo: calculateAssignments()                                           │
│ ├─ Usa: DistributionAlgorithm.assignPartitions()                            │
│ └─ Algoritmo: MementoDistributionFunction.getInstance(1)                    │
└──────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│ DistributionAlgorithm (INTERFACE)                                            │
│ ├─ Metodo: assignPartitions()                                               │
│ ├─ Implementazioni:                                                          │
│ │   ├─ RendezvousDistributionFunction                                       │
│ │   ├─ MementoDistributionFunction                                          │
│ │   └─ ThreadSafeMementoDistributionFunction                                │
│ └─ Output: List<Set<Assignment>>                                             │
└──────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│ Assignment (DATA CLASS)                                                      │
│ ├─ forPeer(): Member sincrono del gruppo RAFT                              │
│ └─ forLearner(): Member asincrono del gruppo RAFT                           │
└──────────────────────────────────────────────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────────────┐
│ 6. FLUSSO COMPLETO DI UNA PARTIZIONE                                         │
└─────────────────────────────────────────────────────────────────────────────┘

CREATE TABLE
    │
    ▼
assignPartitions() calcola [node1, node2, node3] per partition 0
    │
    ▼
MetaStorage.write(stable: [node1, node2, node3])
    │
    ▼
RAFT Group creato con peers [node1, node2, node3]
    │
    ▼
[NODE AGGIUNTO: node4]
    │
    ▼
assignPartitions() ricalcola [node2, node3, node4] per partition 0
    │
    ▼
MetaStorage.write(pending: [node2, node3, node4])
    │
    ▼
RAFT changePeersAndLearnersAsync()
    │
    ├─► node4 si unisce al gruppo
    ├─► node4 sincronizza dati
    └─► Nuova configurazione applicata
    │
    ▼
MetaStorage.write(stable: [node2, node3, node4])
    │
    ▼
node1 fermato (non più in stable)


┌─────────────────────────────────────────────────────────────────────────────┐
│ 7. METASTORAGE KEYS                                                          │
└─────────────────────────────────────────────────────────────────────────────┘

Per ogni partizione (TablePartitionId):

┌──────────────────────────────────────────┐
│ partition.assignments.stable             │
│ ├─ Contiene: Assignments attuali         │
│ └─ Usato da: RAFT clients                │
└──────────────────────────────────────────┘

┌──────────────────────────────────────────┐
│ partition.assignments.pending            │
│ ├─ Contiene: AssignmentsQueue futuri     │
│ └─ Usato da: Rebalance engine            │
└──────────────────────────────────────────┘

┌──────────────────────────────────────────┐
│ partition.assignments.chain              │
│ ├─ Contiene: AssignmentsChain (HA)      │
│ └─ Usato da: Disaster recovery           │
└──────────────────────────────────────────┘

┌──────────────────────────────────────────┐
│ partition.assignments.planned             │
│ ├─ Contiene: Assignments pianificati     │
│ └─ Usato da: Rebalance ottimizzazioni    │
└──────────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────────────┐
│ 8. SUMMARY                                                                   │
└─────────────────────────────────────────────────────────────────────────────┘

assignPartitions() viene chiamato:
├─► 1. TableAssignmentsService.createAndWriteTableAssignmentsToMetastorage()
│   └─► PartitionDistributionUtils.calculateAssignments()
│
├─► 2. DistributionZoneRebalanceEngine.rebalance()
│   └─► PartitionDistributionUtils.calculateAssignments()
│
├─► 3. DistributionZoneRebalanceEngine.resetReplicationGroup()
│   └─► PartitionDistributionUtils.calculateAssignments()
│
└─► 4. PartitionReplicaLifecycleManager.writeZoneAssignments()
    └─► PartitionDistributionUtils.calculateAssignments()

Tutti questi punti chiamano:
    PartitionDistributionUtils.calculateAssignments()
        ↓
    DistributionAlgorithm.assignPartitions()
        ↓
    RendezvousDistributionFunction/MementoDistributionFunction
        ↓
    Calcola hash e assegna nodi alle partizioni
        ↓
    Ritorna List<Set<Assignment>>

Ogni Set<Assignment> contiene:
├─► Assignment.forPeer(nodeId) - nodi sincroni
└─► Assignment.forLearner(nodeId) - nodi asincroni (opzionali)

