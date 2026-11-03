package club.pokermadness.engine;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Supplier;

import static club.pokermadness.engine.TableView.*;

/** Owns exactly one seat's transport and the last state that seat successfully committed. */
final class BotSession implements AutoCloseable {
    record Launch(List<String> command, Path directory) {
        static Launch forJar(Path jar) {
            Path absolute = jar.toAbsolutePath().normalize();
            Path directory = absolute.getParent();
            // Maven starter bots commonly load optional data relative to their project root.
            if (directory.getFileName() != null && directory.getFileName().toString().equals("target")
                    && directory.getParent() != null) {
                directory = directory.getParent();
            }
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
            return new Launch(List.of(java, "-XX:-UsePerfData", "-jar", absolute.toString()), directory);
        }
    }

    private final String botId;
    private final String roundId;
    private final Launch launch;
    private final int startupMs;
    private final Supplier<String> requestIds;
    private final SplittableRandom random;
    private JsonObject state = new JsonObject();
    private LocalWorker worker;

    BotSession(String botId, String roundId, Launch launch, int startupMs,
               Supplier<String> requestIds, long seed) {
        this.botId = botId;
        this.roundId = roundId;
        this.launch = launch;
        this.startupMs = startupMs;
        this.requestIds = requestIds;
        this.random = new SplittableRandom(seed);
    }

    Action act(Observation observation, int budgetMs) throws BotFailure, InterruptedException {
        return exchange(Wire.Kind.ACT, observation, observation, budgetMs).action();
    }

    void finish(HandResult result, int budgetMs) throws BotFailure, InterruptedException {
        exchange(Wire.Kind.END, result, null, budgetMs);
    }

    private Wire.Reply exchange(Wire.Kind kind, Object body, Observation observation, int budgetMs)
            throws BotFailure, InterruptedException {
        try {
            if (worker == null) {
                worker = new LocalWorker(launch.command(), launch.directory());
                request(Wire.Kind.INIT, null, null, startupMs);
            }
            Wire.Reply reply = request(kind, body, observation, budgetMs);
            state = reply.state(); // Commit only after the complete response, including any move, is valid.
            return reply;
        } catch (BotFailure failure) {
            String diagnostic = worker == null ? "" : worker.diagnostic();
            abort();
            if (diagnostic.isBlank()) throw failure;
            throw new BotFailure(failure.getMessage() + " (stderr: " + diagnostic.strip() + ")", failure);
        } catch (InterruptedException interrupted) {
            abort();
            throw interrupted;
        }
    }

    private Wire.Reply request(Wire.Kind kind, Object body, Observation observation, int budgetMs)
            throws BotFailure, InterruptedException {
        var request = Wire.request(kind, requestIds.get(), roundId, botId, random.nextLong(1L << 32),
                budgetMs, state, body);
        return Wire.response(request, worker.exchange(request.frame(), budgetMs), observation);
    }

    private void abort() {
        if (worker != null) worker.abort();
        worker = null;
    }

    @Override
    public void close() {
        if (worker != null) worker.close();
        worker = null;
    }
}
