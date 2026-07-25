package minicode.memory.extraction;

import minicode.config.MemoryConfig;
import minicode.core.turn.CancellationSource;
import minicode.core.turn.CancellationToken;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 在主 Turn 结束后执行记忆提取的进程内单工作线程 FIFO 协调器。
 */
public final class MemoryExtractionCoordinator
        implements MemoryExtractionSubmitter, AutoCloseable {
    private static final System.Logger LOG =
            System.getLogger(MemoryExtractionCoordinator.class.getName());

    private final MemoryExtractionRunner runner;
    private final MemoryExtractionEventSink eventSink;
    private final Duration closeTimeout;
    private final ExecutorService executor;
    private final Object stateLock = new Object();
    private final Set<String> acceptedTurnIds = new HashSet<>();

    private boolean closed;
    private boolean running;
    private int waiting;
    private CancellationToken currentCancellation;

    public MemoryExtractionCoordinator(MemoryExtractionAgent agent,
                                       MemoryExtractionEventSink eventSink) {
        this(agent, eventSink, MemoryConfig.SHUTDOWN_WAIT, newSingleWorker());
    }

    MemoryExtractionCoordinator(MemoryExtractionRunner runner,
                                MemoryExtractionEventSink eventSink,
                                Duration closeTimeout,
                                ExecutorService executor) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.closeTimeout = requirePositive(closeTimeout);
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public boolean submit(MemoryExtractionRequest request) {
        MemoryExtractionRequest actualRequest = Objects.requireNonNull(request, "request");
        synchronized (stateLock) {
            if (closed || !acceptedTurnIds.add(actualRequest.turnId())) {
                return false;
            }
            waiting++;
        }
        try {
            executor.execute(() -> execute(actualRequest));
            return true;
        } catch (RejectedExecutionException exception) {
            synchronized (stateLock) {
                waiting--;
                acceptedTurnIds.remove(actualRequest.turnId());
            }
            return false;
        }
    }

    public MemoryExtractionStatus status() {
        synchronized (stateLock) {
            return new MemoryExtractionStatus(running, waiting);
        }
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        executor.shutdown();
        boolean terminated = await(closeTimeout);
        if (terminated) {
            return;
        }

        CancellationToken cancellation;
        synchronized (stateLock) {
            cancellation = currentCancellation;
        }
        if (cancellation != null) {
            cancellation.requestCancellation(
                    CancellationSource.SYSTEM,
                    "Memory extraction shutdown timeout");
        }
        List<Runnable> discarded = executor.shutdownNow();
        synchronized (stateLock) {
            waiting = Math.max(0, waiting - discarded.size());
        }
    }

    private void execute(MemoryExtractionRequest request) {
        CancellationToken cancellation = CancellationToken.create();
        synchronized (stateLock) {
            waiting = Math.max(0, waiting - 1);
            running = true;
            currentCancellation = cancellation;
        }
        try {
            MemoryExtractionResult result = Objects.requireNonNull(
                    runner.run(request, cancellation), "memory extraction result");
            if (result.updated()) {
                notifyUpdated(new MemoryExtractionUpdatedEvent(
                        request.sessionId(), request.turnId(), result.changes()));
            } else {
                LOG.log(
                        System.Logger.Level.DEBUG,
                        "Memory extraction {0} for turn {1}{2}",
                        result.outcome(),
                        request.turnId(),
                        result.diagnostic().map(diagnostic -> ": " + diagnostic).orElse(""));
            }
        } catch (RuntimeException exception) {
            // 后台提取失败必须与主对话隔离。
            LOG.log(
                    System.Logger.Level.DEBUG,
                    "Memory extraction failed for turn " + request.turnId(),
                    exception);
        } finally {
            synchronized (stateLock) {
                currentCancellation = null;
                running = false;
            }
        }
    }

    private void notifyUpdated(MemoryExtractionUpdatedEvent event) {
        try {
            eventSink.onUpdated(event);
        } catch (RuntimeException exception) {
            // 文件写入已经成为权威结果，UI 通知失败不能触发重复写入。
            LOG.log(
                    System.Logger.Level.DEBUG,
                    "Unable to publish memory update for turn " + event.turnId(),
                    exception);
        }
    }

    private boolean await(Duration timeout) {
        try {
            return executor.awaitTermination(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Duration requirePositive(Duration timeout) {
        Duration actual = Objects.requireNonNull(timeout, "closeTimeout");
        if (actual.isZero() || actual.isNegative()) {
            throw new IllegalArgumentException("closeTimeout must be positive");
        }
        return actual;
    }

    private static ExecutorService newSingleWorker() {
        return Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("memory-extraction-worker-", 0).factory());
    }
}
