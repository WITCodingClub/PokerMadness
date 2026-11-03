package club.pokermadness.engine;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Command-line entry point for a single local match between trusted Java bots. */
public final class Main {
    private static final String HELP = """
            Poker Madness — local Java match runner (trusted bot JARs only)

            Usage: java -jar target/poker-engine.jar [options] <bot.jar> <bot.jar> [more JARs]
            Run 2–6 bots at one table. Repeat a path to give the same bot multiple seats.

              --hands N          Hands to play (default 100)
              --seed N           Signed 64-bit replay seed (default: random; printed with results)
              --small-blind N    Small blind in chips (default 5)
              --big-blind N      Big blind in chips (default 10)
              --stack-bb N       Starting stack each hand, in big blinds (default 100; range 1–200)
              --startup-ms N     Worker startup and ready deadline in ms (default 5000)
              --act-ms N         Decision deadline in ms (default 1000)
              --hand-end-ms N    Hand-end acknowledgment deadline in ms (default 1000)
              --verbose          Log each hand's net chips and all worker failures
              --help             Show this help
              --                 Treat all remaining arguments as JAR paths
            """;

    private Main() {}

    public static void main(String[] args) {
        int code = run(args, new PrintWriter(System.out, true, StandardCharsets.UTF_8),
                new PrintWriter(System.err, true, StandardCharsets.UTF_8));
        if (code != 0) System.exit(code);
    }

    static int run(String[] args, PrintWriter out, PrintWriter err) {
        try {
            if (args.length == 0 || args.length == 1 && args[0].equals("--help")) {
                out.print(HELP);
                out.flush();
                return 0;
            }
            Cli cli = parse(args);
            var settings = cli.settings();
            settings.stack(cli.entrants().size());
            out.printf("Poker Madness | %d hands | %d seats | blinds %d/%d | %d BB stacks | seed %d%n",
                    settings.hands(), cli.entrants().size(), settings.smallBlind(), settings.bigBlind(),
                    settings.stackInBigBlinds(), settings.seed());
            List<Match.Standing> standings = Match.run(settings, cli.entrants(), err);
            out.printf(Locale.ROOT, "%-5s %-28s %12s %10s %12s%n",
                    "Seat", "Bot JAR", "Net chips", "Act errors", "Result errors");
            for (var standing : standings) {
                out.printf(Locale.ROOT, "%-5d %-28s %+12d %10d %12d%n", standing.seat(), standing.label(),
                        standing.netChips(), standing.actionFailures(), standing.handEndFailures());
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("Poker Madness: " + e.getMessage());
            err.println("Use --help for usage.");
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Poker Madness: match interrupted.");
            return 130;
        }
    }

    private static Cli parse(String[] args) {
        int hands = 100, smallBlind = 5, bigBlind = 10, stackInBigBlinds = 100;
        int startup = 5_000, act = 1_000, handEnd = 1_000;
        Long seed = null;
        boolean verbose = false, pathsOnly = false;
        var entrants = new ArrayList<Match.Entrant>();
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (pathsOnly || !argument.startsWith("-")) {
                Path jar = Path.of(argument);
                if (!Files.isRegularFile(jar) || !Files.isReadable(jar)) {
                    throw new IllegalArgumentException("Cannot read bot JAR: " + jar);
                }
                String label = jar.getFileName().toString().replaceAll("[\\p{Cntrl}\\p{Cf}]", "_");
                entrants.add(new Match.Entrant(label, BotSession.Launch.forJar(jar)));
                continue;
            }
            if (argument.equals("--")) {
                pathsOnly = true;
                continue;
            }
            if (argument.equals("--verbose")) {
                verbose = true;
                continue;
            }
            switch (argument) {
                case "--hands" -> hands = intValue(args, ++i, argument);
                case "--small-blind" -> smallBlind = intValue(args, ++i, argument);
                case "--big-blind" -> bigBlind = intValue(args, ++i, argument);
                case "--stack-bb" -> stackInBigBlinds = intValue(args, ++i, argument);
                case "--startup-ms" -> startup = intValue(args, ++i, argument);
                case "--act-ms" -> act = intValue(args, ++i, argument);
                case "--hand-end-ms" -> handEnd = intValue(args, ++i, argument);
                case "--seed" -> seed = longValue(args, ++i, argument);
                default -> throw new IllegalArgumentException("Unknown option: " + argument);
            }
        }
        if (entrants.size() < 2 || entrants.size() > 6) throw new IllegalArgumentException("Enter 2–6 Java bot JARs");
        var settings = new Match.Settings(hands, smallBlind, bigBlind, stackInBigBlinds,
                seed != null ? seed : new SecureRandom().nextLong(), startup, act, handEnd, verbose);
        return new Cli(settings, List.copyOf(entrants));
    }

    private static int intValue(String[] args, int index, String option) {
        long value = longValue(args, index, option);
        if (value < 1 || value > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid value for " + option);
        return (int) value;
    }

    private static long longValue(String[] args, int index, String option) {
        if (index == args.length) throw new IllegalArgumentException("Missing value for " + option);
        try {
            return Long.parseLong(args[index]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value for " + option);
        }
    }

    private record Cli(Match.Settings settings, List<Match.Entrant> entrants) {}
}
