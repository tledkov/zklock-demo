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
import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ZookeeperLockTest {
    private static final String LOCALHOST = "127.0.0.1";
    private static final String ZK_TEST_DIR = "zk";
    private static ZooKeeperServer zookeeper;

    @BeforeAll
    public static void setup() throws IOException, InterruptedException {
        NIOServerCnxnFactory cnxnFactory = new NIOServerCnxnFactory();
        cnxnFactory.configure(new InetSocketAddress(LOCALHOST, 0), 0);

        zookeeper = new ZooKeeperServer(new File(ZK_TEST_DIR, "snapshot"), new File(ZK_TEST_DIR,"log"), 800);

        cnxnFactory.startup(zookeeper);
    }

    @AfterAll
    public static void cleanup() throws IOException, InterruptedException {
        zookeeper.shutdown();

        long tEnd = System.currentTimeMillis() + 10_000;

        // Wait for unlock.
        while (true) {
            try {
                FileUtils.deleteDirectory(new File(ZK_TEST_DIR));

                break;
            } catch (IOException e) {
                if (tEnd < System.currentTimeMillis())
                    throw e;
                else
                    Thread.sleep(200);
            }
        }
    }

    @Test
    public void singleProcessCheck() throws Exception {
        ZooKeeper zk = new ZooKeeper(LOCALHOST + ":" + zookeeper.getClientPort(), 800, null);

        long[] count = new long[1];
        AtomicLong atomicCount = new AtomicLong();

        try (ZookeeperLock zkLock = new ZookeeperLock(zk, "/_test_lock")) {
            long tEnd = System.currentTimeMillis() + 10_000;
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
