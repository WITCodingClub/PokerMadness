package club.pokermadness.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Small independent protocol peer run in a real subprocess by the integration tests. */
public final class ScriptedWorker {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int calls = 0;
        boolean ready = false;
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            JsonObject request = JsonParser.parseString(line).getAsJsonObject();
            var reply = new JsonObject();
            reply.add("protocol_version", request.get("protocol_version"));
            reply.add("request_id", request.get("request_id"));
            JsonObject state = request.getAsJsonObject("user_state").deepCopy();
            switch (request.get("type").getAsString()) {
                case "init" -> {
                    if (ready || !state.isEmpty()) throw new IllegalArgumentException("Expected fresh init");
                    ready = true;
                    reply.addProperty("type", "ready");
                }
                case "act" -> {
                    if (!ready) throw new IllegalArgumentException("Missing init");
                    JsonObject view = request.getAsJsonObject("observation");
                    String action = view.get("to_call").getAsInt() == 0 ? "check" : "call";
                    calls++;
                    switch (mode) {
                        case "stall" -> Thread.sleep(60_000);
                        case "invalid" -> action = "illegal";
                        case "restart" -> {
                            if (calls == 2) {
                                action = "check"; // Fixture faces a call.
                                state.addProperty("seen", 99);
                            } else if (state.has("seen") && state.get("seen").getAsInt() == 1) {
                                action = "fold";
                            } else {
                                state.addProperty("seen", 1);
                            }
                        }
                        default -> {
                            int hand = view.get("hand_number").getAsInt();
                            int completed = state.has("completed") ? state.get("completed").getAsInt() : 0;
                            if (completed != hand - 1) throw new IllegalArgumentException("Lost accepted hand-end state");
                        }
                    }
                    reply.addProperty("type", "action");
                    var move = new JsonObject();
                    move.addProperty("action", action);
                    reply.add("action", move);
                }
                case "hand_end" -> {
                    if (!ready) throw new IllegalArgumentException("Missing init");
                    JsonObject result = request.getAsJsonObject("result");
                    int hand = result.get("hand_number").getAsInt();
                    int seats = result.getAsJsonArray("players").size();
                    if (result.getAsJsonArray("hole_cards").size() != 2
                            || result.get("button_seat").getAsInt() != (hand - 1) % seats) {
                        throw new IllegalArgumentException("Incorrect hand-end result");
                    }
                    state.addProperty("completed", hand);
                    reply.addProperty("type", "ack");
                }
                default -> throw new IllegalArgumentException("Unexpected request");
            }
            reply.add("user_state", state);
            System.out.println(reply);
            System.out.flush();
        }
    }
}
