package club.pokermadness.engine;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static club.pokermadness.engine.TableView.*;

/** Protocol 0.1 request construction and strict validation of worker responses. */
final class Wire {
    static final int MAX_REQUEST = 1_048_576;
    static final int MAX_RESPONSE = 131_072;
    static final int MAX_STATE = 65_536;
    private static final int MAX_DEPTH = 64;
    private static final BigDecimal MAX_SAFE_INTEGER = new BigDecimal("9007199254740991");
    private static final String VERSION = "0.1";
    // Omit inactive variant fields in table snapshots; preserve explicit nulls inside user_state.
    private static final Gson VIEWS = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Set<String> BASIC_KEYS = Set.of("protocol_version", "request_id", "type", "user_state");
    private static final Set<String> ACTION_KEYS = Set.of("protocol_version", "request_id", "type", "user_state", "action");

    private Wire() {}

    enum Kind {
        INIT("init", "ready", null),
        ACT("act", "action", "observation"),
        END("hand_end", "ack", "result");

        private final String requestType;
        private final String responseType;
        private final String bodyName;

        Kind(String requestType, String responseType, String bodyName) {
            this.requestType = requestType;
            this.responseType = responseType;
            this.bodyName = bodyName;
        }

    }

    record Request(String id, Kind kind, byte[] frame) {}
    record Reply(Action action, JsonObject state) {}

    static Request request(Kind kind, String requestId, String roundId, String botId,
                           long seed, int budgetMs, JsonObject state, Object body) {
        var request = new JsonObject();
        request.addProperty("protocol_version", VERSION);
        request.addProperty("request_id", requestId);
        request.addProperty("type", kind.requestType);
        request.addProperty("round_id", roundId);
        request.addProperty("bot_id", botId);
        request.addProperty("random_seed", seed);
        request.addProperty("time_budget_ms", budgetMs);
        request.add("user_state", kind == Kind.INIT ? new JsonObject() : state);
        if (kind.bodyName != null) request.add(kind.bodyName, VIEWS.toJsonTree(body));
        byte[] frame = (JSON.toJson(request) + "\n").getBytes(StandardCharsets.UTF_8);
        if (frame.length > MAX_REQUEST + 1) throw new IllegalStateException("Engine request exceeds protocol limit");
        return new Request(requestId, kind, frame);
    }

    static Reply response(Request request, byte[] bytes, Observation observation) throws BotFailure {
        try {
            require(bytes.length > 0 && bytes.length <= MAX_RESPONSE, "Invalid response size");
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            require(text.charAt(0) != '\ufeff', "Unexpected byte-order mark");
            JsonObject root;
            try (var reader = new JsonReader(new StringReader(text))) {
                reader.setStrictness(Strictness.STRICT);
                root = object(read(reader, 0), "Response must be an object");
                require(reader.peek() == JsonToken.END_DOCUMENT, "Trailing content");
            }
            boolean act = request.kind() == Kind.ACT;
            require(root.keySet().equals(act ? ACTION_KEYS : BASIC_KEYS), "Wrong response fields");
            require(VERSION.equals(string(root.get("protocol_version"))), "Unsupported protocol version");
            require(request.id().equals(string(root.get("request_id"))), "Response request ID does not match");
            require(request.kind().responseType.equals(string(root.get("type"))), "Wrong response type");
            JsonObject state = object(root.get("user_state"), "user_state must be an object");
            require(JSON.toJson(state).getBytes(StandardCharsets.UTF_8).length <= MAX_STATE, "user_state exceeds 64 KiB");
            checkStateNumbers(state);
            if (request.kind() == Kind.INIT) require(state.isEmpty(), "Ready state must be empty");
            if (!act) return new Reply(null, state);

            JsonObject candidate = object(root.get("action"), "Action must be an object");
            var type = switch (string(candidate.get("action"))) {
                case "fold" -> ActionType.FOLD;
                case "check" -> ActionType.CHECK;
                case "call" -> ActionType.CALL;
                case "bet" -> ActionType.BET;
                case "raise" -> ActionType.RAISE;
                default -> throw new BotFailure("Unknown poker action");
            };
            require(candidate.keySet().equals(type.isAggressive() ? Set.of("action", "amount") : Set.of("action")),
                    "Wrong action fields");
            Integer amount = type.isAggressive() ? chipAmount(candidate.get("amount")) : null;
            var action = new Action(type, amount);
            require(observation.allows(action), "Action is not legal for this decision");
            return new Reply(action, state);
        } catch (IOException | IllegalStateException | IllegalArgumentException | ArithmeticException e) {
            throw new BotFailure("Malformed response JSON", e);
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException, BotFailure {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                require(depth < MAX_DEPTH, "Response JSON is too deeply nested");
                var object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    checkUnicode(key);
                    require(!object.has(key), "Duplicate JSON key");
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                require(depth < MAX_DEPTH, "Response JSON is too deeply nested");
                var array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case NUMBER -> {
                BigDecimal value = new BigDecimal(reader.nextString());
                require(Double.isFinite(value.doubleValue()), "Non-finite number");
                yield new JsonPrimitive(value);
            }
            case STRING -> {
                String value = reader.nextString();
                checkUnicode(value);
                yield new JsonPrimitive(value);
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new BotFailure("Expected a JSON value");
        };
    }

    private static void checkStateNumbers(JsonElement element) throws BotFailure {
        if (element.isJsonObject()) {
            for (JsonElement child : element.getAsJsonObject().asMap().values()) checkStateNumbers(child);
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) checkStateNumbers(child);
        } else if (element instanceof JsonPrimitive primitive && primitive.isNumber()) {
            BigDecimal number = primitive.getAsBigDecimal();
            require(number.stripTrailingZeros().scale() > 0 || number.abs().compareTo(MAX_SAFE_INTEGER) <= 0,
                    "user_state integer exceeds the exact cross-language range");
        }
    }

    private static int chipAmount(JsonElement element) throws BotFailure {
        require(element instanceof JsonPrimitive p && p.isNumber(), "Expected a numeric chip amount");
        BigDecimal number = element.getAsBigDecimal();
        require(number.stripTrailingZeros().scale() <= 0 && number.signum() > 0
                        && number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0,
                "Expected a positive integer chip amount");
        return number.intValueExact();
    }

    private static void checkUnicode(String string) throws BotFailure {
        for (int i = 0; i < string.length(); i++) {
            char unit = string.charAt(i);
            if (Character.isHighSurrogate(unit)) {
                require(++i < string.length() && Character.isLowSurrogate(string.charAt(i)),
                        "Unpaired Unicode surrogate");
            } else {
                require(!Character.isLowSurrogate(unit), "Unpaired Unicode surrogate");
            }
        }
    }

    private static JsonObject object(JsonElement value, String message) throws BotFailure {
        require(value != null && value.isJsonObject(), message);
        return value.getAsJsonObject();
    }

    private static String string(JsonElement value) throws BotFailure {
        require(value instanceof JsonPrimitive p && p.isString(), "Expected a string field");
        return value.getAsString();
    }

    private static void require(boolean condition, String reason) throws BotFailure {
        if (!condition) throw new BotFailure(reason);
    }
}
