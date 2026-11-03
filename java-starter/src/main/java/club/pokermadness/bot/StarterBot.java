package club.pokermadness.bot;

import club.pokermadness.api.*;
import com.google.gson.JsonObject;
import static club.pokermadness.api.Models.*;

/** Participant strategy entry point. This is a legal illustrative strategy, not a proven strong bot. */
public final class StarterBot implements PokerBot {
    @Override public Decision act(Observation observation, JsonObject userState, BotContext context) {
        JsonObject nextState = userState.deepCopy();
        increment(nextState, "decisions_seen", 1);

        // Replace this policy. Instance fields last through this hand; use nextState across hands.
        Action action;
        if (observation.can(ActionType.CHECK)) {
            action = Action.check();
        } else {
            long limit = 2L * observation.bigBlind();
            // Starting-hand pair strength is used only preflop.
            if (observation.street() == Street.PREFLOP
                    && observation.holeCards().get(0).rank() == observation.holeCards().get(1).rank()) {
                limit = 4L * observation.bigBlind();
            }
            // The call descriptor gives the actual cost, including a short all-in call.
            LegalAction call = observation.legal(ActionType.CALL).orElse(null);
            action = call != null && call.amount() <= limit ? Action.call() : Action.fold();
        }
        return new Decision(action, nextState);
    }

    @Override public JsonObject onHandEnd(HandResult result, JsonObject userState, BotContext context) {
        JsonObject nextState = userState.deepCopy();
        increment(nextState, "hands_seen", 1);
        increment(nextState, "net_chips", result.hero().netChips());
        return nextState;
    }

    private static void increment(JsonObject state, String key, long amount) {
        long previous = state.has(key) ? state.get(key).getAsLong() : 0;
        state.addProperty(key, Math.addExact(previous, amount));
    }
}
