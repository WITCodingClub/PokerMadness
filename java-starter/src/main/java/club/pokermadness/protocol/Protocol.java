package club.pokermadness.protocol;

import club.pokermadness.api.*;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static club.pokermadness.api.Models.*;

/** Organizer transport validation. Participants normally edit bot/StarterBot.java. */
public final class Protocol {
    public static final String VERSION = "0.1";
    public static final int MAX_REQUEST_BYTES = 1_048_576;
    public static final int MAX_RESPONSE_BYTES = 131_072;
    public static final int MAX_STATE_BYTES = 65_536;
    public static final int MAX_DEPTH = 64;
    public static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setStrictness(Strictness.STRICT).disableHtmlEscaping().disableJdkUnsafe()
            .registerTypeAdapter(Card.class, new TypeAdapter<Card>() {
                @Override public Card read(JsonReader reader) throws IOException { return Card.parse(reader.nextString()); }
                @Override public void write(JsonWriter writer, Card card) throws IOException { writer.value(card.toString()); }
            }).create();

    private Protocol() {}

    public record Request(String type, BotContext context, JsonObject userState,
                          Observation observation, HandResult result) {}

    public static JsonObject parseObject(String line) throws IOException {
        require(!line.isEmpty() && line.charAt(0)!='\ufeff', "Blank input or byte-order mark is not allowed");
        try (JsonReader reader = new JsonReader(new StringReader(line))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = readValue(reader, 0);
            require(reader.peek() == JsonToken.END_DOCUMENT, "Trailing JSON content");
            require(value.isJsonObject(), "Message must be an object");
            return value.getAsJsonObject();
        }
    }

    private static JsonElement readValue(JsonReader reader, int depth) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                require(depth < MAX_DEPTH, "JSON nesting exceeds " + MAX_DEPTH);
                JsonObject object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName(); validUnicode(name);
                    require(!object.has(name), "Duplicate object key: " + name);
                    object.add(name, readValue(reader, depth + 1));
                }
                reader.endObject(); return object;
            }
            case BEGIN_ARRAY -> {
                require(depth < MAX_DEPTH, "JSON nesting exceeds " + MAX_DEPTH);
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(readValue(reader, depth + 1));
                reader.endArray(); return array;
            }
            case STRING -> { String text = reader.nextString(); validUnicode(text); return new JsonPrimitive(text); }
            case NUMBER -> {
                BigDecimal number = new BigDecimal(reader.nextString());
                require(Double.isFinite(number.doubleValue()), "Number outside finite double range");
                return new JsonPrimitive(number);
            }
            case BOOLEAN -> { return new JsonPrimitive(reader.nextBoolean()); }
            case NULL -> { reader.nextNull(); return JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("Expected a JSON value");
        }
    }

    private static void validUnicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                require(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(++i)), "Unpaired Unicode surrogate");
            } else require(!Character.isLowSurrogate(c), "Unpaired Unicode surrogate");
        }
    }

    public static Request decode(JsonObject root) {
        require(VERSION.equals(string(root, "protocol_version")), "Unsupported protocol version");
        String type = string(root, "type");
        require(type.equals("init") || type.equals("act") || type.equals("hand_end"), "Unknown request type");
        BotContext context = new BotContext(string(root,"request_id"), string(root,"round_id"),
                string(root,"bot_id"), integer(root,"random_seed",0,4_294_967_295L), (int)integer(root,"time_budget_ms",1,Integer.MAX_VALUE));
        JsonObject state = object(root,"user_state"); validateState(state);
        if (type.equals("init")) {
            require(state.isEmpty(), "Initialization state must be empty");
            return new Request(type,context,state.deepCopy(),null,null);
        }
        if (type.equals("act")) {
            JsonObject source = object(root,"observation"); validateObservation(source);
            return new Request(type,context,state.deepCopy(),GSON.fromJson(source,Observation.class),null);
        }
        JsonObject source = object(root,"result"); validateResult(source);
        return new Request(type,context,state.deepCopy(),null,GSON.fromJson(source,HandResult.class));
    }

    private static void validateObservation(JsonObject o) {
        string(o,"hand_id"); positive(o,"hand_number");
        String street = choice(o,"street","preflop","flop","turn","river");
        int boardCount = switch (street) { case "preflop" -> 0; case "flop" -> 3; case "turn" -> 4; default -> 5; };
        Set<String> visible = new HashSet<>();
        cards(array(o,"hole_cards",2,2),visible); cards(array(o,"board",boardCount,boardCount),visible);
        positive(o,"small_blind"); positive(o,"big_blind"); positive(o,"starting_stack");
        require(number(o,"small_blind") <= number(o,"big_blind"), "Small blind exceeds big blind");
        JsonArray players = array(o,"players",2,6); int count = players.size();
        int hero = (int)integer(o,"hero_seat",0,count-1); integer(o,"button_seat",0,count-1);
        Set<String> aliases = new HashSet<>(); long contributed=0; int largest=0;
        for (int i=0;i<count;i++) {
            JsonObject p=asObject(players.get(i),"player"); require(number(p,"seat")==i,"Players must be ordered by contiguous seat");
            require(aliases.add(string(p,"player_id")),"Duplicate player alias"); positive(p,"starting_stack");
            int stack=number(p,"stack"), hand=number(p,"hand_contribution"), current=number(p,"street_contribution");
            require((long)stack+hand==number(p,"starting_stack"),"Stack and contribution do not reconcile");
            require(current<=hand,"Street contribution exceeds hand contribution");
            String status=choice(p,"status","active","folded","all_in");
            require(!status.equals("all_in") || stack==0,"All-in player has chips left");
            require(!status.equals("active") || stack>0,"Active player needs chips");
            contributed+=hand; largest=Math.max(largest,current);
        }
        JsonObject self=players.get(hero).getAsJsonObject();
        require("active".equals(string(self,"status")),"Hero is not active");
        require(number(o,"starting_stack")==number(self,"starting_stack"),"Hero initial stack mismatch");
        int pot=number(o,"pot"), currentBet=number(o,"current_bet"), toCall=number(o,"to_call");
        require(contributed==pot,"Pot does not equal hand contributions");
        require(currentBet==largest,"Current bet does not match street commitments");
        require(toCall==Math.max(0,currentBet-number(self,"street_contribution")),"to_call mismatch");
        long potSum=0;
        for (JsonElement e:array(o,"pots",0,6)) {
            JsonObject p=asObject(e,"pot"); potSum+=positive(p,"amount"); seats(p,"eligible_seats",count);
        }
        require(potSum==pot,"Pot layers do not sum to pot");
        Set<String> actionNames=new HashSet<>();
        for (JsonElement e:array(o,"legal_actions",1,5)) {
            JsonObject a=asObject(e,"legal action"); String name=choice(a,"action","fold","check","call","bet","raise");
            require(actionNames.add(name),"Duplicate legal action");
            if (name.equals("check")) require(toCall==0,"Check offered facing a bet");
            if (name.equals("call")) {
                require(toCall>0,"Call offered for zero cost");
                require(positive(a,"amount")==Math.min(toCall,number(self,"stack")),"Call cost mismatch");
            }
            if (name.equals("bet") || name.equals("raise")) {
                int min=positive(a,"min_amount"),max=positive(a,"max_amount");
                require(min<=max && min>currentBet,"Invalid aggressive target bounds");
                require(max==(long)number(self,"stack")+number(self,"street_contribution"),"Aggressive upper bound must equal street capacity");
                require(name.equals("bet")== (currentBet==0),"Bet versus raise does not match current wager");
            }
        }
        require(actionNames.contains("fold"),"Fold must be available");
        history(o,count);
    }

    private static void validateResult(JsonObject r) {
        string(r,"hand_id");positive(r,"hand_number");positive(r,"starting_stack");
        String reason=choice(r,"reason","showdown","all_folded");
        Set<String> visible=new HashSet<>(); JsonArray privateCards=array(r,"hole_cards",2,2);cards(privateCards,visible);
        Set<String> heroCards=new HashSet<>(visible);JsonArray board=array(r,"board",0,5);
        require(Set.of(0,3,4,5).contains(board.size()),"Invalid revealed board length");
        require(!reason.equals("showdown") || board.size()==5,"Showdown needs five board cards");cards(board,visible);
        JsonArray players=array(r,"players",2,6);int count=players.size();
        int hero=(int)integer(r,"hero_seat",0,count-1);integer(r,"button_seat",0,count-1);
        Set<String> aliases=new HashSet<>();long net=0;
        for (int i=0;i<count;i++) {
            JsonObject p=asObject(players.get(i),"result player");require(number(p,"seat")==i,"Result seats must be contiguous");
            require(aliases.add(string(p,"player_id")),"Duplicate result alias");
            int start=positive(p,"starting_stack"),finish=number(p,"final_stack");
            long delta=integer(p,"net_chips",-Integer.MAX_VALUE,Integer.MAX_VALUE);
            require((long)finish-start==delta,"Net result does not reconcile");net+=delta;
            JsonArray shown=array(p,"shown_cards",0,2);require(shown.size()==0 || shown.size()==2,"Shown cards need zero or two cards");
            require(!reason.equals("all_folded") || shown.size()==0,"Uncontested win must not expose cards");
            if(i==hero && shown.size()==2) {Set<String> shownSet=new HashSet<>();cards(shown,shownSet);require(shownSet.equals(heroCards),"Hero shown cards disagree with private cards");}
            else cards(shown,visible);
        }
        require(net==0,"Results must sum to zero");
        require(number(r,"starting_stack")==number(players.get(hero).getAsJsonObject(),"starting_stack"),"Result hero initial stack mismatch");
        for (JsonElement e:array(r,"pots",0,6)) {
            JsonObject pot=asObject(e,"settled pot");int amount=positive(pot,"amount");Set<Integer> eligible=seats(pot,"eligible_seats",count);
            Set<Integer> winners=new HashSet<>();long paid=0;
            for (JsonElement a:array(pot,"awards",1,6)) {
                JsonObject award=asObject(a,"award");int seat=(int)integer(award,"seat",0,count-1);
                require(eligible.contains(seat) && winners.add(seat),"Ineligible or duplicate award recipient");paid+=positive(award,"amount");
            }
            require(paid==amount,"Awards do not sum to pot");
        }
        history(r,count);
    }

    private static void history(JsonObject root,int count) {
        JsonArray events=array(root,"history",0,10000);
        for (int i=0;i<events.size();i++) {
            JsonObject e=asObject(events.get(i),"history event");require(number(e,"event_id")==i,"History event IDs must be contiguous");
            choice(e,"street","preflop","flop","turn","river");String type=choice(e,"type","post_blind","action","return_uncalled","deal_board");
            if (type.equals("deal_board")) { cards(array(e,"cards",1,3),new HashSet<>());continue; }
            integer(e,"seat",0,count-1);number(e,"street_total");int amount=number(e,"amount");
            if (type.equals("post_blind")) { choice(e,"blind","small","big");require(amount>0,"Blind must be positive"); }
            if (type.equals("return_uncalled")) require(amount>0,"Return must be positive");
            if (type.equals("action")) {
                String action=choice(e,"action","fold","check","call","bet","raise");bool(e,"all_in");bool(e,"fallback");
                require(!(action.equals("fold") || action.equals("check")) || amount==0,"Check/fold cannot move chips");
                require(!(action.equals("call") || action.equals("bet") || action.equals("raise")) || amount>0,"Paid action needs chips");
            }
        }
    }

    public static void validateAction(Action action,Observation observation) {
        LegalAction legal=observation.legal(action.action()).orElseThrow(() -> new IllegalArgumentException("Strategy returned an unavailable action"));
        if (legal.isAggressive()) require(legal.allowsTarget(action.amount()),"Bet/raise target outside legal range");
    }

    public static JsonObject actionResponse(Request request,Decision decision) {
        validateAction(decision.action(),request.observation());validateState(decision.userState());
        JsonObject response=envelope(request,"action");response.add("action",GSON.toJsonTree(decision.action()));
        response.add("user_state",decision.userState().deepCopy());return response;
    }

    public static JsonObject ackResponse(Request request,JsonObject state) {
        validateState(state);JsonObject response=envelope(request,"ack");response.add("user_state",state.deepCopy());return response;
    }

    public static JsonObject readyResponse(Request request) {
        JsonObject response=envelope(request,"ready");response.add("user_state",new JsonObject());return response;
    }

    private static JsonObject envelope(Request request,String type) {
        JsonObject response=new JsonObject();response.addProperty("protocol_version",VERSION);
        response.addProperty("request_id",request.context().requestId());response.addProperty("type",type);return response;
    }

    public static String encode(JsonObject response) {
        String text=GSON.toJson(response);require(bytes(text)<=MAX_RESPONSE_BYTES,"Response is too large");return text;
    }

    public static void validateState(JsonObject state) {
        require(state!=null,"user_state must be an object");require(bytes(GSON.toJson(state))<=MAX_STATE_BYTES,"user_state exceeds 65536 bytes");
        validateStateValue(state,0);
    }

    private static void validateStateValue(JsonElement e,int depth) {
        if(e.isJsonObject() || e.isJsonArray()) {
            // State is nested inside the request/response envelope.
            require(depth<MAX_DEPTH-1,"State nesting exceeds whole-message allowance");
            if(e.isJsonObject()) for(var entry:e.getAsJsonObject().entrySet()) { validUnicode(entry.getKey());validateStateValue(entry.getValue(),depth+1); }
            else for(JsonElement child:e.getAsJsonArray()) validateStateValue(child,depth+1);
        } else if(e.isJsonPrimitive()) {
            JsonPrimitive p=e.getAsJsonPrimitive();
            if(p.isString())validUnicode(p.getAsString());
            if(p.isNumber()) {
                BigDecimal n=p.getAsBigDecimal();require(Double.isFinite(n.doubleValue()),"Non-finite state number");
                if(n.stripTrailingZeros().scale()<=0) require(n.abs().compareTo(new BigDecimal("9007199254740991"))<=0,"State integer exceeds exact cross-language range");
            }
        }
    }

    public static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    public static void require(boolean condition,String message) { if(!condition)throw new IllegalArgumentException(message); }
    private static JsonElement field(JsonObject o,String key) { JsonElement e=o.get(key);require(e!=null && !e.isJsonNull(),"Missing field: "+key);return e; }
    private static JsonObject asObject(JsonElement e,String label) { require(e!=null && e.isJsonObject(),label+" must be an object");return e.getAsJsonObject(); }
    private static JsonObject object(JsonObject o,String key) { return asObject(field(o,key),key); }
    private static String string(JsonObject o,String key) {
        JsonElement e=field(o,key);require(e.isJsonPrimitive() && e.getAsJsonPrimitive().isString(),key+" must be a string");
        String s=e.getAsString();require(!s.isEmpty() && s.codePointCount(0,s.length())<=256,key+" must have 1 to 256 characters");return s;
    }
    private static String choice(JsonObject o,String key,String... values) {
        String v=string(o,key);require(Arrays.asList(values).contains(v),"Unknown "+key+": "+v);return v;
    }
    private static long integer(JsonObject o,String key,long min,long max) {
        JsonElement e=field(o,key);require(e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber(),key+" must be an integer number");
        long value;try {value=e.getAsBigDecimal().longValueExact();}catch(ArithmeticException ex){throw new IllegalArgumentException(key+" must be an integer in range");}
        require(value>=min && value<=max,key+" outside allowed range");return value;
    }
    private static int number(JsonObject o,String key) { return (int)integer(o,key,0,Integer.MAX_VALUE); }
    private static int positive(JsonObject o,String key) { return (int)integer(o,key,1,Integer.MAX_VALUE); }
    private static void bool(JsonObject o,String key) { JsonElement e=field(o,key);require(e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean(),key+" must be boolean"); }
    private static JsonArray array(JsonObject o,String key,int min,int max) {
        JsonElement e=field(o,key);require(e.isJsonArray(),key+" must be an array");JsonArray a=e.getAsJsonArray();
        require(a.size()>=min && a.size()<=max,key+" has invalid length");return a;
    }
    private static void cards(JsonArray cards,Set<String> visible) {
        for(JsonElement e:cards) {
            require(e.isJsonPrimitive() && e.getAsJsonPrimitive().isString(),"Card must be a string");String s=e.getAsString();
            Card.parse(s);require(visible.add(s),"Duplicate visible card");
        }
    }
    private static Set<Integer> seats(JsonObject o,String key,int count) {
        Set<Integer> seen=new HashSet<>();
        for(JsonElement e:array(o,key,1,count)) {
            JsonObject temporary=new JsonObject();temporary.add("seat",e);int seat=(int)integer(temporary,"seat",0,count-1);
            require(seen.add(seat),"Duplicate eligible seat");
        }
        return seen;
    }
}
