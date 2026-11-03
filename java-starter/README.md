# Poker Madness Java starter

Protocol 0.1 review draft. Tournament dates and performance limits are undecided.

This project is a working protocol client with a small example strategy. It builds a runnable JAR, accepts player-visible requests, returns legal actions, and carries a JSON state object between calls when the caller supplies it. The included fixture runner sends scripted snapshots. It does not deal cards, play opponents, or rank poker strength.

## Requirements and first run

- A JDK supporting Java 17 or later, including `javac`, and Apache Maven 3.8 or later.
- Python 3.9 or later for the optional fixture and ZIP tools. Those tools use only the standard library.
- Internet for the first Maven build, to download the pinned general-purpose dependencies and build/test plugins. After the cache is populated, `mvn -o verify` can build offline. Official dependency installation happens before offline matches.

From this directory:

```sh
mvn verify
python3 tools/fixture_runner.py
python3 tools/fixture_runner.py --carry-state
```

On Windows, replace `python3` with `py -3` if needed. These commands do not require Bash, Docker, an IDE plugin, or a poker server. Maven writes the runnable file to `target/poker-bot.jar`. The fixed worker command is:

```sh
java -XX:-UsePerfData -jar target/poker-bot.jar
```

That worker waits for JSON lines on stdin; launching it without input does not start a game or open a UI. Use the fixture runner for a visible first test. Its first request initializes the worker under a separate startup allowance.

## What to edit

| Path | Purpose |
| --- | --- |
| src/main/java/club/pokermadness/bot/StarterBot.java | Replace the example decision policy and optional hand-end update |
| src/main/java/club/pokermadness/BotFactory.java | Select a different strategy class, if you add one |
| bot.json | Set your bot name, actual contributor roster, and dependency declaration |
| STRATEGY.md | Explain your strategy, state, data and dependencies |
| pom.xml | Add exact versions of additional general-purpose dependencies you request |
| data/ | Optional declared read-only tables and their generation scripts |
| api/ | Typed observations, cards, action constructors and strategy interface |
| protocol/Protocol.java and Main.java | Organizer transport; normally leave unchanged |
| examples/ and schemas/ | Shared protocol fixtures and JSON Schemas |
| tools/ | Fixture runner and source ZIP tool |

You may add helper classes and packages. There is no source line-count limit. Keep all strategy and generation logic readable. Never print diagnostics to stdout; use `System.err.println(...)`.

## Strategy interface

Implement `PokerBot.act(Observation observation, JsonObject userState, BotContext context)` and return `new Decision(action, nextState)`.

Observations are immutable records. Useful helpers include `observation.hero()`, `observation.can(ActionType.CHECK)`, and `observation.legal(ActionType.RAISE)`. Cards expose numeric ranks 2–14 and a suit character. `context.random()` constructs a seeded `SplittableRandom`; create it once per call if using it repeatedly.

The action constructors are `Action.fold()`, `Action.check()`, `Action.call()`, `Action.betTo(total)`, and `Action.raiseTo(total)`. Bet and raise totals include the chips already committed **on that street**.

For example, if the hero has committed 10 and the legal raise range is 50–1,000, `Action.raiseTo(50)` adds 40 chips. `Action.raiseTo(20)` is invalid. Call responses never contain an amount; the engine uses the actual cost in the call descriptor. Check the legal action before choosing it:

```java
var raise = observation.legal(ActionType.RAISE);
if (raise.isPresent()) {
    int target = raise.get().minAmount();
    return new Decision(Action.raiseTo(target), userState);
}
```

This demonstrates sizing, not a suggested policy to raise every time.

The starter checks when free. Otherwise it calls up to two big blinds, or up to four for a preflop pocket pair; it folds larger calls. These arbitrary teaching thresholds are not a measured competitive strategy. Change them or replace the policy entirely. The tournament validator checks legal behavior, not agreement with this example.

## One bot object per hand

The first request for a hand creates a fresh `PokerBot` object. Preflop, flop, turn, river, and `onHandEnd()` all use that object while the worker stays alive. The next hand gets a new object. An ordinary field such as `private int raisesThisHand;` can remember events during the hand. Set its starting value in the field declaration or constructor.

Use **`userState`** for anything the next hand should know, such as how many hands have been played. Copy the supplied `JsonObject`, update it, and return it from `act()` or `onHandEnd()`. The engine passes on the last valid state at each request. It keeps the state between hands in the same round and resets it to `{}` for a new round. Mutable static fields, files, and background tasks may not store game memory.

If the worker crashes or is restarted mid-hand, it creates a new object even for that same hand. Its fields start fresh. Keep any information you need to survive a restart in `userState`, or rebuild it from the observation and public history.

`initialize(context)` is optional worker setup. It runs once per worker launch, under the startup allowance, on a separate object. Use it for startup checks or loading declared read-only data shared by hand objects. Use ordinary field initializers or a constructor for hand fields.

Override `onHandEnd` to update statistics from the result, even after your bot folds. If the hand result is the first request for that hand, its object starts with fresh fields. The starter demonstrates `decisions_seen`, `hands_seen`, and cumulative `net_chips`. Do not count a public history event more than once when it reappears in later observations.

The state must be an object of at most 65,536 serialized UTF-8 bytes, with bounded nesting and cross-language-safe integer counters. It cannot contain code or a hidden model. Needing a larger state is not permission to move it into files or globals.

## Tests and fixtures

`mvn verify` compiles with `--release 17`, runs protocol, hand lifecycle, and example-behavior tests, and packages the runnable JAR with its JSON dependency. The tests cover framing, duplicate keys, invalid UTF-8, illegal actions, sizing boundaries, state limits, and which calls share a hand object. Unit tests of the provided example are separate from the tournament's strategy-independent checks. If replacing the example, update tests that deliberately assert its behavior; retain the protocol tests.

The fixture runner validates one response per input, request correlation, allowed actions, amounts, state shape and response size. It does not enforce container isolation or tournament CPU/memory limits. Only run your own or trusted code this way. Its default three-second timeout is diagnostic and is not the future competition limit.

Fixtures are independent snapshots with accounted public histories, not a sequence produced by a game engine. The `--carry-state` mode demonstrates state transfer across these independent snapshots; its counters do not represent a real poker session. Unequal-stack compatibility fixtures are labeled; official competition hands still start equally at 100 big blinds.

## Dependencies and submission

Use general-purpose dependencies only. Declare the exact Maven coordinates and versions in `pom.xml` and `bot.json`, and explain why they are needed on the submission form. Organizers review them individually. The starter's Gson dependency is general-purpose JSON support. Test dependencies and build plugins are also pinned in `pom.xml`; see `DEPENDENCIES.md`.

Update `bot.json` and `STRATEGY.md`, run the checks, then:

```sh
python3 tools/package_submission.py
```

Upload `dist/submission.zip` through the announced form. The source ZIP has `bot.json` and `pom.xml` at the root, without an enclosing project folder. It includes source, declared data, instructions, schemas and fixtures. It excludes `target/`, downloaded dependencies, editor settings and caches. The packaging tool intentionally refuses the unedited contributor placeholder. `--allow-template` exists only for organizer checks of this unedited starter.

The tool's local 1,000-file and 50 MiB expanded diagnostics are protective defaults, not final tournament quotas. The official compressed and expanded limits remain undecided. Data larger than the approved size must be discussed before the final deadline.

Each person can be involved with at most three distinct entries across all teams and solo bots. Revisions of this entry use the same entry ID. Winning entries must publish source and the required supporting data/generation material under the final rules. Do not embed contact emails or secrets in publishable source; provide private contact details on the form.

## Common problems

- Maven reports an unsupported release: install/select a JDK with Java 17 support, and check both `java -version` and `mvn -version`.
- The JAR is missing: run `mvn verify` from this directory.
- The worker waits silently: it is waiting for protocol input. Use the fixture runner.
- A protocol failure mentions stdout: remove debug output or move it to stderr.
- A raise is rejected: use the supplied target bounds, rather than treating the amount as added chips.
- State disappears: the host controls state passing; do not expect the worker to remember prior calls automatically.
- A dependency works locally but fails offline: declare it, pin it, and obtain approval so organizers can prepare it before execution.

Full field definitions and poker conventions are in `PROTOCOL.md`. Nothing in this starter opens submissions or confirms an event date.
