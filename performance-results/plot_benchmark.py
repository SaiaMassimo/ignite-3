import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
from pathlib import Path

# Configurazione del plotting
plt.style.use('default')
plt.rcParams['figure.figsize'] = (12, 8)
plt.rcParams['font.size'] = 10

# Directory dei risultati
results_dir = Path("./")


def create_comparison_charts(csv_file, test_type):
    """Crea grafici a colonna per confrontare Rendezvous vs Memento (con pre-warming)"""
    if not csv_file.exists():
        print(f"File {csv_file} non trovato!")
        return

    # Carica i dati
    df = pd.read_csv(csv_file)
    print(f"\n📊 Analizzando {csv_file.name}")
    print(f"   Righe: {len(df)}")

    # Crea la figura con 2 subplots
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 6))
    fig.suptitle(f'Performance Comparison: {test_type}', fontsize=16, fontweight='bold')

    # 1. Grafico: Performance vs Numero di Nodi
    ax1.set_title('Performance vs Numero di Nodi', fontweight='bold')

    # Raggruppa per numero di nodi e calcola la media
    nodes_performance = df.groupby('Nodes')[
        ['RendezvousTime_ms', 'ThreadSafeWithPreWarm_ms']].mean()

    x_pos = np.arange(len(nodes_performance.index))
    width = 0.35

    # Barre per Rendezvous
    bars1 = ax1.bar(x_pos - width/2, nodes_performance['RendezvousTime_ms'], width,
                    label='Rendezvous', color='skyblue', alpha=0.8, edgecolor='black')

    # Barre per Memento (con pre-warming)
    bars2 = ax1.bar(x_pos + width/2, nodes_performance['ThreadSafeWithPreWarm_ms'], width,
                    label='Memento', color='lightgreen', alpha=0.8, edgecolor='black')

    ax1.set_xlabel('Numero di Nodi')
    ax1.set_ylabel('Tempo Medio (ms)')
    ax1.set_xticks(x_pos)
    ax1.set_xticklabels(nodes_performance.index)
    ax1.legend()
    ax1.grid(True, alpha=0.3, axis='y')

    # Aggiungi valori sulle barre
    for bars in [bars1, bars2]:
        for bar in bars:
            height = bar.get_height()
            ax1.text(bar.get_x() + bar.get_width() / 2., height + height * 0.01,
                     f'{height:.0f}', ha='center', va='bottom', fontsize=8)

    # 2. Grafico: Performance vs Numero di Partizioni
    ax2.set_title('Performance vs Numero di Partizioni', fontweight='bold')

    # Raggruppa per numero di partizioni e calcola la media
    partitions_performance = df.groupby('Partitions')[
        ['RendezvousTime_ms', 'ThreadSafeWithPreWarm_ms']].mean()

    x_pos2 = np.arange(len(partitions_performance.index))

    # Barre per Rendezvous
    bars1 = ax2.bar(x_pos2 - width/2, partitions_performance['RendezvousTime_ms'], width,
                    label='Rendezvous', color='skyblue', alpha=0.8, edgecolor='black')

    # Barre per Memento (con pre-warming)
    bars2 = ax2.bar(x_pos2 + width/2, partitions_performance['ThreadSafeWithPreWarm_ms'], width,
                    label='Memento', color='lightgreen', alpha=0.8, edgecolor='black')

    ax2.set_xlabel('Numero di Partizioni')
    ax2.set_ylabel('Tempo Medio (ms)')
    ax2.set_xticks(x_pos2)
    ax2.set_xticklabels(partitions_performance.index)
    ax2.legend()
    ax2.grid(True, alpha=0.3, axis='y')

    # Aggiungi valori sulle barre
    for bars in [bars1, bars2]:
        for bar in bars:
            height = bar.get_height()
            ax2.text(bar.get_x() + bar.get_width() / 2., height + height * 0.01,
                     f'{height:.0f}', ha='center', va='bottom', fontsize=8)

    plt.tight_layout()

    # Salva il grafico
    output_file = results_dir / f"comparison_{test_type.lower().replace(' ', '_')}.png"
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"   📈 Grafico salvato: {output_file}")

    plt.show()

    # Statistiche riassuntive
    print(f"\n📈 Statistiche {test_type}:")

    # Trova il migliore per nodi
    best_by_nodes = nodes_performance.min(axis=1)
    print(f"   🏆 Miglior tempo per nodi:")
    for nodes, best_time in best_by_nodes.items():
        best_algo = nodes_performance.loc[nodes].idxmin()
        algo_name = {'RendezvousTime_ms': 'Rendezvous',
                     'ThreadSafeWithPreWarm_ms': 'Memento'}[best_algo]
        print(f"      {nodes} nodi: {algo_name} ({best_time:.0f} ms)")

    # Trova il migliore per partizioni
    best_by_partitions = partitions_performance.min(axis=1)
    print(f"   🏆 Miglior tempo per partizioni:")
    for partitions, best_time in best_by_partitions.items():
        best_algo = partitions_performance.loc[partitions].idxmin()
        algo_name = {'RendezvousTime_ms': 'Rendezvous',
                     'ThreadSafeWithPreWarm_ms': 'Memento'}[best_algo]
        print(f"      {partitions} partizioni: {algo_name} ({best_time:.0f} ms)")


def main():
    """Funzione principale"""
    print("🚀 Apache Ignite Performance Comparison")
    print("=" * 50)

    # Verifica che la directory esista
    if not results_dir.exists():
        print(f"❌ Directory {results_dir} non trovata!")
        return

    # Trova i file CSV specifici
    few_replicas_files = list(results_dir.glob("performance_fewreplicas_*.csv"))
    full_replicas_files = list(results_dir.glob("performance_fullreplicas_*.csv"))

    # Analizza Few Replicas
    if few_replicas_files:
        latest_few = max(few_replicas_files, key=lambda x: x.stat().st_mtime)
        print(f"\n📊 Analizzando Few Replicas: {latest_few.name}")
        create_comparison_charts(latest_few, "Few Replicas")
    else:
        print("❌ Nessun file Few Replicas trovato!")

    # Analizza Full Replicas
    if full_replicas_files:
        latest_full = max(full_replicas_files, key=lambda x: x.stat().st_mtime)
        print(f"\n📊 Analizzando Full Replicas: {latest_full.name}")
        create_comparison_charts(latest_full, "Full Replicas")
    else:
        print("❌ Nessun file Full Replicas trovato!")

    print("\n✅ Analisi completata!")


if __name__ == "__main__":
    main()