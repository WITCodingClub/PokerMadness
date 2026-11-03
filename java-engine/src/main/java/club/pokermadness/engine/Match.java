package club.pokermadness.engine;

import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

import static club.pokermadness.engine.TableView.*;

/** Runs one local round of fixed hands, with a fresh stack for each hand. */
final class Match {
    record Settings(int hands, int smallBlind, int bigBlind, int stackInBigBlinds, long seed,
                    int startupMs, int actMs, int handEndMs, boolean verbose) {
        Settings {
            if (hands < 1 || hands > 1_000_000) throw new IllegalArgumentException("Hands must be 1–1000000");
            if (smallBlind < 1 || bigBlind < smallBlind) throw new IllegalArgumentException("Blinds must be positive with small ≤ big");
            if (stackInBigBlinds < 1 || stackInBigBlinds > 200) throw new IllegalArgumentException("Stack must be 1–200 big blinds");
            if (startupMs < 1 || actMs < 1 || handEndMs < 1) throw new IllegalArgumentException("Timeouts must be positive");
        }

        int stack(int seats) {
            long stack = (long) bigBlind * stackInBigBlinds;
            if (stack * seats > Integer.MAX_VALUE) throw new IllegalArgumentException("Total table chips exceed the protocol range");
            return (int) stack;
        }
    }

    record Entrant(String label, BotSession.Launch launch) {}
    record Standing(int seat, String label, long netChips, long actionFailures, long handEndFailures) {}

    private Match() {}

    static List<Standing> run(Settings settings, List<Entrant> entrants, PrintWriter log)
            throws InterruptedException {
        int count = entrants.size();
        if (count < 2 || count > 6) throw new IllegalArgumentException("Enter 2–6 Java bot JARs");
        int[] startingStacks = new int[count];
        Arrays.fill(startingStacks, settings.stack(count));
        var deckRandom = new SplittableRandom(deriveSeed(settings.seed(), "poker-madness/deck"));
        var bots = new ArrayList<BotSession>(count);
        var requestNumbers = new RequestNumbers();
        String roundId = "local-" + Long.toUnsignedString(deriveSeed(settings.seed(), "poker-madness/round"), 16);
        for (int seat = 0; seat < count; seat++) {
            bots.add(new BotSession("entry-" + (seat + 1), roundId, entrants.get(seat).launch(),
                    settings.startupMs(), requestNumbers::next,
                    deriveSeed(settings.seed(), "poker-madness/worker/" + seat)));
        }

        long[] totals = new long[count];
        long[] actFailures = new long[count];
        long[] endFailures = new long[count];
        try {
            for (int number = 1; number <= settings.hands(); number++) {
                int button = (number - 1) % count;
                var hand = new Hand(number, button, settings.smallBlind(), settings.bigBlind(),
                        startingStacks, Cards.shuffled(deckRandom));
                Observation observation;
                while ((observation = hand.nextDecision()) != null) {
                    int seat = observation.heroSeat();
                    Action action;
                    boolean fallback = false;
                    try {
                        action = bots.get(seat).act(observation, settings.actMs());
                    } catch (BotFailure failure) {
                        action = observation.fallback();
                        fallback = true;
                        reportFailure(log, settings.verbose(), ++actFailures[seat], number,
                                seat, "act", failure);
                    }
                    hand.act(action, fallback);
                }
                for (int seat = 0; seat < count; seat++) {
                    HandResult result = hand.result(seat);
                    totals[seat] += result.players().get(seat).netChips();
                    try {
                        bots.get(seat).finish(result, settings.handEndMs());
                    } catch (BotFailure failure) {
                        reportFailure(log, settings.verbose(), ++endFailures[seat], number,
                                seat, "hand_end", failure);
                    }
                }
                if (settings.verbose()) {
                    HandResult result = hand.result(0);
                    log.printf("Hand %d, button seat %d, %s: %s%n", number, button + 1, result.reason(),
                            result.players().stream().map(p -> "seat " + (p.seat() + 1) + " " + p.netChips()).toList());
                }
            }
        } finally {
            bots.forEach(BotSession::close);
        }
        var standings = new ArrayList<Standing>(count);
        for (int seat = 0; seat < count; seat++) {
            standings.add(new Standing(seat + 1, entrants.get(seat).label(), totals[seat],
                    actFailures[seat], endFailures[seat]));
        }
        standings.sort(Comparator.comparingLong(Standing::netChips).reversed().thenComparingInt(Standing::seat));
        return List.copyOf(standings);
    }

    private static void reportFailure(PrintWriter log, boolean verbose, long count,
                                      int hand, int seat, String phase, BotFailure failure) {
        if (!verbose && count > 1) return;
        String message = failure.getMessage().replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
        log.printf("Hand %d, seat %d, %s failed: %s%n", hand, seat + 1, phase,
                message.substring(0, Math.min(300, message.length())));
    }

    private static long deriveSeed(long matchSeed, String domain) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest.digest(ByteBuffer.allocate(Long.BYTES).putLong(matchSeed).array())).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Missing SHA-256", impossible);
        }
    }

    private static final class RequestNumbers {
        private long last;

        String next() {
            return "r" + ++last;
        }
    }
}
