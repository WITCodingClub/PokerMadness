package club.pokermadness;

import club.pokermadness.api.*;
import club.pokermadness.bot.StarterBot;
import club.pokermadness.protocol.Protocol;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import static club.pokermadness.api.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    static JsonObject fixture(String name) throws Exception {
        return Protocol.parseObject(Files.readString(Path.of("examples",name+".json")));
    }
    static Protocol.Request request(String name) throws Exception {return Protocol.decode(fixture(name));}

    @TestFactory Stream<DynamicTest> allPublishedFixturesAreAccepted() throws Exception {
        JsonArray cases=JsonParser.parseString(Files.readString(Path.of("examples/cases.json"))).getAsJsonArray();
        return cases.asList().stream().map(c -> DynamicTest.dynamicTest(c.getAsJsonObject().get("name").getAsString(),() -> {
            Protocol.Request r=request(c.getAsJsonObject().get("name").getAsString());
            StarterBot bot=new StarterBot();
            JsonObject response=switch(r.type()) {
                case "init" -> Protocol.readyResponse(r);
                case "act" -> Protocol.actionResponse(r,bot.act(r.observation(),r.userState(),r.context()));
                default -> Protocol.ackResponse(r,bot.onHandEnd(r.result(),r.userState(),r.context()));
            };
            assertEquals(r.context().requestId(),Protocol.parseObject(Protocol.encode(response)).get("request_id").getAsString());
        }));
    }

    @Test void raiseTargetsAreTotalsAndBoundsAreInclusive() throws Exception {
        Observation o=request("raise-to").observation();
        assertEquals(10,o.hero().streetContribution());assertEquals(20,o.toCall());
        assertDoesNotThrow(() -> Protocol.validateAction(Action.raiseTo(50),o));
        assertDoesNotThrow(() -> Protocol.validateAction(Action.raiseTo(1000),o));
        assertThrows(IllegalArgumentException.class,() -> Protocol.validateAction(Action.raiseTo(49),o));
        assertThrows(IllegalArgumentException.class,() -> Protocol.validateAction(Action.raiseTo(20),o));
        assertThrows(IllegalArgumentException.class,() -> Protocol.validateAction(Action.betTo(50),o));
    }

    @Test void shortAllInRaiseIsASingletonRange() throws Exception {
        Observation o=request("short-all-in-raise").observation();
        assertDoesNotThrow(() -> Protocol.validateAction(Action.raiseTo(35),o));
        assertThrows(IllegalArgumentException.class,() -> Protocol.validateAction(Action.raiseTo(34),o));
        assertThrows(IllegalArgumentException.class,() -> Protocol.validateAction(Action.raiseTo(36),o));
    }

    @Test void shortCallUsesActualCostAndDoesNotAcceptAnAmount() throws Exception {
        Observation o=request("call-below-wager").observation();
        assertEquals(20,o.toCall());assertEquals(12,o.legal(ActionType.CALL).orElseThrow().amount());
        assertDoesNotThrow(() -> Protocol.validateAction(Action.call(),o));
        assertFalse(Protocol.GSON.toJsonTree(Action.call()).getAsJsonObject().has("amount"));
        assertThrows(IllegalArgumentException.class,() -> new Action(ActionType.CALL,12));
    }

    @Test void bettingReopeningIsTakenFromLegalActions() throws Exception {
        Observation o=request("bet-not-reopened").observation();
        assertFalse(o.can(ActionType.RAISE));
        assertThrows(IllegalArgumentException.class,() -> Protocol.validateAction(Action.raiseTo(230),o));
    }

    @Test void exampleCarriesRoundStatsOnlyThroughUserState() throws Exception {
        Protocol.Request first=request("free-check");StarterBot bot=new StarterBot();
        Decision d=bot.act(first.observation(),first.userState(),first.context());
        assertTrue(first.userState().isEmpty());assertEquals(1,d.userState().get("decisions_seen").getAsInt());
        JsonObject next=fixture("hand-end-demo");next.add("user_state",d.userState());
        Protocol.Request end=Protocol.decode(next);
        JsonObject state=new StarterBot().onHandEnd(end.result(),end.userState(),end.context());
        assertEquals(1,state.get("decisions_seen").getAsInt());assertEquals(1,state.get("hands_seen").getAsInt());
        assertEquals(50,state.get("net_chips").getAsInt());
        assertEquals(1,new StarterBot().act(first.observation(),new JsonObject(),first.context()).userState().get("decisions_seen").getAsInt());
    }

    @Test void invalidActionOrStateDoesNotCreateASuccessfulResponse() throws Exception {
        Protocol.Request r=request("cheap-call");
        assertThrows(IllegalArgumentException.class,() -> Protocol.actionResponse(r,new Decision(Action.check(),new JsonObject())));
        JsonObject oversized=new JsonObject();oversized.addProperty("x","a".repeat(65536));
        assertThrows(IllegalArgumentException.class,() -> Protocol.actionResponse(r,new Decision(Action.call(),oversized)));
    }

    @Test void utf8StateBoundaryAndSafeIntegers() {
        JsonObject exact=new JsonObject();exact.addProperty("x","a".repeat(Protocol.MAX_STATE_BYTES-8));
        assertEquals(Protocol.MAX_STATE_BYTES,Protocol.bytes(Protocol.GSON.toJson(exact)));
        assertDoesNotThrow(() -> Protocol.validateState(exact));
        exact.addProperty("x","é".repeat(Protocol.MAX_STATE_BYTES/2));
        assertThrows(IllegalArgumentException.class,() -> Protocol.validateState(exact));
        JsonObject huge=new JsonObject();huge.addProperty("counter",9007199254740992L);
        assertThrows(IllegalArgumentException.class,() -> Protocol.validateState(huge));
    }

    @Test void rejectsMalformedAndAmbiguousJson() {
        for(String bad:new String[]{"{\"x\":1,\"x\":2}","{} {}","{\"x\":NaN}","{\"x\":1,}","[]","{\"x\":\"\\ud800\"}","\ufeff{}"}) {
            assertThrows(Exception.class,() -> Protocol.parseObject(bad),bad);
        }
        assertThrows(Exception.class,() -> Protocol.parseObject("[".repeat(65)+"0"+"]".repeat(65)));
    }

    @Test void requiredTypesAndCardUniquenessAreEnforced() throws Exception {
        JsonObject fractional=fixture("cheap-call");fractional.getAsJsonObject("observation").addProperty("to_call",1.5);
        assertThrows(IllegalArgumentException.class,() -> Protocol.decode(fractional));
        JsonObject duplicate=fixture("cheap-call");duplicate.getAsJsonObject("observation").getAsJsonArray("hole_cards").set(1,new JsonPrimitive("As"));
        assertThrows(IllegalArgumentException.class,() -> Protocol.decode(duplicate));
    }

    @Test void rejectsUnsupportedVersionsAndAcceptsUnknownRequestFields() throws Exception {
        JsonObject f=fixture("cheap-call");f.addProperty("future_optional_field",true);assertDoesNotThrow(() -> Protocol.decode(f));
        f.addProperty("protocol_version","9.9");assertThrows(IllegalArgumentException.class,() -> Protocol.decode(f));
    }

    @Test void processLoopFlushesOneResponsePerRequestAndDoesNotStoreUserState() throws Exception {
        String input=Protocol.GSON.toJson(fixture("init"))+"\n"+Protocol.GSON.toJson(fixture("free-check"))+"\n"+Protocol.GSON.toJson(fixture("free-check"))+"\n";
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        Main.run(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),output);
        String[] lines=output.toString(StandardCharsets.UTF_8).split("\n");assertEquals(3,lines.length);
        assertEquals("ready",Protocol.parseObject(lines[0]).get("type").getAsString());
        for(int i=1;i<3;i++)assertEquals(1,Protocol.parseObject(lines[i]).getAsJsonObject("user_state").get("decisions_seen").getAsInt());
    }

    @Test void requiresInitializationAndRejectsBrokenFraming() throws Exception {
        byte[] withoutInit=(Protocol.GSON.toJson(fixture("free-check"))+"\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,() -> Main.run(new ByteArrayInputStream(withoutInit),new ByteArrayOutputStream()));
        assertThrows(Exception.class,() -> Main.run(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28,10}),new ByteArrayOutputStream()));
        assertThrows(Exception.class,() -> Main.run(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),new ByteArrayOutputStream()));
        assertThrows(Exception.class,() -> Main.run(new ByteArrayInputStream(new byte[]{10}),new ByteArrayOutputStream()));
    }

    @Test void seededRandomnessAndCardHelpersAreReproducible() throws Exception {
        BotContext c=request("free-check").context();assertEquals(c.random().nextLong(),c.random().nextLong());
        assertEquals(14,Card.parse("As").rank());assertEquals("Td",Card.parse("Td").toString());
        assertThrows(IllegalArgumentException.class,() -> Card.parse("10s"));
    }
}
