package com.ravi.stockscreener.util;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

public class SerialExecutor implements Executor {
    final Queue<Runnable> queue = new ArrayDeque<>();
    final Executor executor;
    Runnable active;

    public SerialExecutor(Executor executor) {
        this.executor = executor;
    }

    public synchronized void execute(@NotNull final Runnable r) {
        queue.offer(() -> {
            try {
                r.run();
            } finally {
                scheduleNext();
            }
        });
        if (active == null) scheduleNext();
    }

    protected synchronized void scheduleNext() {
        active = queue.poll();
        if (active != null) executor.execute(active);
    }
}
