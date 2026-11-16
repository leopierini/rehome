package io.github.leopierini.rehome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * A list mapped on a few threads, the results in the input's order, the caller waiting and working too: the Home's
 * tiles (label, banner, glow colour per app) loaded in parallel (Task 7). Each app costs 2-25 ms of package-manager
 * and resource reads, about 185 ms for the 25 apps on the Philips at every cold start, all on the main thread; spread
 * over the TV's four cores it takes a fraction of that, and the rows come out exactly as before.
 *
 * <p>The helpers live in a small pool that lets its threads go after a few idle seconds: nothing stays running.
 */
final class ParallelOrdered {

    private ParallelOrdered() {}

    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(3, 3, 5, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "Rehome.parallel");
                t.setDaemon(true);
                return t;
            });

    static {
        POOL.allowCoreThreadTimeOut(true);
    }

    /**
     * {@code fn} over every item, on up to {@code threads} threads counting the caller, the results in the items'
     * order. A RuntimeException or Error from {@code fn} is thrown here once every started item has finished, as a
     * plain loop would have thrown it.
     */
    static <T, R> List<R> map(List<T> items, Function<T, R> fn, int threads) {
        int n = items.size();
        if (n == 0) return Collections.emptyList();
        Object[] out = new Object[n];
        AtomicInteger next = new AtomicInteger();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Runnable work = () -> {
            int i;
            while (failed.get() == null && (i = next.getAndIncrement()) < n) {
                try {
                    out[i] = fn.apply(items.get(i));
                } catch (RuntimeException | Error e) {
                    failed.compareAndSet(null, e);
                }
            }
        };
        int helpers = Math.max(0, Math.min(threads, n) - 1);
        CountDownLatch done = new CountDownLatch(helpers);
        for (int h = 0; h < helpers; h++) {
            POOL.execute(() -> {
                try {
                    work.run();
                } finally {
                    done.countDown();
                }
            });
        }
        work.run();
        boolean interrupted = false;
        while (true) {
            try {
                done.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        Throwable t = failed.get();
        if (t instanceof RuntimeException) throw (RuntimeException) t;
        if (t instanceof Error) throw (Error) t;
        @SuppressWarnings("unchecked")
        List<R> result = (List<R>) new ArrayList<>(Arrays.asList(out));
        return result;
    }
}
