package org.example;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

import java.util.Collections;
import java.util.List;

public class ZookeeperLock implements AutoCloseable {
    private final ZooKeeper zk;
    private final String baseLockPath;
    private final ThreadLocal<String> lockPath = new ThreadLocal<>();

    public ZookeeperLock(ZooKeeper zk, String lockName) throws InterruptedException, KeeperException {
        this.zk = zk;

        baseLockPath = zk.create(lockName, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    public void lock() {
        try {
            String lockPath0 = zk.create(baseLockPath + "/lock-", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
            lockPath.set(lockPath0);

            final Object mux = new Object();
            synchronized (mux) {
                while (true) {
                    List<String> nodes = zk.getChildren(baseLockPath, null);

                    Collections.sort(nodes);
                    if (lockPath0.endsWith(nodes.get(0))) {
                        return;
                    } else {
                        Watcher w = event -> {
                            synchronized (mux) {
                                mux.notifyAll();
                            }
                        };

                        if (zk.exists(baseLockPath + '/' + nodes.get(1), w) == null) {
                            mux.wait();
                        }
                    }
                }
            }
        } catch (KeeperException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public void unlock() {
        try {
            zk.delete(lockPath.get(), -1);
        } catch (KeeperException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public void close() {
        try {
            assert baseLockPath != null;

            zk.delete(baseLockPath, -1);
        } catch (KeeperException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}