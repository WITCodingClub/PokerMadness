package club.pokermadness;

import club.pokermadness.api.Action;
import club.pokermadness.api.BotContext;
import club.pokermadness.api.Decision;
import club.pokermadness.api.Models.ActionType;
import club.pokermadness.api.Models.HandResult;
import club.pokermadness.api.Models.Observation;
import club.pokermadness.api.PokerBot;
import club.pokermadness.protocol.Protocol;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandLifecycleTest {
    @Test
    void streetsAndResultShareOneObjectAndTheNextHandStartsFresh() throws Exception {
        ProbeFactory probe = new ProbeFactory();
        List<JsonObject> replies = run(probe,
                ProtocolTest.fixture("init"),
                message("cheap-call", "preflop-1", "round", 1, new JsonObject()),
                message("flop-open-bet", "flop-1", "round", 1, new JsonObject()),
                message("hand-end-demo", "end-1", "round", 1, new JsonObject()),
                message("cheap-call", "preflop-2", "round", 2, state("completed", 1)),
                message("hand-end-demo", "end-2", "round", 2, state("completed", 1)),
                message("hand-end-demo", "end-3", "round", 3, state("completed", 2)));

        assertEquals(List.of("init:1", "preflop-1:2", "flop-1:2", "end-1:2",
                "preflop-2:3", "end-2:3", "end-3:4"), probe.calls);
        assertTrue(userState(replies.get(0)).isEmpty());
        assertEquals(2, userState(replies.get(3)).get("acts_this_hand").getAsInt());
        assertEquals(1, userState(replies.get(3)).get("completed").getAsInt());
        assertEquals(1, userState(replies.get(4)).get("acts_this_hand").getAsInt());
        assertEquals(1, userState(replies.get(4)).get("completed").getAsInt());
        assertEquals(2, userState(replies.get(5)).get("completed").getAsInt());
        assertEquals(0, userState(replies.get(6)).get("acts_this_hand").getAsInt());
        assertEquals(3, userState(replies.get(6)).get("completed").getAsInt());
    }

    @Test
    void aReplacementWorkerStartsWithFreshFieldsAndCanReadSavedState() throws Exception {
        ProbeFactory original = new ProbeFactory();
        List<JsonObject> before = run(original, ProtocolTest.fixture("init"),
                message("cheap-call", "preflop-1", "round", 1, state("completed", 5)));

        ProbeFactory replacement = new ProbeFactory();
        List<JsonObject> after = run(replacement, ProtocolTest.fixture("init"),
                message("flop-open-bet", "flop-1", "round", 1, userState(before.get(1))),
                message("hand-end-demo", "end-1", "round", 1, userState(before.get(1))));

        assertEquals(List.of("init:1", "flop-1:2", "end-1:2"), replacement.calls);
        assertEquals(1, userState(after.get(1)).get("acts_this_hand").getAsInt());
        assertEquals(5, userState(after.get(1)).get("completed").getAsInt());
        assertEquals(6, userState(after.get(2)).get("completed").getAsInt());
    }

    @Test
    void repeatedHandIdsInDifferentRoundsCannotReuseAnInstance() throws Exception {
        ProbeFactory probe = new ProbeFactory();
        List<JsonObject> replies = run(probe, ProtocolTest.fixture("init"),
                message("cheap-call", "round-one", "one", 1, state("completed", 5)),
                message("cheap-call", "round-two", "two", 1, new JsonObject()));

        assertEquals(List.of("init:1", "round-one:2", "round-two:3"), probe.calls);
        assertEquals(1, userState(replies.get(2)).get("acts_this_hand").getAsInt());
        assertFalse(userState(replies.get(2)).has("completed"));
    }

    private static JsonObject message(String fixture, String requestId, String round, int hand,
                                      JsonObject state) throws Exception {
        JsonObject request = ProtocolTest.fixture(fixture);
        request.addProperty("request_id", requestId);
        request.addProperty("round_id", round);
        request.add("user_state", state);
        JsonObject payload = request.getAsJsonObject(request.has("observation") ? "observation" : "result");
        payload.addProperty("hand_id", "hand-" + hand);
        payload.addProperty("hand_number", hand);
        return request;
    }

    private static JsonObject state(String key, int value) {
        JsonObject state = new JsonObject();
        state.addProperty(key, value);
        return state;
    }

    private static JsonObject userState(JsonObject reply) {
        return reply.getAsJsonObject("user_state");
    }

    private static List<JsonObject> run(Supplier<PokerBot> factory, JsonObject... requests) throws Exception {
        StringBuilder input = new StringBuilder();
        for (JsonObject request : requests) input.append(Protocol.GSON.toJson(request)).append('\n');
        var output = new ByteArrayOutputStream();
        Main.run(new ByteArrayInputStream(input.toString().getBytes(StandardCharsets.UTF_8)), output, factory);
        List<JsonObject> replies = new ArrayList<>();
        for (String line : output.toString(StandardCharsets.UTF_8).lines().toList()) {
            replies.add(Protocol.parseObject(line));
        }
        assertEquals(requests.length, replies.size());
        return replies;
    }

    private static final class ProbeFactory implements Supplier<PokerBot> {
        private int instances;
        private final List<String> calls = new ArrayList<>();

        @Override
        public PokerBot get() {
            int instance = ++instances;
            return new PokerBot() {
                private int actsThisHand;

                @Override
                public void initialize(BotContext context) {
                    calls.add("init:" + instance);
                }

                @Override
                public Decision act(Observation observation, JsonObject state, BotContext context) {
                    calls.add(context.requestId() + ":" + instance);
                    state.addProperty("acts_this_hand", ++actsThisHand);
                    Action action = observation.can(ActionType.CALL) ? Action.call() : observation.safeFallback();
                    return new Decision(action, state);
                }

                @Override
                public JsonObject onHandEnd(HandResult result, JsonObject state, BotContext context) {
                    calls.add(context.requestId() + ":" + instance);
                    state.addProperty("acts_this_hand", actsThisHand);
                    int previous = state.has("completed") ? state.get("completed").getAsInt() : 0;
                    state.addProperty("completed", previous + 1);
                    return state;
                }
            };
        }
    }
}
