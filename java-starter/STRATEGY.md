# Strategy explanation

Replace the example explanation before submitting.

## Decision rules

The example checks when checking is free. Otherwise it calls up to two big blinds, increasing that limit to four big blinds for a pocket pair before the flop. It folds other calls and does not bet or raise. These thresholds are arbitrary and have not been measured against opponents.

## Hand fields and persistent state

The example stores decision count, completed-hand count, and cumulative net chips in `user_state`. It does not use these counters to select moves. Hand-end notifications update results even when the bot folded. If you add instance fields for the current hand, explain what they hold and how the bot handles a worker restart. Only `user_state` can carry learned information to the next hand.

## Randomness and precomputation

The example uses neither randomness nor precomputed data. If adding either, explain the seed usage, data contents, generator source, parameters, generation cost and reproduction commands here. Non-neural precomputation is allowed; neural playing policies are not.

## Dependencies

Gson 2.13.2 provides JSON handling in the organizer wrapper. Maven resolves its declared annotation dependency. JUnit Jupiter is used only for tests. No poker-specific dependency is included. List additional dependencies and request them on the form.

## Known weaknesses

The example ignores board texture, pot odds, position, opponent behavior, and almost all hand strength. It is predictable and passive. Passing protocol checks demonstrates compatibility, not poker strength.

## Contributors and entry identity

Set all actual contributor names in `bot.json`, and provide private contact information on the form. Each contributor may be involved with at most three bot entries overall. Leave `entry_id` null until organizers assign one, then use it for revisions.
