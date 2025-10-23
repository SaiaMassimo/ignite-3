# PreWarmer Flow - Diagramma ASCII Semplificato

## 🚀 Flusso Completo PreWarmer

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           IGNITE STARTUP                                  │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    IgniteImpl Constructor                                  │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 1. Crea ThreadSafeMementoDistributionFunction                          │ │
│  │    ├─► ConcurrentHashMap<String, Integer> nodeToBucket                 │ │
│  │    ├─► ConcurrentHashMap<Integer, String> bucketToNode                 │ │
│  │    ├─► IgniteSpinBusyLock busyLock                                     │ │
│  │    └─► Memento memento (synchronized)                                 │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                    │                                        │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 2. Crea PartitionDistributionPreWarmer                                  │ │
│  │    ├─► ThreadSafeMementoDistributionFunction                           │ │
│  │    ├─► LogicalTopologyService                                          │ │
│  │    └─► ExecutorService (2 threads)                                     │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                    │                                        │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 3. Configura PartitionDistributionUtils                                │ │
│  │    └─► setPreWarmedAlgorithm(distributionFunction)                     │ │
│  │        └─► ThreadLocal<ThreadSafeMementoDistributionFunction>          │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      joinClusterAsync()                                     │
│                                                                             │
│  awaitSelfInLocalLogicalTopology() ──────────────────────────────────────┐  │
│  ├─► Attende che il nodo sia nella logical topology                      │  │
│  └─► Completa quando il nodo è validato                                  │  │
│                                                                         │  │
│  catalogManager.catalogInitializationFuture() ────────────────────────┤  │
│  ├─► Inizializza il catalog manager                                     │  │
│  └─► Completa quando il catalog è pronto                                │  │
│                                                                         │  │
│  systemViewManager.completeRegistration() ────────────────────────────┤  │
│  ├─► Completa la registrazione delle system view                       │  │
│  └─► Tutti i componenti sono registrati                                │  │
│                                                                         │  │
│  ⭐ partitionDistributionPreWarmer.start() ⭐ ─────────────────────────┤  │
│  ├─► logicalTopologyService.addEventListener(this)                    │  │
│  │   ├─► Registra listener per: onNodeJoined                           │  │
│  │   ├─► Registra listener per: onNodeLeft                             │  │
│  │   └─► Registra listener per: onTopologyLeap                         │  │
│  ├─► updateTopologyAsync() - Aggiornamento iniziale                    │  │
│  │   ├─► logicalTopologyService.localLogicalTopology()                 │  │
│  │   ├─► Estrae nodeNames da LogicalTopologySnapshot                    │  │
│  │   ├─► distributionFunction.updateTopology(nodeNames)                │  │
│  │   │   ├─► busyLock.enterBusy()                                      │  │
│  │   │   ├─► synchronized(this) {                                     │  │
│  │   │   │   ├─► Aggiorna nodeToBucket mapping                        │  │
│  │   │   │   ├─► Aggiorna bucketToNode mapping                         │  │
│  │   │   │   └─► Chiama addBucket()/removeBucket()                     │  │
│  │   │   └─► }                                                          │  │
│  │   │   └─► busyLock.leaveBusy()                                      │  │
│  │   └─► topologyUpdatesCount.incrementAndGet()                        │  │
│  └─► PreWarmer è attivo e ascolta eventi topology                      │  │
│                                                                         │  │
│  lifecycleManager.onStartComplete() ─────────────────────────────────────┘  │
│  └─► Nodo è completamente avviato                                       │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        RUNTIME PHASE                                       │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    TOPOLOGY EVENTS (Background)                             │
│                                                                             │
│  📡 Node Joined Event                                                      │
│  ├─► logicalTopologyService.onNodeJoined(joinedNode, newTopology)          │
│  ├─► partitionDistributionPreWarmer.onNodeJoined(joinedNode, newTopology) │
│  ├─► updateTopologyAsync(newTopology)                                     │
│  │   ├─► CompletableFuture.runAsync(() -> {                               │
│  │   │   ├─► Estrae nodeNames da newTopology.nodes()                      │
│  │   │   ├─► distributionFunction.updateTopology(nodeNames)              │
│  │   │   │   ├─► Rimuove nodi non più presenti                           │
│  │   │   │   ├─► Aggiunge nuovi nodi                                     │
│  │   │   │   └─► Aggiorna mappings                                       │
│  │   │   └─► topologyUpdatesCount.incrementAndGet()                      │
│  │   └─► }, preWarmerExecutor)                                           │
│  └─► Topology mapping aggiornato in background                           │
│                                                                             │
│  📡 Node Left Event                                                        │
│  ├─► Stesso flusso di Node Joined                                         │
│  └─► Topology mapping aggiornato                                          │
│                                                                             │
│  📡 Topology Leap Event                                                    │
│  ├─► Stesso flusso di Node Joined                                         │
│  └─► Topology mapping completamente rinnovato                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                ASSIGNMENT CALCULATIONS (On-Demand)                         │
│                                                                             │
│  Componenti chiamano:                                                      │
│  ├─► TableAssignmentsService.createAndWriteTableAssignmentsToMetastorage() │
│  ├─► DistributionZoneRebalanceEngine.rebalance()                           │
│  ├─► PartitionReplicaLifecycleManager.writeZoneAssignments()               │
│  └─► Altri componenti che calcolano assignments                            │
│                                    │                                        │
│                                    ▼                                        │
│  PartitionDistributionUtils.calculateAssignments(dataNodes, partitions, replicas) │
│  ├─► getDistributionAlgorithm()                                            │
│  │   ├─► ThreadSafeMementoDistributionFunction preWarmed = PRE_WARMED_ALGORITHM.get() │
│  │   ├─► if (preWarmed != null) return preWarmed;                         │
│  │   └─► else return STATIC_DISTRIBUTION_ALGORITHM;                        │
│  │                                                                         │
│  ├─► algorithm.assignPartitions(dataNodes, emptyList(), partitions, replicas, replicas) │
│  │                                                                         │
│  ├─► CASO 1: ThreadSafeMementoDistributionFunction (Pre-warmed) ⭐      │
│  │   ├─► busyLock.enterBusy()                                             │
│  │   ├─► Set<String> currentNodes = nodeToBucket.keySet()                 │
│  │   ├─► Set<String> newNodes = new HashSet<>(dataNodes)                  │
│  │   ├─► if (!currentNodes.equals(newNodes)) {                            │
│  │   │   └─► updateTopologyInternal(dataNodes) - Aggiornamento inline    │
│  │   └─► }                                                                │
│  │   ├─► Calcola assignments usando topology già aggiornata               │
│  │   ├─► Per ogni partizione:                                             │
│  │   │   ├─► getBucket("partition-" + part)                               │
│  │   │   │   ├─► synchronized(this) {                                    │
│   │   │   │   │   ├─► binomialEngine.getBucket(key)                      │
│   │   │   │   │   ├─► memento.replacer(b)                                │
│   │   │   │   │   └─► Calcola bucket finale                               │
│   │   │   │   └─► }                                                       │
│   │   │   ├─► bucketToNode.get(bucket)                                    │
│   │   │   └─► Crea Assignment.forPeer(node)                               │
│   │   └─► Ritorna List<Set<Assignment>>                                   │
│   │   └─► busyLock.leaveBusy()                                           │
│   │                                                                       │
│   └─► CASO 2: MementoDistributionFunction (Static)                        │
│       ├─► Calcola assignments senza pre-warming                          │
│       └─► Performance inferiore ma compatibilità garantita               │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          SHUTDOWN PHASE                                    │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            stopAsync()                                      │
│                                                                             │
│  stopGuard.compareAndSet(false, true)                                      │
│  lifecycleExecutor = stopExecutor()                                       │
│                                                                             │
│  ⭐ partitionDistributionPreWarmer.stop() ⭐                               │
│  ├─► logicalTopologyService.removeEventListener(this)                     │
│  │   ├─► Rimuove listener da topology events                               │
│  │   └─► Non riceve più eventi                                             │
│  ├─► distributionFunction.stop()                                           │
│  │   ├─► busyLock.block()                                                  │
│  │   └─► Blocca tutte le future operazioni                                │
│  └─► PreWarmer è fermato                                                   │
│                                                                             │
│  PartitionDistributionUtils.clearPreWarmedAlgorithm()                     │
│  ├─► PRE_WARMED_ALGORITHM.remove()                                        │
│  └─► Pulisce thread-local per tutti i thread                              │
│                                                                             │
│  lifecycleManager.stopNode(componentContext)                               │
│  ├─► Ferma tutti gli altri componenti                                      │
│  └─► Completa shutdown del nodo                                           │
│                                                                             │
│  lifecycleExecutor.shutdownNow()                                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 🎯 Vantaggi Chiave

### ⚡ Performance
- **Pre-warming**: Topology mapping aggiornato in background
- **Zero-latency**: `assignPartitions()` usa mapping già pronto
- **Concurrent**: Aggiornamenti asincroni non bloccano calcoli

### 🔄 Compatibilità
- **Thread-local**: Isolamento tra thread diversi
- **Fallback**: Algoritmo statico quando pre-warmed non disponibile
- **Backward-compatible**: Nessuna modifica richiesta ai componenti esistenti

### 🛡️ Thread-Safety
- **ConcurrentHashMap**: Accesso thread-safe alle mappe
- **IgniteSpinBusyLock**: Gestione lifecycle thread-safe
- **synchronized**: Protezione accesso a Memento non thread-safe

### 🔧 Lifecycle Management
- **Start**: Integrato nel flusso di avvio di IgniteImpl
- **Stop**: Pulizia completa durante shutdown
- **Event-driven**: Reattivo ai cambiamenti di topology
