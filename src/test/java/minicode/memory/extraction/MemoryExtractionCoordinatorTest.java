package minicode.memory.extraction;

import minicode.core.message.UserMessage;
import minicode.memory.MemoryType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryExtractionCoordinatorTest {
    @TempDir
    Path tempDir;

    @Test
    void submitIsNonBlockingAndTasksRunStrictlyOneAtATimeInFifoOrder() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        MemoryExtractionRunner runner = (request, token) -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            order.add(request.turnId());
            if ("turn-a".equals(request.turnId())) {
                firstStarted.countDown();
                try {
                    releaseFirst.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            active.decrementAndGet();
            finished.countDown();
            return MemoryExtractionResult.noMemory();
        };
        MemoryExtractionCoordinator coordinator = coordinator(runner, event -> {
        }, Duration.ofSeconds(2));
        try {
            long started = System.nanoTime();
            assertTrue(coordinator.submit(request("turn-a")));
            assertTrue(coordinator.submit(request("turn-b")));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMillis < 200, "submit should not wait for the worker");
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertEquals(new MemoryExtractionStatus(true, 1), coordinator.status());
            releaseFirst.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("turn-a", "turn-b"), order);
            assertEquals(1, maxActive.get());
        } finally {
            releaseFirst.countDown();
            coordinator.close();
        }
    }

    @Test
    void duplicateTurnAndSubmissionAfterCloseAreRejected() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        MemoryExtractionCoordinator coordinator = coordinator((request, token) -> {
            finished.countDown();
            return MemoryExtractionResult.noMemory();
        }, event -> {
        }, Duration.ofSeconds(1));

        assertTrue(coordinator.submit(request("same")));
        assertFalse(coordinator.submit(request("same")));
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        coordinator.close();
        assertFalse(coordinator.submit(request("later")));
    }

    @Test
    void onlyAuthoritativeUpdatesEmitContentFreeEvents() throws Exception {
        CountDownLatch finished = new CountDownLatch(2);
        List<MemoryExtractionUpdatedEvent> events =
                java.util.Collections.synchronizedList(new ArrayList<>());
        MemoryExtractionCoordinator coordinator = coordinator((request, token) -> {
            try {
                return "changed".equals(request.turnId())
                        ? MemoryExtractionResult.updated(Map.of(MemoryType.FEEDBACK, 1))
                        : MemoryExtractionResult.noChange();
            } finally {
                finished.countDown();
            }
        }, events::add, Duration.ofSeconds(1));
        try {
            coordinator.submit(request("unchanged"));
            coordinator.submit(request("changed"));
            assertTrue(finished.await(1, TimeUnit.SECONDS));
        } finally {
            coordinator.close();
        }

        assertEquals(1, events.size());
        assertEquals("changed", events.getFirst().turnId());
        assertEquals(Map.of(MemoryType.FEEDBACK, 1), events.getFirst().changes());
    }

    @Test
    void closeTimeoutCancelsCurrentAndDiscardsWaitingTasks() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean sawCancellation = new AtomicBoolean();
        AtomicInteger executions = new AtomicInteger();
        MemoryExtractionCoordinator coordinator = coordinator((request, token) -> {
            executions.incrementAndGet();
            started.countDown();
            while (!token.isCancellationRequested() && !Thread.currentThread().isInterrupted()) {
                Thread.onSpinWait();
            }
            sawCancellation.set(token.isCancellationRequested());
            return MemoryExtractionResult.cancelled("shutdown");
        }, event -> {
        }, Duration.ofMillis(100));
        coordinator.submit(request("running"));
        coordinator.submit(request("waiting"));
        assertTrue(started.await(1, TimeUnit.SECONDS));

        long startNanos = System.nanoTime();
        coordinator.close();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertTrue(elapsedMillis < 600);
        for (int attempt = 0; attempt < 100 && !sawCancellation.get(); attempt++) {
            Thread.sleep(5);
        }
        assertTrue(sawCancellation.get());
        assertEquals(1, executions.get());
        assertEquals(0, coordinator.status().waiting());
    }

    @Test
    void normalCloseDrainsAcceptedTasksAndIsIdempotent() {
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        MemoryExtractionCoordinator coordinator = coordinator((request, token) -> {
            order.add(request.turnId());
            return MemoryExtractionResult.noMemory();
        }, event -> {
        }, Duration.ofSeconds(1));
        assertTrue(coordinator.submit(request("first")));
        assertTrue(coordinator.submit(request("second")));

        coordinator.close();

        assertEquals(List.of("first", "second"), order);
        assertEquals(MemoryExtractionStatus.idle(), coordinator.status());
        assertFalse(coordinator.submit(request("after-close")));
        assertDoesNotThrow(coordinator::close);
    }

    @Test
    void runnerAndEventSinkFailuresDoNotStopFollowingTasks() throws Exception {
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch thirdFinished = new CountDownLatch(1);
        AtomicInteger eventCalls = new AtomicInteger();
        MemoryExtractionCoordinator coordinator = coordinator((request, token) -> {
            order.add(request.turnId());
            return switch (request.turnId()) {
                case "runner-fails" -> throw new IllegalStateException("boom");
                case "event-fails" -> MemoryExtractionResult.updated(Map.of(MemoryType.USER, 1));
                default -> {
                    thirdFinished.countDown();
                    yield MemoryExtractionResult.noChange();
                }
            };
        }, event -> {
            eventCalls.incrementAndGet();
            throw new IllegalStateException("ui unavailable");
        }, Duration.ofSeconds(1));
        try {
            coordinator.submit(request("runner-fails"));
            coordinator.submit(request("event-fails"));
            coordinator.submit(request("still-runs"));

            assertTrue(thirdFinished.await(1, TimeUnit.SECONDS));
        } finally {
            coordinator.close();
        }

        assertEquals(List.of("runner-fails", "event-fails", "still-runs"), order);
        assertEquals(1, eventCalls.get());
    }

    private MemoryExtractionCoordinator coordinator(MemoryExtractionRunner runner,
                                                     MemoryExtractionEventSink sink,
                                                     Duration timeout) {
        return new MemoryExtractionCoordinator(
                runner,
                sink,
                timeout,
                Executors.newSingleThreadExecutor(
                        Thread.ofVirtual().name("memory-test-", 0).factory()));
    }

    private MemoryExtractionRequest request(String turnId) {
        return new MemoryExtractionRequest(
                "session",
                turnId,
                tempDir,
                Instant.parse("2026-07-24T01:00:00Z"),
                ConversationSnapshot.capture(
                        List.of(new UserMessage("hello")), 5, 24_000, 8_000));
    }
}
