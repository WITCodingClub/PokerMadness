# Poker Madness Java engine

A standalone Java 17 Maven module for playing a local no-limit Texas Hold'em match with trusted Poker Madness bot JARs. It runs one table of 2–6 bots for a fixed number of hands and reports net chips and worker failures by seat. The original Java starter works without changes.

## Run a match

From the `competition-draft` directory that contains both `java-starter` and `java-engine`:

```sh
mvn -f java-starter/pom.xml package
mvn -f java-engine/pom.xml verify
java -jar java-engine/target/poker-engine.jar --hands 20 --seed 42 java-starter/target/poker-bot.jar java-starter/target/poker-bot.jar
```

The engine module also builds by itself with `mvn verify`. Give the CLI one JAR path per seat, in clockwise order. Repeating a path runs an independent process for each seat. To compare strategies, pass their different JAR paths. Quotes work for paths with spaces.

```sh
java -jar java-engine/target/poker-engine.jar --help
```

The defaults are 100 hands, blinds of 5/10, and 100 big blinds per bot at the beginning of every hand. Use `--hands`, `--small-blind`, `--big-blind`, or `--stack-bb` to change these for local tests. Use `--verbose` for per-hand chip totals and all worker errors. The seed is printed on every run. Supply the same `--seed`, bots, seat order and settings to replay the cards and seeded bot randomness; external randomness or timeouts can change bot decisions and results.

## Rules and bot behavior

- The button rotates every hand, including heads-up. Starting stacks reset; the score is the sum of final stack minus starting stack over all hands. Ties stay tied.
- The engine deals and ranks hands itself. It handles short all-in calls and raises, betting reopening, uncalled chips, side pots, tied hands, and odd chips. On a street with a short opening wager, an eligible player may complete the wager to one big blind. The first full wager establishes that street's opening increment. Short blinds are outside the local runner's equal-stack setup.
- The engine speaks [bot protocol 0.1](../protocol/PROTOCOL.md) over standard input and output, including the startup handshake and hand-end updates. A bot sees its own cards, the public board and action history, and its own `user_state`. Other hole cards are only shown for live hands at showdown.
- The engine forwards the last valid state to subsequent requests, including after a worker restart. Failed decisions become a check when free or a fold when facing chips. Failed hand-end responses leave the score and previous state intact. The summary counts decision and hand-end failures separately.
- The default startup, action, and hand-end deadlines are for local testing. They can be changed with `--startup-ms`, `--act-ms`, and `--hand-end-ms`.

Worker JARs run under your user account without a sandbox. Only launch code you trust. This runner has no student submission intake, isolation, or tournament scheduling. JARs in a Maven `target` directory run from the bot project root so they can load optional `data/` files; other JARs run from their containing directory.

The shared protocol is the event's rules reference. The [Poker Tournament Directors Association rules](https://www.pokertda.com/view-poker-tda-rules/) were consulted for raise sizes, short all-ins, and splitting pots.

## Module layout

| File | Role |
| --- | --- |
| `Hand.java`, `HandRank.java`, `Cards.java` | Deal, validate betting, evaluate hands, and settle chips |
| `TableView.java`, `Wire.java` | Player-visible views and protocol validation |
| `LocalWorker.java`, `BotSession.java` | Process I/O, deadlines, restarts, and accepted state |
| `Match.java`, `Main.java` | Single-table match loop and command-line interface |

`mvn verify` runs rule and hand-ranking tests, seeded chip and deck invariants, strict message checks, and subprocess tests for timeouts, restart state, fallback, and replay.
