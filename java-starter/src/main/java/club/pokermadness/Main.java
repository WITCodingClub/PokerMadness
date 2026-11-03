package club.pokermadness;

import club.pokermadness.api.PokerBot;
import club.pokermadness.protocol.Protocol;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.function.Supplier;

/** Fixed organizer entry point. Reads one JSON object per line, with no stdout banners. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        if(args.length!=0) {System.err.println("Usage: java -jar target/poker-bot.jar (JSON lines on stdin)");System.exit(2);}
        try {
            run(System.in,System.out);
        } catch(Exception failure) {
            System.err.println("Poker Madness worker failed: "+failure.getMessage());
            System.exit(2);
        }
    }

    public static void run(InputStream input,OutputStream output) throws Exception {
        run(input, output, BotFactory::create);
    }

    static void run(InputStream input, OutputStream output, Supplier<PokerBot> botFactory) throws Exception {
        BufferedInputStream in = new BufferedInputStream(input);
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        boolean initialized = false;
        HandScope activeHand = null;
        PokerBot handBot = null;
        String line;
        while ((line = readLine(in)) != null) {
            Protocol.Request request = Protocol.decode(Protocol.parseObject(line));
            JsonObject response;
            if (request.type().equals("init")) {
                Protocol.require(!initialized, "Worker was already initialized");
                botFactory.get().initialize(request.context());
                response = Protocol.readyResponse(request);
                initialized = true;
            } else {
                Protocol.require(initialized, "First request must be init");
                boolean handEnded = request.type().equals("hand_end");
                String handId = handEnded ? request.result().handId() : request.observation().handId();
                HandScope requestedHand = new HandScope(request.context().roundId(), handId);
                if (!requestedHand.equals(activeHand)) {
                    activeHand = requestedHand;
                    handBot = botFactory.get();
                }

                JsonObject userState = request.userState().deepCopy();
                if (handEnded) {
                    response = Protocol.ackResponse(request, handBot.onHandEnd(request.result(), userState, request.context()));
                    handBot = null;
                    activeHand = null;
                } else {
                    response = Protocol.actionResponse(request, handBot.act(request.observation(), userState, request.context()));
                }
            }
            out.write(Protocol.encode(response));
            out.write('\n');
            out.flush();
        }
    }

    private record HandScope(String roundId, String handId) {}

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line=new ByteArrayOutputStream();int b;
        while((b=in.read())!=-1 && b!='\n') {
            if(line.size()>=Protocol.MAX_REQUEST_BYTES+1)throw new IOException("Input frame is too large");
            line.write(b);
        }
        if(b==-1 && line.size()==0)return null;
        if(b==-1)throw new IOException("Input frame must end with LF");
        byte[] bytes=line.toByteArray();int length=bytes.length;
        if(length>0 && bytes[length-1]=='\r')length--;
        if(length==0 || length>Protocol.MAX_REQUEST_BYTES)throw new IOException("Blank or oversized input frame");
        CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes,0,length)).toString();
    }
}
