package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The tiles' parallel loading (CategoryHome.reload): same results, same order, errors as before. */
public class ParallelOrderedTest {

    private static List<Integer> upTo(int n) {
        List<Integer> in = new ArrayList<>();
        for (int i = 0; i < n; i++) in.add(i);
        return in;
    }

    @Test
    public void resultsComeBackInTheInputsOrder() {
        List<Integer> in = upTo(40);
        // Later items finish first: the order must still be the input's.
        List<String> out = ParallelOrdered.map(in, i -> {
            sleep(40 - i);
            return "t" + i;
        }, 4);
        assertEquals(40, out.size());
        for (int i = 0; i < 40; i++) assertEquals("t" + i, out.get(i));
    }

    @Test
    public void emptyAndSingleInputs() {
        assertEquals(Collections.emptyList(), ParallelOrdered.map(Collections.<Integer>emptyList(), i -> i, 4));
        assertEquals(Collections.singletonList(7), ParallelOrdered.map(Collections.singletonList(7), i -> i, 4));
    }

    @Test
    public void theCallerWorksTooSoOneThreadIsEnough() {
        String caller = Thread.currentThread().getName();
        List<String> where = ParallelOrdered.map(upTo(5), i -> Thread.currentThread().getName(), 1);
        for (String w : where) assertEquals(caller, w);
    }

    @Test
    public void itemsRunAtTheSameTime() throws Exception {
        // Three items that each wait for the other two: only parallel workers get past the latch.
        CountDownLatch all = new CountDownLatch(3);
        List<Boolean> met = ParallelOrdered.map(upTo(3), i -> {
            all.countDown();
            try {
                return all.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }, 3);
        assertEquals(java.util.Arrays.asList(true, true, true), met);
    }

    @Test
    public void everyItemRunsExactlyOnce() {
        Set<Integer> seen = ConcurrentHashMap.newKeySet();
        List<Integer> in = upTo(100);
        ParallelOrdered.map(in, i -> {
            assertTrue("ran twice: " + i, seen.add(i));
            return i;
        }, 4);
        assertEquals(100, seen.size());
    }

    @Test
    public void aFailureReachesTheCallerAsItWould() {
        IllegalStateException boom = new IllegalStateException("boom");
        try {
            ParallelOrdered.map(upTo(10), i -> {
                if (i == 6) throw boom;
                return i;
            }, 4);
            fail("no exception");
        } catch (IllegalStateException e) {
            assertSame(boom, e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
