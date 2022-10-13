package org.example;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ZookeeperLockIntegrationTest {
    private ZooKeeperServer zookeeper;
    private int zkPort;

    @BeforeEach
    public void setup() throws IOException, InterruptedException {
        NIOServerCnxnFactory cnxnFactory = new NIOServerCnxnFactory();
        cnxnFactory.configure(new InetSocketAddress("127.0.0.1", 0), 0);

        zookeeper = new ZooKeeperServer(new File("snap-dir"), new File("log-dir"), 800);

        cnxnFactory.startup(zookeeper);

        zkPort = zookeeper.getClientPort();
    }

    @AfterEach
    public void tearDown() {
        zookeeper.shutdown(true);
    }

    @Test
    public void singleProcessCheck() throws Exception {
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + zookeeper.getClientPort(), 800, null);

        long[] count = new long[1];
        AtomicLong atomicCount = new AtomicLong();

        try (ZookeeperLock zkLock = new ZookeeperLock(zk, "/_test_lock")) {
            long tEnd = System.currentTimeMillis() + 5_000;
            runMultiThreaded(() -> {
                while (System.currentTimeMillis() < tEnd) {
                    zkLock.lock();
                    try {
                        count[0]++;
                        atomicCount.incrementAndGet();
                    } finally {
                        zkLock.unlock();
                    }
                }

                return 0;
            }, 20);
        }

        System.out.println("+++ Count: " + atomicCount.get());
        assertEquals(atomicCount.get(), count[0]);
    }

    public static void runMultiThreaded(Callable<?> call, int threads) throws Exception {
        runMultiThreaded(IntStream.range(0, threads).mapToObj(i -> call).collect(Collectors.toList()));
    }

    public static void runMultiThreaded(Iterable<Callable<?>> calls) throws Exception {
        Collection<Thread> threads = new ArrayList<>();
        final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();

        for (Callable<?> call : calls) {
            final Callable<?> call0 = call;
            threads.add(new Thread(() -> {
                try {
                    call0.call();
                } catch (Throwable e) {
                    errors.add(e);

                    throw new RuntimeException(e);
                }
            }));
        }

        for (Thread t : threads)
            t.start();

        // Wait threads finish their job.
        try {
            for (Thread t : threads)
                t.join();
        } catch (InterruptedException e) {
            for (Thread t : threads)
                t.interrupt();

            throw e;
        }

        // Validate errors happens
        Throwable err = errors.peek();
        if (err != null) {
            if (err instanceof Error)
                throw (Error) err;

            throw (Exception) err;
        }
    }
}
