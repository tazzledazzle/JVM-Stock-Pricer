package com.tazzledazzle.stockagg;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Simulates an upstream price provider. {@link #run} is a blocking loop
 * meant to be submitted to a virtual-thread executor -- the blocking
 * {@code Thread.sleep} is the whole point: virtual threads make "just
 * write blocking code" viable at thousands-of-tasks scale, since the JVM
 * parks the virtual thread (cheaply) instead of pinning an OS thread.
 */
public class PriceProvider {

    private final String name;
    private final double basePrice;
    private final Integer flakyEveryNTicks; // null = never stalls

    public PriceProvider(String name, double basePrice, Integer flakyEveryNTicks) {
        this.name = name;
        this.basePrice = basePrice;
        this.flakyEveryNTicks = flakyEveryNTicks;
    }

    public String name() {
        return name;
    }

    /** Blocking loop. Intended to run on its own virtual thread. */
    public void run(String symbol, Consumer<Tick> onTick) {
        double price = basePrice;
        int tickCount = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                tickCount++;

                boolean flakyTick = flakyEveryNTicks != null && tickCount % flakyEveryNTicks == 0;
                long delayMillis = flakyTick ? 5_000L : ThreadLocalRandom.current().nextLong(200, 800);
                Thread.sleep(delayMillis);

                price += ThreadLocalRandom.current().nextDouble(-0.5, 0.5);
                price = Math.max(price, 0.01);

                onTick.accept(new Tick(symbol, name, price, System.currentTimeMillis()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}