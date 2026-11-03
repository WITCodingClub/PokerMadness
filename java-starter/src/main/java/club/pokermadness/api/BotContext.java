package club.pokermadness.api;

import java.util.SplittableRandom;

public record BotContext(String requestId, String roundId, String botId,
                         long randomSeed, int timeBudgetMs) {
    /** Keep and reuse this generator locally within one invocation. */
    public SplittableRandom random() { return new SplittableRandom(randomSeed); }
}
