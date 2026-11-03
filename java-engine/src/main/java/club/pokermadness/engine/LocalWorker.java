package club.pokermadness.engine;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One trusted local subprocess with bounded reads, writes, stderr and waits. */
final class LocalWorker implements AutoCloseable {
    private static final int STDERR_LIMIT = 2_048;
    private final Process process;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> daemon("bot-stdin", task));
    private final ArrayBlockingQueue<Frame> output = new ArrayBlockingQueue<>(2);
    private final AtomicBoolean overflow = new AtomicBoolean();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private boolean closed;

    LocalWorker(List<String> command, Path directory) throws BotFailure {
        try {
            process = new ProcessBuilder(command).directory(directory.toFile()).start();
        } catch (IOException e) {
            writer.shutdownNow();
            throw new BotFailure("Could not start worker: " + e.getMessage(), e);
        }
        daemon("bot-stdout", this::collectFrames).start();
        daemon("bot-stderr", this::collectStderr).start();
    }

    byte[] exchange(byte[] request, int budgetMs) throws BotFailure, InterruptedException {
        Frame early = output.poll();
        if (early != null) throw new BotFailure(early.failure == null ? "Unsolicited stdout" : early.failure);
        if (overflow.get()) throw new BotFailure("Too many stdout lines");
        Future<Long> delivery = writer.submit(() -> {
            process.getOutputStream().write(request);
            process.getOutputStream().flush();
            return System.nanoTime();
        });
        long deliveredAt;
        try {
            // Cap blocked pipe writes as well. The response deadline starts after delivery.
            deliveredAt = delivery.get(budgetMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            delivery.cancel(true);
            throw new BotFailure("Worker did not read the request within " + budgetMs + " ms", e);
        } catch (ExecutionException e) {
            throw new BotFailure("Worker input closed", e.getCause());
        }
        long budget = TimeUnit.MILLISECONDS.toNanos(budgetMs);
        long remaining = Math.max(0, budget - (System.nanoTime() - deliveredAt));
        Frame reply = output.poll(remaining, TimeUnit.NANOSECONDS);
        if (reply == null || reply.completedAt - deliveredAt > budget) {
            throw new BotFailure("Worker did not respond within " + budgetMs + " ms");
        }
        if (reply.failure != null) throw new BotFailure(reply.failure);
        Frame next = output.peek();
        if (overflow.get() || (next != null && next.failure == null)) {
            throw new BotFailure("Worker emitted extra stdout lines");
        }
        return reply.bytes;
    }

    String diagnostic() {
        synchronized (stderr) {
            return stderr.toString(StandardCharsets.UTF_8);
        }
    }

    private void collectFrames() {
        try (var stream = new BufferedInputStream(process.getInputStream())) {
            for (;;) {
                byte[] bytes = readLine(stream);
                if (bytes == null) {
                    offer(Frame.failure("Worker closed stdout"));
                    return;
                }
                if (!offer(new Frame(bytes, null, System.nanoTime()))) return;
            }
        } catch (IOException e) {
            offer(Frame.failure(e.getMessage()));
        }
    }

    private boolean offer(Frame frame) {
        if (output.offer(frame)) return true;
        overflow.set(true);
        return false;
    }

    private static byte[] readLine(InputStream stream) throws IOException {
        var buffer = new ByteArrayOutputStream(1024);
        int b;
        while ((b = stream.read()) != -1 && b != '\n') {
            if (buffer.size() >= Wire.MAX_RESPONSE + 1) throw new IOException("Response exceeds the frame limit");
            buffer.write(b);
        }
        if (b == -1) {
            if (buffer.size() == 0) return null;
            throw new IOException("Worker ended with an unterminated response");
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') bytes = Arrays.copyOf(bytes, bytes.length - 1);
        if (bytes.length > Wire.MAX_RESPONSE) throw new IOException("Response exceeds the frame limit");
        return bytes;
    }

    private void collectStderr() {
        try (var stream = process.getErrorStream()) {
            var buffer = new byte[1024];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                synchronized (stderr) {
                    stderr.write(buffer, 0, Math.min(count, STDERR_LIMIT - stderr.size()));
                }
            }
        } catch (IOException ignored) {
            // The process may already have been killed.
        }
    }

    @Override
    public void close() {
        stop(true);
    }

    void abort() {
        stop(false);
    }

    private void stop(boolean graceful) {
        if (closed) return;
        closed = true;
        writer.shutdownNow();
        boolean interrupted = Thread.currentThread().isInterrupted();
        try {
            if (graceful && !interrupted) {
                try {
                    process.getOutputStream().close();
                    if (process.waitFor(200, TimeUnit.MILLISECONDS)) return;
                } catch (IOException ignored) {
                    // The worker may have already closed stdin.
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                if (!interrupted) {
                    try {
                        process.waitFor(300, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static Thread daemon(String name, Runnable body) {
        var thread = new Thread(body, name);
        thread.setDaemon(true);
        return thread;
    }

    private record Frame(byte[] bytes, String failure, long completedAt) {
        static Frame failure(String message) {
            return new Frame(null, message == null ? "Cannot read worker stdout" : message, System.nanoTime());
        }
    }
}
