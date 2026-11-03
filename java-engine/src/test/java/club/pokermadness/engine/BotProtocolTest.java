package club.pokermadness.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static club.pokermadness.engine.TableView.*;
import static club.pokermadness.engine.TableView.ActionType.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class BotProtocolTest {
    private final Observation observation = HandTest.decision(HandTest.hand(0, 100, 100), 0);
    private final Wire.Request request =
            Wire.request(Wire.Kind.ACT, "r1", "round", "entry", 0, 100, new JsonObject(), observation);

    @Test
    void requestShowsOnlyTheActorsHoleCardsAndPublicInformation() {
        var root = JsonParser.parseString(new String(request.frame(), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject view = root.getAsJsonObject("observation");
        assertEquals(2, view.getAsJsonArray("hole_cards").size());
        assertEquals(0, view.getAsJsonArray("board").size());
        assertFalse(view.has("deck"));
        assertTrue(view.getAsJsonArray("players").asList().stream().noneMatch(p -> p.getAsJsonObject().has("hole_cards")));
        assertFalse(view.getAsJsonArray("legal_actions").get(0).getAsJsonObject().has("amount"));
        assertFalse(view.getAsJsonArray("history").get(0).getAsJsonObject().has("action"));
    }

    @Test
    void validNestedStateWithExplicitNullSurvivesTheNextRequest() throws BotFailure {
        Wire.Reply reply = Wire.response(request, reply("r1", "{\"action\":\"call\"}", "{\"keep\":[null],\"optional\":null}"),
                observation);
        assertEquals(CALL, reply.action().type());
        var next = Wire.request(Wire.Kind.ACT, "r2", "round", "entry", 0, 100, reply.state(), observation);
        var state = JsonParser.parseString(new String(next.frame(), StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonObject("user_state");
        assertTrue(state.get("optional").isJsonNull());
        assertTrue(state.getAsJsonArray("keep").get(0).isJsonNull());
    }

    @Test
    void rejectsMalformedUncorrelatedAndIllegalReplies() {
        for (byte[] bytes : List.of(
                reply("wrong", "{\"action\":\"call\"}", "{}"),
                reply("r1", "{\"action\":\"check\"}", "{}"),
                reply("r1", "{\"action\":\"call\",\"amount\":5}", "{}"),
                reply("r1", "{\"action\":\"raise\",\"amount\":19}", "{}"),
                reply("r1", "{\"action\":\"raise\",\"amount\":20.25}", "{}"),
                reply("r1", "{\"action\":\"call\"}", "{\"x\":1,\"x\":2}"),
                reply("r1", "{\"action\":\"call\"}", "{\"x\":9007199254740992}"),
                reply("r1", "{\"action\":\"call\"}", "{\"x\":\"" + "é".repeat(32768) + "\"}"),
                reply("r1", "{\"action\":\"call\"}", "{\"x\":" + "[".repeat(64) + "null" + "]".repeat(64) + "}"),
                new byte[]{(byte) 0xc3, (byte) 0x28})) {
            assertThrows(BotFailure.class, () -> Wire.response(request, bytes, observation));
        }
    }

    @Test
    void invalidReplyRollsBackStateEvenAfterWorkerRestarts() throws Exception {
        try (BotSession bot = bot("restart")) {
            assertEquals(CALL, bot.act(observation, 1000).type());
            assertThrows(BotFailure.class, () -> bot.act(observation, 1000));
            // A fresh worker only folds if the last accepted state (and not the bad update) reached it.
            assertEquals(FOLD, bot.act(observation, 1000).type());
        }
    }

    @Test
    void stuckBotIsTimedOutAndCanBeReplacedForHandEnd() throws Exception {
        try (BotSession bot = bot("stall")) {
            BotFailure failure = assertThrows(BotFailure.class, () -> bot.act(observation, 100));
            assertTrue(failure.getMessage().contains("100 ms"));
            Hand hand = HandTest.hand(0, 100, 100);
            HandTest.move(hand, 0, FOLD);
            assertNull(hand.nextDecision());
            assertDoesNotThrow(() -> bot.finish(hand.result(0), 1000));
        }
    }

    @Test
    void matchFoldsOnFailedCallAndStillSendsHandEnd() throws InterruptedException {
        var standings = match(1, "invalid", "normal");
        var broken = standings.stream().filter(s -> s.seat() == 1).findFirst().orElseThrow();
        assertEquals(-5, broken.netChips());
        assertEquals(1, broken.actionFailures());
        assertEquals(0, broken.handEndFailures());
        assertEquals(5, standings.get(0).netChips());
    }

    @Test
    void matchChecksOnFailedFreeActionAndContinuesToShowdown() throws InterruptedException {
        var standings = match(1, "normal", "invalid");
        var broken = standings.stream().filter(s -> s.seat() == 2).findFirst().orElseThrow();
        assertEquals(4, broken.actionFailures()); // Big-blind option, flop, turn and river.
        assertEquals(0, broken.handEndFailures());
        assertEquals(0, standings.stream().mapToLong(Match.Standing::netChips).sum());
    }

    @Test
    void realWorkerProcessesCarryStateAcrossHandsAndReplayASeed() throws InterruptedException {
        var first = match(12, "normal", "normal", "normal", "normal");
        var second = match(12, "normal", "normal", "normal", "normal");
        assertEquals(first, second);
        assertEquals(0, first.stream().mapToLong(Match.Standing::netChips).sum());
        assertTrue(first.stream().allMatch(s -> s.actionFailures() == 0 && s.handEndFailures() == 0));
    }

    private static List<Match.Standing> match(int hands, String... modes) throws InterruptedException {
        List<Match.Entrant> bots = java.util.Arrays.stream(modes)
                .map(mode -> new Match.Entrant(mode, launch(mode))).toList();
        return Match.run(new Match.Settings(hands, 5, 10, 10, 42, 5000, 1000, 1000, false),
                bots, new PrintWriter(new StringWriter()));
    }

    private static BotSession bot(String mode) {
        var counter = new AtomicInteger();
        return new BotSession("test", "round", launch(mode), 5000, () -> "r" + counter.incrementAndGet(), 0);
    }

    private static BotSession.Launch launch(String mode) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new BotSession.Launch(List.of(java, "-XX:-UsePerfData", "-cp", classpath,
                ScriptedWorker.class.getName(), mode), Path.of("").toAbsolutePath());
    }

    private static byte[] reply(String id, String action, String state) {
        return ("{\"protocol_version\":\"0.1\",\"request_id\":\"" + id
                + "\",\"type\":\"action\",\"action\":" + action + ",\"user_state\":" + state + "}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
