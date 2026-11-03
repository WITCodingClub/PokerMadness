package club.pokermadness.api;

import com.google.gson.JsonObject;
import static club.pokermadness.api.Models.*;

/** One strategy instance serves a hand while its worker remains alive. Use userState across hands. */
public interface PokerBot {
    /**
     * Optional worker setup, once per process launch under the startup allowance.
     * The startup instance is discarded. Use field initializers or a constructor for hand fields,
     * and this hook for setup checks or declared read-only caches shared by hand instances.
     */
    default void initialize(BotContext context) {}

    /** Called on the current hand's instance. Return any state the engine should carry forward. */
    Decision act(Observation observation, JsonObject userState, BotContext context);

    /**
     * Called on the hand's instance, even if the bot folded. If this is the first request the
     * worker receives for the hand, the instance is fresh. Return the state for the next hand.
     */
    default JsonObject onHandEnd(HandResult result, JsonObject userState, BotContext context) {
        return userState;
    }
}
