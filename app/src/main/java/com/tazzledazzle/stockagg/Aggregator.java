package com.tazzledazzle.stockagg;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Aggregates concurrent provider streams per symbol into a single cached
 * {@link AggregatedPrice}, and notifies a listener (wired up to the
 * WebSocket broadcaster) on every update.
 *
 * Concurrency shape -- deliberately mirrors the Kotlin/coroutines version
 * so the two are comparable line-for-line:
 *  - one virtual thread PER PROVIDER runs {@link PriceProvider#run}, pushing
 *    ticks into that provider's own {@link BlockingQueue}
 *  - one virtual thread PER PROVIDER polls its queue with a timeout
 *    ({@code queue.poll(timeout, unit)}) -- this is the direct equivalent
 *    of Kotlin's {@code withTimeoutOrNull { channel.receive() }}
 *  - a stalled provider just means that poll returns null for a beat;
 *    nothing else blocks, because every provider has its own pair of
 *    independent virtual threads (the bulkhead, achieved structurally
 *    rather than via a language construct like `supervisorScope`)
 *
 * Virtual threads make running "2 threads per provider" completely fine
 * even with 500 symbols x 3 providers = 3,000 threads -- they're cheap
 * enough that the JVM schedules them onto a small pool of carrier threads.
 */
@Component
public class Aggregator {

    private final List<PriceProvider> providers;
    private final long perTickTimeoutMillis;
    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, AggregatedPrice> cache = new ConcurrentHashMap<>();
    private volatile Consumer<AggregatedPrice> listener = price -> { };

    public Aggregator() {
        this.providers = List.of(
                new PriceProvider("vendor-a", 190.0, null),
                new PriceProvider("vendor-b", 190.5, null),
                // vendor-c stalls every 5th tick for ~5s to exercise the timeout/bulkhead path.
                new PriceProvider("vendor-c", 189.5, 5)
        );
        this.perTickTimeoutMillis = 1_000;
    }

    /** Registers the single downstream listener (the WebSocket broadcaster). */
    public void onUpdate(Consumer<AggregatedPrice> listener) {
        this.listener = listener;
    }

    public AggregatedPrice latest(String symbol) {
        return cache.get(symbol);
    }

    public List<AggregatedPrice> allLatest() {
        return List.copyOf(cache.values());
    }

    /** Launches the ingestion pipeline for a symbol. Runs for the app's lifetime. */
    public void start(String symbol) {
        ConcurrentHashMap<String, Tick> latestByProvider = new ConcurrentHashMap<>();
        for (PriceProvider provider : providers) {
            BlockingQueue<Tick> queue = new ArrayBlockingQueue<>(16);
            virtualExecutor.submit(() -> provider.run(symbol, queue::offer));
            virtualExecutor.submit(() -> pollLoop(symbol, provider.name(), queue, latestByProvider));
        }
    }

    private void pollLoop(
            String symbol,
            String providerName,
            BlockingQueue<Tick> queue,
            ConcurrentHashMap<String, Tick> latestByProvider
    ) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Tick tick = queue.poll(perTickTimeoutMillis, TimeUnit.MILLISECONDS);
                if (tick == null) {
                    continue; // this provider stalled past the SLA this beat; others are unaffected
                }

                latestByProvider.put(providerName, tick);
                List<Tick> samples = List.copyOf(latestByProvider.values());
                double avg = samples.stream().mapToDouble(Tick::price).average().orElse(0.0);

                AggregatedPrice aggregated = new AggregatedPrice(
                        symbol,
                        avg,
                        samples.size(),
                        samples.stream().map(Tick::provider).toList(),
                        System.currentTimeMillis(),
                        0
                );
                cache.put(symbol, aggregated);
                listener.accept(aggregated);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        virtualExecutor.shutdownNow();
    }
}