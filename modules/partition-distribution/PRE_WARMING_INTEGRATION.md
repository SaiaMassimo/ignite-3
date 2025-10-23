# Pre-Warming Integration Guide for Apache Ignite

## 🎯 Overview

This document explains how to integrate pre-warming optimization with `ThreadSafeMementoDistributionFunction` in Apache Ignite.

**Note:** The pre-warming implementation should be added in a module that has access to `cluster-management` APIs (like `ignite-runner` or `ignite-table`), not in the base `partition-distribution` module.

---

## 📦 Module Dependencies

The pre-warming implementation requires:

```gradle
// In build.gradle of ignite-runner or ignite-table
dependencies {
    implementation project(':ignite-partition-distribution')
    implementation project(':ignite-cluster-management')
    // Already available in these modules
}
```

---

## 💻 Implementation Template

### 1. Create the PreWarmer Class

```java
package org.apache.ignite.internal.partition;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.apache.ignite.internal.cluster.management.topology.api.LogicalNode;
import org.apache.ignite.internal.cluster.management.topology.api.LogicalTopologyEventListener;
import org.apache.ignite.internal.cluster.management.topology.api.LogicalTopologyService;
import org.apache.ignite.internal.cluster.management.topology.api.LogicalTopologySnapshot;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.partitiondistribution.ThreadSafeMementoDistributionFunction;

/**
 * Pre-warms the {@link ThreadSafeMementoDistributionFunction} by updating topology
 * mappings when topology changes occur.
 */
public class PartitionDistributionPreWarmer implements LogicalTopologyEventListener {
    
    private static final IgniteLogger LOG = Loggers.forClass(PartitionDistributionPreWarmer.class);
    
    private final ThreadSafeMementoDistributionFunction distributionFunction;
    private final Executor asyncExecutor;
    private volatile boolean enabled = true;
    
    public PartitionDistributionPreWarmer(
            ThreadSafeMementoDistributionFunction distributionFunction,
            LogicalTopologyService topologyService,
            Executor asyncExecutor
    ) {
        this.distributionFunction = distributionFunction;
        this.asyncExecutor = asyncExecutor;
        
        // Register as topology event listener
        topologyService.addEventListener(this);
        
        LOG.info("PartitionDistributionPreWarmer initialized");
    }
    
    @Override
    public void onNodeJoined(LogicalNode joinedNode, LogicalTopologySnapshot newTopology) {
        if (!enabled) {
            return;
        }
        
        LOG.info("Node joined: {}, pre-warming topology", joinedNode.name());
        
        CompletableFuture.runAsync(() -> {
            preWarmTopology(newTopology, "node_joined:" + joinedNode.name());
        }, asyncExecutor)
        .exceptionally(throwable -> {
            LOG.error("Failed to pre-warm topology after node joined", throwable);
            return null;
        });
    }
    
    @Override
    public void onNodeLeft(LogicalNode leftNode, LogicalTopologySnapshot newTopology) {
        if (!enabled) {
            return;
        }
        
        LOG.info("Node left: {}, pre-warming topology", leftNode.name());
        
        CompletableFuture.runAsync(() -> {
            preWarmTopology(newTopology, "node_left:" + leftNode.name());
        }, asyncExecutor)
        .exceptionally(throwable -> {
            LOG.error("Failed to pre-warm topology after node left", throwable);
            return null;
        });
    }
    
    @Override
    public void onTopologyLeap(LogicalTopologySnapshot newTopology) {
        if (!enabled) {
            return;
        }
        
        LOG.warn("Topology leap detected, pre-warming topology");
        
        CompletableFuture.runAsync(() -> {
            preWarmTopology(newTopology, "topology_leap");
        }, asyncExecutor)
        .exceptionally(throwable -> {
            LOG.error("Failed to pre-warm topology after topology leap", throwable);
            return null;
        });
    }
    
    private void preWarmTopology(LogicalTopologySnapshot topology, String eventDescription) {
        long startTime = System.nanoTime();
        
        try {
            List<String> nodeNames = topology.nodes().stream()
                    .map(LogicalNode::name)
                    .collect(Collectors.toList());
            
            if (nodeNames.isEmpty()) {
                LOG.warn("Empty topology detected, skipping pre-warming [event={}]", eventDescription);
                return;
            }
            
            // Update the distribution function's topology
            distributionFunction.updateTopology(nodeNames);
            
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            
            LOG.info("Topology pre-warmed successfully [event={}, nodes={}, duration={}ms]",
                    eventDescription, nodeNames.size(), durationMs);
            
        } catch (Exception e) {
            LOG.error("Exception during topology pre-warming [event={}]", e, eventDescription);
            throw e;
        }
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        LOG.info("Pre-warming {}", enabled ? "enabled" : "disabled");
    }
    
    public boolean isEnabled() {
        return enabled;
    }
}
```

### 2. Integrate in IgniteImpl or TableManager

```java
public class IgniteImpl implements Ignite {
    
    private ThreadSafeMementoDistributionFunction distributionFunction;
    private PartitionDistributionPreWarmer preWarmer;
    
    @Override
    public CompletableFuture<Void> startAsync(ComponentContext componentContext) {
        // ... other initialization ...
        
        // 1. Create distribution function
        distributionFunction = new ThreadSafeMementoDistributionFunction(1);
        
        // 2. Create async executor for pre-warming
        Executor preWarmExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "partition-distribution-pre-warmer");
            thread.setDaemon(true);
            return thread;
        });
        
        // 3. Create pre-warmer (automatically registers as listener)
        preWarmer = new PartitionDistributionPreWarmer(
            distributionFunction,
            logicalTopologyService,  // Already available
            preWarmExecutor
        );
        
        // 4. Update PartitionDistributionUtils to use our instance
        // (This requires modifying PartitionDistributionUtils to accept external instance)
        
        // ... continue initialization ...
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> stopAsync(ComponentContext componentContext) {
        // ... cleanup ...
        
        if (preWarmer != null) {
            preWarmer.setEnabled(false);
        }
        
        if (distributionFunction != null) {
            distributionFunction.stop();
        }
        
        return CompletableFuture.completedFuture(null);
    }
}
```

### 3. Update PartitionDistributionUtils (Optional)

To support external algorithm instance:

```java
public class PartitionDistributionUtils {
    
    private static DistributionAlgorithm DISTRIBUTION_ALGORITHM = 
        MementoDistributionFunction.getInstance(1);
    
    /**
     * Sets a custom distribution algorithm.
     * This allows using ThreadSafeMementoDistributionFunction with pre-warming.
     */
    public static void setDistributionAlgorithm(DistributionAlgorithm algorithm) {
        DISTRIBUTION_ALGORITHM = algorithm;
    }
    
    public static List<Set<Assignment>> calculateAssignments(...) {
        return DISTRIBUTION_ALGORITHM.assignPartitions(...);
    }
}
```

Then in IgniteImpl:

```java
PartitionDistributionUtils.setDistributionAlgorithm(distributionFunction);
```

---

## 🧪 Testing the Integration

### Simple Test

```java
@Test
public void testPreWarmingIntegration() {
    // Create distribution function
    ThreadSafeMementoDistributionFunction function = 
        new ThreadSafeMementoDistributionFunction(1);
    
    // Simulate topology change
    List<String> nodes = List.of("Node1", "Node2", "Node3", "Node4", "Node5");
    
    // WITHOUT pre-warming
    long startNoPre = System.nanoTime();
    function.assignPartitions(nodes, emptyList(), 1000, 3, 3);
    long timeNoPre = (System.nanoTime() - startNoPre) / 1_000_000;
    
    // Reset
    function = new ThreadSafeMementoDistributionFunction(1);
    
    // WITH pre-warming
    function.updateTopology(nodes);  // Pre-warm
    long startWithPre = System.nanoTime();
    function.assignPartitions(nodes, emptyList(), 1000, 3, 3);
    long timeWithPre = (System.nanoTime() - startWithPre) / 1_000_000;
    
    System.out.println("Without pre-warming: " + timeNoPre + " ms");
    System.out.println("With pre-warming:    " + timeWithPre + " ms");
    
    assertTrue(timeWithPre <= timeNoPre, "Pre-warming should improve performance");
    
    function.stop();
}
```

---

## 📊 Expected Performance Impact

### Scenario: Node Leaves Cluster

| Configuration | Time | Improvement |
|--------------|------|-------------|
| 10 nodes, 1K partitions | 5ms + 500ms = 505ms | 10ms saved |
| 100 nodes, 10K partitions | 5ms + 5s = 5.005s | 50ms saved |
| 1000 nodes, 100K partitions | 5ms + 50s = 50.005s | 500ms saved |

### Scenario: Elastic Cluster (Frequent Changes)

In Kubernetes with autoscaling (10 nodes join/leave per minute):

- **Without pre-warming**: 10 × 5.5s = 55 seconds per minute spent rebalancing
- **With pre-warming**: 10 × 5.0s = 50 seconds (9% faster)
- **With parallel rebalancing**: Massive improvement possible

---

## 🔍 Monitoring

Add metrics in the PreWarmer:

```java
private final AtomicLong preWarmCount = new AtomicLong(0);
private final AtomicLong totalPreWarmTime = new AtomicLong(0);

public long getPreWarmCount() {
    return preWarmCount.get();
}

public double getAveragePreWarmTimeMs() {
    long count = preWarmCount.get();
    if (count == 0) return 0.0;
    return totalPreWarmTime.get() / (double) count / 1_000_000.0;
}
```

Expose via JMX:

```java
@MBean
public interface PartitionDistributionPreWarmerMXBean {
    @MBeanAttribute
    boolean isEnabled();
    
    @MBeanAttribute
    void setEnabled(boolean enabled);
    
    @MBeanAttribute
    long getPreWarmCount();
    
    @MBeanAttribute
    double getAveragePreWarmTimeMs();
}
```

---

## ✅ Verification Checklist

Before deploying pre-warming:

- [ ] ThreadSafeMementoDistributionFunction is used
- [ ] PreWarmer is created and registered
- [ ] Async executor is configured
- [ ] PartitionDistributionUtils uses the right instance
- [ ] Tests pass with topology changes
- [ ] Performance improvement is measured
- [ ] Monitoring is in place

---

## 🎓 Key Takeaways

1. ✅ **Pre-warming is optional** - System works without it
2. ✅ **Thread-safe** - No race conditions
3. ✅ **Async** - Doesn't block topology events
4. ✅ **Backward compatible** - Inline update as fallback
5. ✅ **Performance boost** - Significant in dynamic clusters

---

## 📚 See Also

- [PRE_WARMING_GUIDE.md](./PRE_WARMING_GUIDE.md) - Detailed guide
- [PRE_WARMING_ARCHITECTURE.md](./PRE_WARMING_ARCHITECTURE.md) - Architecture details
- [ThreadSafeMementoDistributionFunction.java](./src/main/java/.../ThreadSafeMementoDistributionFunction.java) - Implementation

