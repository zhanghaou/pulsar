/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.common.util.collections;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;
import lombok.Cleanup;
import org.testng.annotations.Test;

public class ConcurrentLongHashMapTest {

    @Test
    public void testConstructor() {
        try {
            ConcurrentLongHashMap.<String>newBuilder()
                    .expectedItems(0)
                    .build();
            fail("should have thrown exception");
        } catch (IllegalArgumentException e) {
            // ok
        }

        try {
            ConcurrentLongHashMap.<String>newBuilder()
                    .expectedItems(16)
                    .concurrencyLevel(0)
                    .build();
            fail("should have thrown exception");
        } catch (IllegalArgumentException e) {
            // ok
        }

        try {
            ConcurrentLongHashMap.<String>newBuilder()
                    .expectedItems(4)
                    .concurrencyLevel(8)
                    .build();
            fail("should have thrown exception");
        } catch (IllegalArgumentException e) {
            // ok
        }
    }

    @Test
    public void simpleInsertions() {
        ConcurrentLongHashMap<String> map = ConcurrentLongHashMap.<String>newBuilder()
                .expectedItems(16)
                .build();

        assertTrue(map.isEmpty());
        assertNull(map.put(1, "one"));
        assertFalse(map.isEmpty());

        assertNull(map.put(2, "two"));
        assertNull(map.put(3, "three"));

        assertEquals(map.size(), 3);

        assertEquals(map.get(1), "one");
        assertEquals(map.size(), 3);

        assertEquals(map.remove(1), "one");
        assertEquals(map.size(), 2);
        assertNull(map.get(1));
        assertNull(map.get(5));
        assertEquals(map.size(), 2);

        assertNull(map.put(1, "one"));
        assertEquals(map.size(), 3);
        assertEquals(map.put(1, "uno"), "one");
        assertEquals(map.size(), 3);
    }

    @Test
    public void testReduceUnnecessaryExpansions() {
        ConcurrentLongHashMap<String> map = ConcurrentLongHashMap.<String>newBuilder()
                .expectedItems(2)
                .concurrencyLevel(1)
                .build();
        assertNull(map.put(1, "v1"));
        assertNull(map.put(2, "v2"));
        assertNull(map.put(3, "v3"));
        assertNull(map.put(4, "v4"));

        assertTrue(map.remove(1, "v1"));
        assertTrue(map.remove(2, "v2"));
        assertTrue(map.remove(3, "v3"));
        assertTrue(map.remove(4, "v4"));

        assertEquals(0, map.getUsedBucketCount());
    }

    @Test
    public void testClear() {
        ConcurrentLongHashMap<String> map = ConcurrentLongHashMap.<String>newBuilder()
                .expectedItems(2)
                .concurrencyLevel(1)
                .autoShrink(true)
                .mapIdleFactor(0.25f)
                .build();
        assertTrue(map.capacity() == 4);

        assertNull(map.put(1, "v1"));
        assertNull(map.put(2, "v2"));
        assertNull(map.put(3, "v3"));

        assertTrue(map.capacity() == 8);
        map.clear();
        assertTrue(map.capacity() == 4);
    }

    @Test
    public void testExpandAndShrink() {
        ConcurrentLongHashMap<String> map = ConcurrentLongHashMap.<String>newBuilder()
                .expectedItems(2)
                .concurrencyLevel(1)
                .autoShrink(true)
                .mapIdleFactor(0.25f)
                .build();
        assertTrue(map.capacity() == 4);

        assertNull(map.put(1, "v1"));
        assertNull(map.put(2, "v2"));
        assertNull(map.put(3, "v3"));

        // expand hashmap
        assertTrue(map.capacity() == 8);

        assertTrue(map.remove(1, "v1"));
        // not shrink
        assertTrue(map.capacity() == 8);
        assertTrue(map.remove(2, "v2"));
        // shrink hashmap
        assertTrue(map.capacity() == 4);

        // expand hashmap
        assertNull(map.put(4, "v4"));
        assertNull(map.put(5, "v5"));
        assertTrue(map.capacity() == 8);

        //verify that the map does not keep shrinking at every remove() operation
        assertNull(map.put(6, "v6"));
        assertTrue(map.remove(6, "v6"));
        assertTrue(map.capacity() == 8);
    }

    @Test
    public void testExpandShrinkAndClear() {
        ConcurrentLongHashMap<String> map = ConcurrentLongHashMap.<String>newBuilder()
                .expectedItems(2)
                .concurrencyLevel(1)
                .autoShrink(true)
                .mapIdleFactor(0.25f)
                .build();
        final long initCapacity = map.capacity();
        assertTrue(map.capacity() == 4);
        assertNull(map.put(1, "v1"));
        assertNull(map.put(2, "v2"));
        assertNull(map.put(3, "v3"));

        // expand hashmap
        assertTrue(map.capacity() == 8);

        assertTrue(map.remove(1, "v1"));
        // not shrink
        assertTrue(map.capacity() == 8);
        assertTrue(map.remove(2, "v2"));
        // shrink hashmap
        assertTrue(map.capacity() == 4);

        assertTrue(map.remove(3, "v3"));
        // Will not shrink the hashmap again because shrink capacity is less than initCapacity
        // current capacity is equal than the initial capacity
        assertTrue(map.capacity() == initCapacity);
        map.clear();
        // after clear, because current capacity is equal than the initial capacity, so not shrinkToInitCapacity
        assertTrue(map.capacity() == initCapacity);
    }

    @Test
    public void testConcurrentExpandAndShrinkAndGet()  throws Throwable {
        ConcurrentLongHashMap<String> map = ConcurrentLongHashMap.<String>newBuilder()
                .expectedItems(2)
                .concurrencyLevel(1)
                .autoShrink(true)
                .mapIdleFactor(0.25f)
                .build();
        assertEquals(map.capacity(), 4);

        @Cleanup("shutdownNow")
        ExecutorService executor = Executors.newCachedThreadPool();
        final int readThreads = 16;
        final int writeThreads = 1;
        final int n = 1_000;
        CyclicBarrier barrier = new CyclicBarrier(writeThreads + readThreads);
        Future<?> future = null;
        AtomicReference<Exception> ex = new AtomicReference<>();

        for (int i = 0; i < readThreads; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                try {
                    map.get(1);
                } catch (Exception e) {
                    ex.set(e);
                }
            });
        }

        assertNull(map.put(1, "v1"));
        future = executor.submit(() -> {
            try {
                barrier.await();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            for (int i = 0; i < n; i++) {
                // expand hashmap
                assertNull(map.put(2, "v2"));
                assertNull(map.put(3, "v3"));
                assertEquals(map.capacity(), 8);

                // shrink hashmap
                assertTrue(map.remove(2, "v2"));
                assertTrue(map.remove(3, "v3"));
                assertEquals(map.capacity(), 4);
            }
        });

        future.get();
        assertTrue(ex.get() == null);
        // shut down pool
        executor.shutdown();
    }

    @Test
    public void testRemove() {
        ConcurrentLongHashMap<String> map = ConcurrentLongHashMap.<String>newBuilder()
                .build();

        assertTrue(map.isEmpty());
        assertNull(map.put(1, "one"));
        assertFalse(map.isEmpty());

        assertFalse(map.remove(0, "zero"));
        assertFalse(map.remove(1, "uno"));

        assertFalse(map.isEmpty());
        assertTrue(map.remove(1, "one"));
        assertTrue(map.isEmpty());
    }

    @Test
    public void testNegativeUsedBucketCount() {
        ConcurrentLongHashMap<String> map = ConcurrentLongHashMap.<String>newBuilder()
                .expectedItems(16)
                .concurrencyLevel(1)
                .build();

        map.put(0, "zero");
        assertEquals(1, map.getUsedBucketCount());
        map.put(0, "zero1");
        assertEquals(1, map.getUsedBucketCount());
        map.remove(0);
        assertEquals(0, map.getUsedBucketCount());
        map.remove(0);
        assertEquals(0, map.getUsedBucketCount());
    }

    @Test
    public void testRehashing() {
        int n = 16;
        ConcurrentLongHashMap<Integer> map = ConcurrentLongHashMap.<Integer>newBuilder()
                .expectedItems(n / 2)
                .concurrencyLevel(1)
                .build();
        assertEquals(map.capacity(), n);
        assertEquals(map.size(), 0);

        for (int i = 0; i < n; i++) {
            map.put(i, i);
        }

        assertEquals(map.capacity(), 2 * n);
        assertEquals(map.size(), n);
    }

    @Test
    public void testRehashingWithDeletes() {
        int n = 16;
        ConcurrentLongHashMap<Integer> map = ConcurrentLongHashMap.<Integer>newBuilder()
                .expectedItems(n / 2)
                .concurrencyLevel(1)
                .build();
        assertEquals(map.capacity(), n);
        assertEquals(map.size(), 0);

        for (int i = 0; i < n / 2; i++) {
            map.put(i, i);
        }

        for (int i = 0; i < n / 2; i++) {
            map.remove(i);
        }

        for (int i = n; i < (2 * n); i++) {
            map.put(i, i);
        }

        assertEquals(map.capacity(), 2 * n);
        assertEquals(map.size(), n);
    }

    @Test
    public void concurrentInsertions() throws Throwable {
        ConcurrentLongHashMap<String> map = ConcurrentLongHashMap.<String>newBuilder()
                .build();
        @Cleanup("shutdownNow")
        ExecutorService executor = Executors.newCachedThreadPool();

        final int nThreads = 16;
        final int num = 100_000;
        String value = "value";

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < nThreads; i++) {
            final int threadIdx = i;

            futures.add(executor.submit(() -> {
                Random random = new Random();

                for (int j = 0; j < num; j++) {
                    long key = random.nextLong();
                    // Ensure keys are uniques
                    key -= key % (threadIdx + 1);

                    map.put(key, value);
                }
            }));
        }

        for (Future<?> future : futures) {
            future.get();
        }

        assertEquals(map.size(), num * nThreads);
    }

    @Test
    public void concurrentInsertionsAndReads() throws Throwable {
        ConcurrentLongHashMap<String> map = ConcurrentLongHashMap.<String>newBuilder()
                .build();
        @Cleanup("shutdownNow")
        ExecutorService executor = Executors.newCachedThreadPool();

        final int nThreads = 16;
        final int num = 100_000;
        String value = "value";

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < nThreads; i++) {
            final int threadIdx = i;

            futures.add(executor.submit(() -> {
                Random random = new Random();

                for (int j = 0; j < num; j++) {
                    long key = random.nextLong();
                    // Ensure keys are uniques
                    key -= key % (threadIdx + 1);

                    map.put(key, value);
                }
            }));
        }

        for (Future<?> future : futures) {
            future.get();
        }

        assertEquals(map.size(), num * nThreads);
    }

    public void stressConcurrentInsertionsAndReads() throws Throwable {
        ConcurrentLongHashMap<String> map = ConcurrentLongHashMap.<String>newBuilder()
                .expectedItems(4)
                .concurrencyLevel(1)
                .build();
        @Cleanup("shutdownNow")
        ExecutorService executor = Executors.newCachedThreadPool();
        final int writeThreads = 16;
        final int readThreads = 16;
        final int n = 1_000_000;
        String value = "value";
        CyclicBarrier barrier = new CyclicBarrier(writeThreads + readThreads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < writeThreads; i++) {
            final int threadIdx = i;
            futures.add(executor.submit(() -> {
                Random random = new Random(threadIdx);
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                for (int j = 0; j < n; j++) {
                    long key = random.nextLong();
                    // Ensure keys are uniques
                    key -= key % (threadIdx + 1);
                    map.put(key, value);
                }
            }));
        }
        for (int i = 0; i < readThreads; i++) {
            final int threadIdx = i;
            futures.add(executor.submit(() -> {
                Random random = new Random(threadIdx);
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                for (int j = 0; j < n; j++) {
                    long key = random.nextLong();
                    // Ensure keys are uniques
                    key -= key % (threadIdx + 1);
                    map.get(key);
                }
            }));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        assertEquals(map.size(), n * writeThreads);
    }

    @Test
    public void testIteration() {
        ConcurrentLongHashMap<String> map = ConcurrentLongHashMap.<String>newBuilder()
                .build();

        assertEquals(map.keys(), Collections.emptyList());
        assertEquals(map.values(), Collections.emptyList());

        map.put(0, "zero");

        assertEquals(map.keys(), Lists.newArrayList(0L));
        assertEquals(map.values(), Lists.newArrayList("zero"));

        map.remove(0);

        assertEquals(map.keys(), Collections.emptyList());
        assertEquals(map.values(), Collections.emptyList());

        map.put(0, "zero");
        map.put(1, "one");
        map.put(2, "two");

        List<Long> keys = map.keys();
        keys.sort(null);
        assertEquals(keys, Lists.newArrayList(0L, 1L, 2L));

        List<String> values = map.values();
        values.sort(null);
        assertEquals(values, Lists.newArrayList("one", "two", "zero"));

        map.put(1, "uno");

        keys = map.keys();
        keys.sort(null);
        assertEquals(keys, Lists.newArrayList(0L, 1L, 2L));

        values = map.values();
        values.sort(null);
        assertEquals(values, Lists.newArrayList("two", "uno", "zero"));

        map.clear();
        assertTrue(map.isEmpty());
    }

    @Test
    public void testHashConflictWithDeletion() {
        final int buckets = 16;
        ConcurrentLongHashMap<String> map = ConcurrentLongHashMap.<String>newBuilder()
                .expectedItems(buckets)
                .concurrencyLevel(1)
                .build();

        // Pick 2 keys that fall into the same bucket
        long key1 = 1;
        long key2 = 27;

        int bucket1 = ConcurrentLongHashMap.signSafeMod(ConcurrentLongHashMap.hash(key1), buckets);
        int bucket2 = ConcurrentLongHashMap.signSafeMod(ConcurrentLongHashMap.hash(key2), buckets);
        assertEquals(bucket1, bucket2);

        assertNull(map.put(key1, "value-1"));
        assertNull(map.put(key2, "value-2"));
        assertEquals(map.size(), 2);

        assertEquals(map.remove(key1), "value-1");
        assertEquals(map.size(), 1);

        assertNull(map.put(key1, "value-1-overwrite"));
        assertEquals(map.size(), 2);

        assertEquals(map.remove(key1), "value-1-overwrite");
        assertEquals(map.size(), 1);

        assertEquals(map.put(key2, "value-2-overwrite"), "value-2");
        assertEquals(map.get(key2), "value-2-overwrite");

        assertEquals(map.size(), 1);
        assertEquals(map.remove(key2), "value-2-overwrite");
        assertTrue(map.isEmpty());
    }

    @Test
    public void testPutIfAbsent() {
        ConcurrentLongHashMap<String> map = ConcurrentLongHashMap.<String>newBuilder()
                .build();
        assertNull(map.putIfAbsent(1, "one"));
        assertEquals(map.get(1), "one");

        assertEquals(map.putIfAbsent(1, "uno"), "one");
        assertEquals(map.get(1), "one");
    }

    @Test
    public void testComputeIfAbsent() {
        ConcurrentLongHashMap<Integer> map = ConcurrentLongHashMap.<Integer>newBuilder()
                .expectedItems(16)
                .concurrencyLevel(1)
                .build();
        AtomicInteger counter = new AtomicInteger();
        LongFunction<Integer> provider = key -> counter.getAndIncrement();

        assertEquals(map.computeIfAbsent(0, provider).intValue(), 0);
        assertEquals(map.get(0).intValue(), 0);

        assertEquals(map.computeIfAbsent(1, provider).intValue(), 1);
        assertEquals(map.get(1).intValue(), 1);

        assertEquals(map.computeIfAbsent(1, provider).intValue(), 1);
        assertEquals(map.get(1).intValue(), 1);

        assertEquals(map.computeIfAbsent(2, provider).intValue(), 2);
        assertEquals(map.get(2).intValue(), 2);
    }

    static final int Iterations = 1;
    static final int ReadIterations = 10000;
    static final int N = 100_000;

    public void benchConcurrentLongHashMap() throws Exception {
        ConcurrentLongHashMap<String> map = ConcurrentLongHashMap.<String>newBuilder()
                .expectedItems(N)
                .concurrencyLevel(1)
                .build();

        for (long i = 0; i < Iterations; i++) {
            for (int j = 0; j < N; j++) {
                map.put(i, "value");
            }

            for (long h = 0; h < ReadIterations; h++) {
                for (int j = 0; j < N; j++) {
                    map.get(i);
                }
            }

            for (int j = 0; j < N; j++) {
                map.remove(i);
            }
        }
    }

    public void benchConcurrentHashMap() throws Exception {
        ConcurrentHashMap<Long, String> map = new ConcurrentHashMap<Long, String>(N, 0.66f, 1);

        for (long i = 0; i < Iterations; i++) {
            for (int j = 0; j < N; j++) {
                map.put(i, "value");
            }

            for (long h = 0; h < ReadIterations; h++) {
                for (int j = 0; j < N; j++) {
                    map.get(i);
                }
            }

            for (int j = 0; j < N; j++) {
                map.remove(i);
            }
        }
    }

    void benchHashMap() throws Exception {
        HashMap<Long, String> map = new HashMap<Long, String>(N, 0.66f);

        for (long i = 0; i < Iterations; i++) {
            for (int j = 0; j < N; j++) {
                map.put(i, "value");
            }

            for (long h = 0; h < ReadIterations; h++) {
                for (int j = 0; j < N; j++) {
                    map.get(i);
                }
            }

            for (int j = 0; j < N; j++) {
                map.remove(i);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        ConcurrentLongHashMapTest t = new ConcurrentLongHashMapTest();

        long start = System.nanoTime();
        t.benchConcurrentLongHashMap();
        long end = System.nanoTime();

        System.out.println("CLHM: " + TimeUnit.NANOSECONDS.toMillis(end - start) + " ms");

        start = System.nanoTime();
        t.benchHashMap();
        end = System.nanoTime();

        System.out.println("HM:   " + TimeUnit.NANOSECONDS.toMillis(end - start) + " ms");

        start = System.nanoTime();
        t.benchConcurrentHashMap();
        end = System.nanoTime();

        System.out.println("CHM:  " + TimeUnit.NANOSECONDS.toMillis(end - start) + " ms");
    }
}
