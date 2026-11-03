# Poker Madness bot protocol

Version 0.1. Review draft; Java hand lifecycle updated November 3, 2026.

This document specifies the boundary between the future poker engine and participant bots. It applies equally to Python, JavaScript, and Java. The Java starter implements this boundary with typed observations and a strategy entry point. The engine itself is outside this package.

## 1 Transport and worker lifecycle

The organizer launches a worker inside its isolated environment. Input is standard input. Output is standard output. Diagnostic messages belong on standard error. No network communication is part of this protocol.

Each message is exactly one UTF-8 JSON object followed by LF. CRLF is accepted. Pretty-printed multi-line messages, blank lines, byte-order marks, duplicate object keys, trailing JSON values, non-finite numbers, and invalid UTF-8 are invalid. Requests and responses must not contain executable code. JSON nesting is at most 64 containers. Strings may contain escaped newlines, not physical frame-breaking newlines.

Draft transport bounds, including the enclosing JSON but excluding the final LF:

| Item | Maximum UTF-8 bytes |
| --- | ---: |
| One engine request | 1,048,576 |
| One worker response | 131,072 |
| Serialized user_state object | 65,536 |

JSON objects are unordered. Unknown request fields are ignored within version 0.1, but unknown enum values, unsupported protocol versions, and missing required fields are errors. Responses must follow their schema without extra fields. A change to required fields, action meaning, or how the host carries `user_state` requires a new protocol version and a migration notice.

The engine sends one request at a time and waits for its response. There is no request pipelining. The worker flushes one response line for every request. On every worker launch, the host first sends `init` and waits for `ready` under the startup limit before sending decisions. After a round the engine closes stdin; the worker exits cleanly on EOF. No unsolicited output is allowed. A worker may process many requests for performance, and may be restarted between any two requests, followed by another init handshake.

**One strategy object per hand.** The Java wrapper creates the object on the first `act` or `hand_end` for that hand. It reuses the object for all betting streets and the hand result while the worker stays alive. The wrapper discards it after `hand_end` and starts the next hand with a fresh object. Instance fields may hold private working data for the current hand. A bot that gets a hand result without an earlier decision receives it on a fresh object.

**Use `user_state` to carry information between hands.** Learned information may not be kept in mutable static fields, files, background tasks, or any other memory that outlives the hand. Participants may cache declared, immutable precomputed data. If the host restarts a worker mid-hand, ordinary instance fields start fresh; recover from the current observation and history or the last accepted `user_state`. This Java lifecycle uses the same protocol 0.1 messages and state transfer.

## 2 Request envelope

All three request types have these fields:

| Field | Type | Meaning |
| --- | --- | --- |
| protocol_version | string | Exactly `0.1` |
| request_id | string | Unique opaque request identifier; echo unchanged |
| type | string | `init`, `act`, or `hand_end` |
| round_id | string | Opaque scope of the persistent state |
| bot_id | string | This entry's identifier; not an opponent identity map |
| random_seed | integer | 0 through 4,294,967,295; reproducible bot randomness |
| time_budget_ms | positive integer | Deadline allowance for this particular request |
| user_state | object | Last accepted state for this bot in this round, initially `{}` |

An `act` request additionally has `observation`. A `hand_end` request instead has `result`. An `init` request has neither and supplies an empty `user_state`; its `time_budget_ms` is the startup allowance. A ready response has `protocol_version`, the echoed `request_id`, `type: "ready"`, and empty `user_state`. Initialization may load declared immutable data but cannot update learned state. Restart initialization does not erase the host's previously accepted round state; the next act/hand_end request supplies that state again. No decision request is sent to a folded or all-in player. Every seated bot receives one hand-end request for each completed hand, including hands in which it folded.

In the Java starter, the optional `initialize(context)` hook runs on a separate setup object once per worker launch. It is for startup checks or preparing read-only data shared by hand instances. Use normal field initializers or a constructor to set up fields for each hand.

`random_seed` is independent of the deck RNG and contains no information about hidden cards or future deals. Identical seeds do not require identical random sequences across languages. The bot should use its supplied seed rather than wall time if it wants reproducible randomness.

`time_budget_ms` is duration metadata, not a timestamp. The host measures elapsed time from finishing delivery of the request line to receiving the complete response line. Parsing, decision work, state serialization, and flushing are included. Worker startup and approved immutable-data initialization have separate limits. Performance values are not finalized in this draft. Fixtures use 1,000 ms as illustrative metadata; the local harness defaults to a more generous diagnostic timeout and is not a host benchmark.

## 3 Persistent state and failure semantics

State is a JSON **object**, not an arbitrary memory snapshot or serialized executable. It may contain ordinary nested JSON values. State numbers must be finite; integer-valued state numbers must stay within JavaScript's exact integer range, -9,007,199,254,740,991 through 9,007,199,254,740,991. A new round starts with `{}` and new opponent aliases. State does not transfer between bots or rounds.

The engine validates an entire response before accepting its action or state. On success it commits the returned `user_state` and forwards it to the next request, including the next hand. A valid hand-end acknowledgment can update state using public results even if the bot made no decision in that hand.

For a malformed, oversized, mismatched, illegal, missing, or late response, or a worker crash: discard its state update, retain the previous accepted state, and apply check if legal, otherwise fold. A failed hand-end request only retains state; there is no poker action to replace. The engine may restart the worker after a failure. It does not retry the same request automatically. Failure counts and any suspension threshold must be published with the tournament settings; there is no hidden threshold in this draft.

Infrastructure failures are separate from bot failures. They follow the competition's rerun policy. This package's wrapper exits with a diagnostic on invalid input or strategy failure instead of concealing failures with a fabricated successful response.

## 4 Poker and numeric conventions

Cards use two ASCII characters: rank `2` through `9`, `T`, `J`, `Q`, `K`, or `A`; suit `c`, `d`, `h`, or `s`. Examples: `As`, `Kc`, `Td`. A ten is `T`, not `10`. There are no jokers. Visible physical cards cannot repeat. Hole cards are exactly two cards. Community-card counts are 0 preflop, 3 on the flop, 4 on the turn, and 5 on the river.

Chips are nonnegative integer-valued JSON numbers in 0 through 2,147,483,647. No floating chip amounts, strings, currency units, or negative wagers are accepted. Seat numbers are zero-based, contiguous, and arranged clockwise. Player aliases are opaque and stable within a round. They do not reveal other entries' authors or accounts.

Competition defaults are 100 big blinds per player at the start of every hand, fixed blinds, no ante, no rake, and no chip carryover. Exact chip denominations are settings, not hardcoded strategy assumptions. Fixtures use small blind 5, big blind 10, and initial stack 1,000. Cash results accumulate for scoring while stacks reset. Players remain seated for later hands even after losing their entire stack in one hand.

## 5 Act observation

| Field | Type | Meaning |
| --- | --- | --- |
| hand_id | string | Opaque identifier unique within the round |
| hand_number | positive integer | Sequential hand number within the round |
| street | string | `preflop`, `flop`, `turn`, or `river` |
| hero_seat | integer | Seat of the bot asked to act |
| button_seat | integer | Dealer button seat |
| small_blind | positive integer | Fixed small blind |
| big_blind | positive integer | Fixed big blind |
| starting_stack | positive integer | Hero's stack before posting blinds this hand |
| hole_cards | array of two cards | Only this bot's private cards |
| board | card array | Only already revealed community cards |
| players | player array | All seats, including folded and all-in players |
| pot | integer | Sum of net hand contributions, including pending unmatched wagers |
| pots | pot array | Current contribution layers; see below |
| current_bet | integer | Highest street commitment to match, including a short all-in |
| to_call | integer | `max(0, current_bet - hero.street_contribution)`, before stack capping |
| legal_actions | legal action array | Authoritative choices and bounds for this decision |
| history | public event array | Complete public history of this hand up to this request |

Each player has `seat`, `player_id`, `starting_stack`, `stack`, `street_contribution`, `hand_contribution`, and `status`. Status is `active`, `folded`, or `all_in`. `stack` excludes committed chips. Before settlement, stack plus hand contribution equals that player's starting stack. Contributions exclude already returned uncalled chips. Street contributions reset on each new street; hand contributions do not. The hero is `active` with positive remaining stack on every act request.

Official competition hands use equal starting stacks. The per-player field also lets compatibility fixtures represent unequal stacks for short-call and side-pot edge cases; those fixtures do not change the competition format.

Each pot layer has `amount` and `eligible_seats`. To construct layers, take distinct positive hand-contribution levels in ascending order. Each layer contains the increment from the previous level multiplied by the number of contributors reaching that level, including folded players. Eligible seats are non-folded contributors reaching that level. A top layer with a single contributor can represent an unmatched wager pending return. Do not interpret it as guaranteed winnings or a completed award. The layer amounts sum to `pot`; no payout has happened in an act observation.

The engine supplies legal actions after accounting for the full betting rules. Bots should use those actions instead of reconstructing whether betting has reopened. After all opponents are all-in, an unmatched actor may call or fold, but cannot bet uncontestable extra chips.

## 6 Legal actions and amounts

| Descriptor in observation | Valid response action |
| --- | --- |
| `{"action":"fold"}` | `{"action":"fold"}` |
| `{"action":"check"}` | `{"action":"check"}` |
| `{"action":"call","amount":20}` | `{"action":"call"}` |
| `{"action":"bet","min_amount":10,"max_amount":980}` | `{"action":"bet","amount":30}` within inclusive bounds |
| `{"action":"raise","min_amount":60,"max_amount":1000}` | `{"action":"raise","amount":80}` within inclusive bounds |

Legal descriptors contain no duplicate action types. Fold is available on every act request, including when checking is free. Check appears only when `to_call` is zero. Call appears only when `to_call` is positive, and its descriptor's amount is the actual chip cost, `min(to_call, hero.stack)`. The response omits a call amount so an all-in short call cannot be mis-sized.

Bet and raise response amounts are the **target total contribution on the current street**, not added chips, not the amount of the raise increment, and not the total contribution over the entire hand. Actual chips spent are `amount - hero.street_contribution`. The upper bound is the hero's street contribution plus remaining stack, subject to the engine's legal-action constraints. All integers in the inclusive legal interval are accepted.

There is no separate `all_in` action. A call can exhaust the stack. A bet or raise at `max_amount` can exhaust the stack. If the only aggressive move is an all-in smaller than a normal minimum, the legal range is a singleton: `min_amount == max_amount == hero.street_contribution + hero.stack`. No other sub-minimum amount is legal. If raising is not reopened, the raise descriptor is absent even if the bot has chips.

Examples:

- Hero already put in 10, current bet is 30, last full raise increment was 20. Calling spends 20. A minimum raise is **to 50**, spending 40 more. Returning raise amount 20 is invalid.
- Hero already put in 10 and has only 12 left, while current bet is 30. `to_call` is 20; the call descriptor amount is 12. Returning call commits those 12 and makes the hero all-in.
- Hero put in 10 and has 25 left against a current bet of 30. If raising is open, a short all-in raise is **to 35**, with minimum and maximum both 35. It does not automatically reopen betting for prior actors.
- On the flop the hero put in zero on that street. A bet to 30 spends 30, regardless of how much the hero put in preflop.

## 7 Action response

```json
{"protocol_version":"0.1","request_id":"example-act-1","type":"action","action":{"action":"call"},"user_state":{"decisions_seen":1}}
```

All five top-level fields are required. The request ID must match exactly. An action object has only `action`, and additionally `amount` for bet or raise. Unsolicited response fields and amounts on check/call/fold are invalid. An invalid state invalidates the whole response, even if its action was legal.

## 8 Hand end and acknowledgment

A `hand_end` request carries the common envelope plus `result`:

| Result field | Type | Meaning |
| --- | --- | --- |
| hand_id and hand_number | string and positive integer | Completed hand |
| hero_seat and button_seat | integers | Recipient seat and dealer position |
| starting_stack | positive integer | Initial stack before blinds |
| hole_cards | array of two cards | Recipient's own private cards, even if it folded |
| board | card array | Board actually revealed; no unrevealed runout after everyone folds |
| reason | string | `showdown` or `all_folded` |
| players | result player array | Final stacks, net winnings, and publicly shown cards |
| pots | settled pot array | Eligible seats and actual awards after uncalled returns |
| history | public event array | Full public hand history, including uncalled returns |

Result players have `seat`, `player_id`, `starting_stack`, `final_stack`, `net_chips`, and `shown_cards`. For each player, `net_chips = final_stack - starting_stack`; sum of all net chips is zero. `shown_cards` is either an empty array or two public cards. At showdown all non-folded hands are public in this digital event. Folded cards remain hidden from opponents. After an uncontested win no opponent's private cards are shown. The separate `result.hole_cards` always contains the recipient's own cards, including after a fold or failed act response; it does not expose those cards to any other recipient. If the hero's cards are publicly shown, its shown cards and private cards identify the same two cards.

A settled pot has `amount`, `eligible_seats`, and `awards`. Each award has `seat` and `amount`. Awards for that pot sum to its amount, and recipients must be eligible. Returned unmatched chips are excluded from settled pots and remain in the player's final stack. Each split pot's odd chips go to tied winners clockwise from the first seat left of the button.

```json
{"protocol_version":"0.1","request_id":"example-end-1","type":"ack","user_state":{"decisions_seen":1,"hands_seen":1,"net_chips":50}}
```

The acknowledgment has exactly these four top-level fields. It contains no action. Every bot receives its own observation/result plus its own state; no other bot's state is ever attached.

## 9 Public history events

Events have a zero-based contiguous `event_id`, `street`, and `type`. Type is `post_blind`, `action`, `return_uncalled`, or `deal_board`.

Monetary events include `seat` and `amount`, where amount is **new chips moved by that event**, unlike a bet/raise response target. `post_blind` additionally has `blind` (`small` or `big`) and `street_total`. An `action` event has `action`, `street_total`, `all_in`, and `fallback`; its amount is zero for check or fold, actual paid chips for call, and newly paid chips for bet/raise. `return_uncalled` has positive amount and the remaining `street_total` after the return. `deal_board` has `cards` containing only the newly revealed board cards and has no seat or amount. The schema describes the exact variants.

History is provided in full for the current hand, rather than as a delta, so a restarted worker does not need an in-memory replay cursor. A strategy accumulating statistics must avoid counting the same event again when it appears in subsequent requests. `hand_end` supports one final update per hand; do not count known actions twice.

## 10 Game conventions for the engine implementation

The game is no-limit Texas Hold'em with one standard deck and one board. Best five cards out of seven determine showdown, using zero, one, or two hole cards. Suits do not break ties; an ace can be low in A2345. There is no run-it-twice, straddle, rebuy, ante, or rake.

At tables of three or more, the first two seats clockwise after the button post the small and big blinds. Preflop action starts left of the big blind. On later streets, action starts left of the button among eligible players. For a two-player compatibility fixture, the button posts the small blind, acts first preflop and last postflop. Competition seating is planned for 4–6 entries; a 2–6 observation range makes the contract usable if the event later changes table size, without promising heads-up competition.

An opening full bet is at least one big blind, unless the bettor is all-in for less. A full raise increases the current wager by at least the last full bet or raise increment; the initial preflop increment is the big blind. A short all-in does not reduce that increment. Cumulative short all-ins can reopen action when a previously acting player now faces at least a full raise. The engine must also correctly handle completing an opening all-in smaller than a big blind. The authoritative min/max ranges eliminate the need for a bot to infer these edge cases itself.

Uncalled excess is returned before settlement. Each side pot is contested only by eligible non-folded contributors. Folding loses the claim to contributed chips. When only one non-folded player remains, the hand ends immediately without revealing future cards. When all remaining players are all-in and betting is settled, the engine deals the remaining board and proceeds to showdown.

The button advances one seat between hands. Because stacks reset, players do not bust out of a round. Blinds and chips still affect each hand's net result. Round scoring and seating are defined in the competition document.

These digital conventions govern this event. Poker TDA's rules are background references for betting and side-pot terminology, not wholesale adoption of live-tournament procedures: https://www.pokertda.com/view-poker-tda-rules/

## 11 Packaging and compatibility

The submission root contains `bot.json`, `STRATEGY.md`, source, dependency declarations, required data, and reproducible generation scripts for precomputed tables. The Java template uses `pom.xml`, `src/`, and optional `data/`. Its fixed entry point is `club.pokermadness.Main`; `BotFactory` selects the participant strategy class. The manifest never supplies a shell command.

The organizer rebuilds from submitted source and individually approved dependencies, before offline execution. A participant's local runnable JAR is a convenience and is not a substitute for source. Runtime data is read-only. Any source-generated tables used in a winning submission must be publishable and reproducible from the supplied generation instructions. Secrets, virtual environments, caches, and downloaded dependency trees do not belong in the ZIP.

The JSON Schemas define structural shape. Additional semantic invariants in this document, such as chip accounting, distinct cards, action legality, and state scope, also apply. Fixture-based checks and starter unit tests do not replace future engine integration tests or organizer review. Before official publication, all language wrappers must pass shared fixtures and a real engine must be checked against this contract.
