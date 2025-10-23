# PreWarmer Flow Diagram - Flusso Completo

## 🚀 Schema di Flusso del PreWarmer Integrato

### 1. INIZIALIZZAZIONE (IgniteImpl Constructor)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          IgniteImpl Constructor                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. logicalTopologyService = new LogicalTopologyServiceImpl(...)          │
│                                                                             │
│  2. distributionFunction = new ThreadSafeMementoDistributionFunction(1)    │
│     ├─► ConcurrentHashMap<String, Integer> nodeToBucket                    │
│     ├─► ConcurrentHashMap<Integer, String> bucketToNode                    │
│     ├─► IgniteSpinBusyLock busyLock                                        │
│     └─► Memento memento (thread-safe con synchronized)                     │
│                                                                             │
│  3. preWarmerExecutor = Executors.newFixedThreadPool(2, ...)               │
│                                                                             │
│  4. partitionDistributionPreWarmer = new PartitionDistributionPreWarmer(   │
│         distributionFunction,                                               │
│         logicalTopologyService,                                            │
│         preWarmerExecutor                                                   │
│     )                                                                       │
│                                                                             │
│  5. PartitionDistributionUtils.setPreWarmedAlgorithm(distributionFunction) │
│     ├─► ThreadLocal<ThreadSafeMementoDistributionFunction> PRE_WARMED_ALGORITHM │
│     └─► Configura algoritmo pre-warmed per il thread corrente              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2. AVVIO NODO (IgniteImpl.joinClusterAsync)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        joinClusterAsync() Flow                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  awaitSelfInLocalLogicalTopology()                                          │
│  ├─► Attende che il nodo sia nella logical topology                        │
│  └─► Completa quando il nodo è validato e aggiunto                         │
│                                                                             │
│  catalogManager.catalogInitializationFuture()                              │
│  ├─► Inizializza il catalog manager                                        │
│  └─► Completa quando il catalog è pronto                                   │
│                                                                             │
│  systemViewManager.completeRegistration()                                  │
│  ├─► Completa la registrazione delle system view                          │
│  └─► Tutti i componenti sono registrati                                    │
│                                                                             │
│  ⭐ partitionDistributionPreWarmer.start() ⭐                               │
│  ├─► logicalTopologyService.addEventListener(this)                        │
│  ├─► Registra listener per: onNodeJoined, onNodeLeft, onTopologyLeap      │
│  ├─► updateTopologyAsync() - Aggiornamento iniziale                       │
│  │   ├─► logicalTopologyService.localLogicalTopology()                     │
│  │   ├─► Estrae nodeNames da LogicalTopologySnapshot                      │
│  │   ├─► distributionFunction.updateTopology(nodeNames)                   │
│  │   └─► Aggiorna nodeToBucket e bucketToNode mappings                    │
│  └─► PreWarmer è attivo e ascolta eventi topology                        │
│                                                                             │
│  lifecycleManager.onStartComplete()                                       │
│  └─► Nodo è completamente avviato                                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3. EVENTI TOPOLOGY (PreWarmer Event Handling)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Topology Event Handling                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  📡 Evento: Node Joined                                                    │
│  ├─► logicalTopologyService.onNodeJoined(joinedNode, newTopology)          │
│  ├─► partitionDistributionPreWarmer.onNodeJoined(joinedNode, newTopology)  │
│  ├─► updateTopologyAsync(newTopology)                                      │
│  │   ├─► CompletableFuture.runAsync(() -> {                               │
│  │   │   ├─► Estrae nodeNames da newTopology.nodes()                      │
│  │   │   ├─► distributionFunction.updateTopology(nodeNames)              │
│  │   │   │   ├─► busyLock.enterBusy()                                     │
│  │   │   │   ├─► synchronized(this) {                                     │
│  │   │   │   │   ├─► Rimuove nodi non più presenti                        │
│  │   │   │   │   ├─► Aggiunge nuovi nodi                                  │
│  │   │   │   │   ├─► Aggiorna nodeToBucket e bucketToNode                 │
│  │   │   │   │   └─► Chiama addBucket()/removeBucket()                   │
│  │   │   │   └─► }                                                        │
│  │   │   │   └─► busyLock.leaveBusy()                                     │
│  │   │   └─► topologyUpdatesCount.incrementAndGet()                     │
│  │   └─► }, preWarmerExecutor)                                            │
│  └─► Topology mapping aggiornato in background                            │
│                                                                             │
│  📡 Evento: Node Left                                                      │
│  ├─► Stesso flusso di onNodeJoined                                        │
│  └─► Topology mapping aggiornato                                          │
│                                                                             │
│  📡 Evento: Topology Leap                                                   │
│  ├─► Stesso flusso di onNodeJoined                                        │
│  └─► Topology mapping completamente rinnovato                             │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4. CALCOLO ASSIGNMENTS (PartitionDistributionUtils)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Partition Assignment Calculation                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Componente chiama:                                                        │
│  ├─► TableAssignmentsService.createAndWriteTableAssignmentsToMetastorage() │
│  ├─► DistributionZoneRebalanceEngine.rebalance()                           │
│  ├─► PartitionReplicaLifecycleManager.writeZoneAssignments()               │
│  └─► Altri componenti che calcolano assignments                            │
│                                                                             │
│  ↓                                                                          │
│                                                                             │
│  PartitionDistributionUtils.calculateAssignments(dataNodes, partitions, replicas) │
│  ├─► getDistributionAlgorithm()                                            │
│  │   ├─► ThreadSafeMementoDistributionFunction preWarmed = PRE_WARMED_ALGORITHM.get() │
│  │   ├─► if (preWarmed != null) return preWarmed;                         │
│  │   └─► else return STATIC_DISTRIBUTION_ALGORITHM;                        │
│  │                                                                         │
│  ├─► algorithm.assignPartitions(dataNodes, emptyList(), partitions, replicas, replicas) │
│  │                                                                         │
│  ├─► CASO 1: ThreadSafeMementoDistributionFunction (Pre-warmed)          │
│  │   ├─► busyLock.enterBusy()                                             │
│  │   ├─► Set<String> currentNodes = nodeToBucket.keySet()                 │
│  │   ├─► Set<String> newNodes = new HashSet<>(dataNodes)                  │
│  │   ├─► if (!currentNodes.equals(newNodes)) {                            │
│  │   │   └─► updateTopologyInternal(dataNodes) - Aggiornamento inline    │
│  │   │       ├─► synchronized(this) {                                      │
│  │   │       │   ├─► Rimuove nodi non più presenti                        │
│  │   │       │   ├─► Aggiunge nuovi nodi                                  │
│  │   │       │   └─► Aggiorna mappings                                    │
│  │   │       └─► }                                                        │
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
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5. STOP NODO (IgniteImpl.stopAsync)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            Node Stop Flow                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  stopAsync()                                                                │
│  ├─► stopGuard.compareAndSet(false, true)                                  │
│  ├─► lifecycleExecutor = stopExecutor()                                    │
│  │                                                                         │
│  ├─► ⭐ partitionDistributionPreWarmer.stop() ⭐                           │
│  │   ├─► logicalTopologyService.removeEventListener(this)                  │
│  │   ├─► Rimuove listener da topology events                               │
│  │   ├─► distributionFunction.stop()                                       │
│  │   │   ├─► busyLock.block()                                             │
│  │   │   └─► Blocca tutte le future operazioni                            │
│  │   └─► PreWarmer è fermato                                               │
│  │                                                                         │
│  ├─► PartitionDistributionUtils.clearPreWarmedAlgorithm()                 │
│  │   ├─► PRE_WARMED_ALGORITHM.remove()                                    │
│  │   └─► Pulisce thread-local per tutti i thread                          │
│  │                                                                         │
│  ├─► lifecycleManager.stopNode(componentContext)                          │
│  │   ├─► Ferma tutti gli altri componenti                                 │
│  │   └─► Completa shutdown del nodo                                       │
│  │                                                                         │
│  └─► lifecycleExecutor.shutdownNow()                                       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 🔄 Flusso Completo - Vista d'Insieme

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           COMPLETE FLOW OVERVIEW                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  STARTUP PHASE:                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ IgniteImpl Constructor                                                 │ │
│  │ ├─► Crea ThreadSafeMementoDistributionFunction                         │ │
│  │ ├─► Crea PartitionDistributionPreWarmer                                │ │
│  │ └─► Configura PartitionDistributionUtils                               │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                    ↓                                        │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ joinClusterAsync()                                                      │ │
│  │ ├─► awaitSelfInLocalLogicalTopology()                                   │ │
│  │ ├─► catalogManager.catalogInitializationFuture()                       │ │
│  │ ├─► systemViewManager.completeRegistration()                           │ │
│  │ ├─► partitionDistributionPreWarmer.start() ⭐                         │ │
│  │ │   ├─► Registra topology event listener                               │ │
│  │ │   └─► Aggiornamento iniziale topology                                │ │
│  │ └─► lifecycleManager.onStartComplete()                                 │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                    ↓                                        │
│  RUNTIME PHASE:                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ Topology Events (Background)                                           │ │
│  │ ├─► Node Joined → PreWarmer.onNodeJoined()                             │ │
│  │ ├─► Node Left → PreWarmer.onNodeLeft()                                 │ │
│  │ ├─► Topology Leap → PreWarmer.onTopologyLeap()                         │ │
│  │ └─► Tutti aggiornano distributionFunction.updateTopology()             │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                    ↓                                        │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ Assignment Calculations (On-Demand)                                     │ │
│  │ ├─► Componenti chiamano PartitionDistributionUtils.calculateAssignments() │ │
│  │ ├─► getDistributionAlgorithm()                                          │ │
│  │ │   ├─► ThreadLocal: ThreadSafeMementoDistributionFunction (Pre-warmed) │ │
│  │ │   └─► Fallback: MementoDistributionFunction (Static)                 │ │
│  │ ├─► Pre-warmed: Topology già aggiornata → Performance ottimizzata       │ │
│  │ └─► Static: Topology calcolata inline → Compatibilità garantita        │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                    ↓                                        │
│  SHUTDOWN PHASE:                                                            │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ stopAsync()                                                             │ │
│  │ ├─► partitionDistributionPreWarmer.stop() ⭐                           │ │
│  │ │   ├─► Rimuove topology event listener                                │ │
│  │ │   └─► distributionFunction.stop()                                    │ │
│  │ ├─► PartitionDistributionUtils.clearPreWarmedAlgorithm()              │ │
│  │ ├─► lifecycleManager.stopNode()                                        │ │
│  │ └─► Nodo completamente fermato                                         │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 🎯 Vantaggi del PreWarmer

### Performance
- **Pre-warming**: Topology mapping aggiornato in background
- **Zero-latency**: `assignPartitions()` usa mapping già pronto
- **Concurrent**: Aggiornamenti asincroni non bloccano calcoli

### Compatibilità
- **Thread-local**: Isolamento tra thread diversi
- **Fallback**: Algoritmo statico quando pre-warmed non disponibile
- **Backward-compatible**: Nessuna modifica richiesta ai componenti esistenti

### Thread-Safety
- **ConcurrentHashMap**: Accesso thread-safe alle mappe
- **IgniteSpinBusyLock**: Gestione lifecycle thread-safe
- **synchronized**: Protezione accesso a Memento non thread-safe

### Lifecycle Management
- **Start**: Integrato nel flusso di avvio di IgniteImpl
- **Stop**: Pulizia completa durante shutdown
- **Event-driven**: Reattivo ai cambiamenti di topology
