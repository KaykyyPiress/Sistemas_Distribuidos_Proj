import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class Servidor {

    private static final Path STATE_FILE = Path.of("/data/state_java.msgpack");
    private static final Pattern USER_REGEX = Pattern.compile("^[a-zA-Z0-9_]{3,20}$");
    private static final Pattern CHAN_REGEX = Pattern.compile("^[a-zA-Z0-9_-]{3,50}$");
    private static final int HEARTBEAT_EVERY_MESSAGES = 10;

    private static long logicalClock = 0;
    private static double clockOffset = 0.0;

    public static void main(String[] args) throws Exception {
        Map<String, Object> state = loadState();
        String serverName = System.getenv().getOrDefault("SERVER_NAME", "servidor-java");

        try (ZContext ctx = new ZContext()) {
            ZMQ.Socket repSocket = ctx.createSocket(SocketType.REP);
            repSocket.connect("tcp://broker:5556");

            ZMQ.Socket pubSocket = ctx.createSocket(SocketType.PUB);
            pubSocket.connect("tcp://pubsub-proxy:5557");

            ZMQ.Socket refSocket = ctx.createSocket(SocketType.REQ);
            refSocket.connect("tcp://reference:5559");

            Map<String, Object> registerReply = callReference(refSocket, mapOf("type", "register", "name", serverName));
            int serverRank = ((Number) registerReply.getOrDefault("rank", -1)).intValue();
            updateClockOffset(registerReply);

            System.out.println("[SERVIDOR-JAVA] " + serverName + " rank=" + serverRank
                + " | Estado: " + getList(state, "logins").size() + " login(s), "
                + getList(state, "channels").size() + " canal(is), "
                + getList(state, "publications").size() + " publicacao(oes).");

            int messagesSinceHeartbeat = 0;

            while (!Thread.currentThread().isInterrupted()) {
                byte[] raw = repSocket.recv();
                Map<String, Object> msg = MsgHelper.unpack(raw);
                mergeClock(msg.get("logical_clock"));

                String type = (String) msg.getOrDefault("type", "");
                System.out.println("[SERVIDOR-JAVA] Mensagem recebida: type=" + type + " lc=" + logicalClock);

                Map<String, Object> response;
                switch (type) {
                    case "login":
                        response = handleLogin(msg, state);
                        break;
                    case "create_channel":
                        response = handleCreateChannel(msg, state);
                        break;
                    case "list_channels":
                        response = handleListChannels(state);
                        break;
                    case "publish_message":
                        response = handlePublishMessage(msg, state, pubSocket);
                        break;
                    default:
                        response = makeResponse("error", map("message", "Operacao desconhecida: " + type));
                        break;
                }
                repSocket.send(MsgHelper.pack(response));

                messagesSinceHeartbeat++;
                if (messagesSinceHeartbeat >= HEARTBEAT_EVERY_MESSAGES) {
                    Map<String, Object> hbReply = callReference(
                        refSocket,
                        mapOf("type", "heartbeat", "name", serverName, "rank", serverRank)
                    );
                    updateClockOffset(hbReply);
                    messagesSinceHeartbeat = 0;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> handleLogin(Map<String, Object> msg, Map<String, Object> state) throws Exception {
        Map<String, Object> pl = (Map<String, Object>) msg.getOrDefault("payload", new HashMap<>());
        String username = pl.getOrDefault("username", "").toString().trim();
        if (username.isEmpty())
            return makeResponse("error", map("message", "Username nao informado."));
        if (!USER_REGEX.matcher(username).matches())
            return makeResponse("error", map("message", "Username invalido."));

        double ts = msg.containsKey("timestamp") ? toDouble(msg.get("timestamp")) : now();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("username", username);
        entry.put("login_timestamp", ts);
        getList(state, "logins").add(entry);
        saveState(state);
        System.out.println("[LOGIN] " + username + " fez login em " + ts);
        return makeResponse("ok", map("message", "Bem-vindo, " + username + "!"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> handleCreateChannel(Map<String, Object> msg, Map<String, Object> state) throws Exception {
        Map<String, Object> pl = (Map<String, Object>) msg.getOrDefault("payload", new HashMap<>());
        String channel = pl.getOrDefault("channel", "").toString().trim();
        String username = pl.getOrDefault("username", "desconhecido").toString();
        if (channel.isEmpty())
            return makeResponse("error", map("message", "Nome do canal nao informado."));
        if (!CHAN_REGEX.matcher(channel).matches())
            return makeResponse("error", map("message", "Nome de canal invalido."));

        List<Object> channels = getList(state, "channels");
        if (channels.contains(channel))
            return makeResponse("error", map("message", "Canal " + channel + " ja existe."));

        channels.add(channel);
        saveState(state);
        System.out.println("[CANAL] " + username + " criou o canal " + channel);
        return makeResponse("ok", map("message", "Canal " + channel + " criado com sucesso."));
    }

    private static Map<String, Object> handleListChannels(Map<String, Object> state) {
        List<Object> channels = getList(state, "channels");
        Map<String, Object> pl = new LinkedHashMap<>();
        pl.put("channels", channels);
        return makeResponse("ok", pl);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> handlePublishMessage(Map<String, Object> msg, Map<String, Object> state, ZMQ.Socket pubSocket) throws Exception {
        Map<String, Object> pl = (Map<String, Object>) msg.getOrDefault("payload", new HashMap<>());
        String channel = pl.getOrDefault("channel", "").toString().trim();
        String message = pl.getOrDefault("message", "").toString().trim();
        String username = pl.getOrDefault("username", "desconhecido").toString();

        if (channel.isEmpty())
            return makeResponse("error", map("message", "Canal nao informado."));
        if (!getList(state, "channels").contains(channel))
            return makeResponse("error", map("message", "Canal " + channel + " nao existe."));
        if (message.isEmpty())
            return makeResponse("error", map("message", "Mensagem vazia."));

        double sentTimestamp = msg.containsKey("timestamp") ? toDouble(msg.get("timestamp")) : now();
        double publishedTimestamp = now();

        Map<String, Object> publication = new LinkedHashMap<>();
        publication.put("channel", channel);
        publication.put("message", message);
        publication.put("username", username);
        publication.put("sent_timestamp", sentTimestamp);
        publication.put("published_timestamp", publishedTimestamp);
        publication.put("logical_clock", tickClock());

        pubSocket.sendMore(channel.getBytes(StandardCharsets.UTF_8));
        pubSocket.send(MsgHelper.pack(publication));

        getList(state, "publications").add(publication);
        saveState(state);

        return makeResponse("ok", map("message", "Publicacao enviada com sucesso."));
    }

    private static Map<String, Object> makeResponse(String status, Map<String, Object> payload) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", status);
        r.put("timestamp", now());
        r.put("logical_clock", tickClock());
        r.put("payload", payload);
        return r;
    }

    private static synchronized void mergeClock(Object received) {
        long r = (received == null) ? 0 : ((Number) received).longValue();
        logicalClock = Math.max(logicalClock, r);
    }

    private static synchronized long tickClock() {
        logicalClock += 1;
        return logicalClock;
    }

    private static void updateClockOffset(Map<String, Object> reply) {
        Object ref = reply.get("reference_time");
        if (ref instanceof Number) {
            clockOffset = ((Number) ref).doubleValue() - (System.currentTimeMillis() / 1000.0);
        }
    }

    private static double now() {
        return (System.currentTimeMillis() / 1000.0) + clockOffset;
    }

    private static Map<String, Object> map(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i].toString(), kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> callReference(ZMQ.Socket refSocket, Map<String, Object> req) throws Exception {
        refSocket.send(MsgHelper.pack(req));
        return MsgHelper.unpack(refSocket.recv());
    }

    private static double toDouble(Object o) {
        if (o instanceof Double) return (Double) o;
        if (o instanceof Float) return ((Float) o).doubleValue();
        if (o instanceof Long) return ((Long) o).doubleValue();
        if (o instanceof Integer) return ((Integer) o).doubleValue();
        return Double.parseDouble(o.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> getList(Map<String, Object> state, String key) {
        return (List<Object>) state.computeIfAbsent(key, k -> new ArrayList<>());
    }

    private static Map<String, Object> emptyState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("logins", new ArrayList<>());
        state.put("channels", new ArrayList<>());
        state.put("publications", new ArrayList<>());
        return state;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadState() {
        Map<String, Object> state = emptyState();

        try {
            if (!Files.exists(STATE_FILE) || Files.size(STATE_FILE) == 0) {
                return state;
            }

            byte[] raw = Files.readAllBytes(STATE_FILE);
            Map<String, Object> persisted = MsgHelper.unpack(raw);
            if (persisted != null) {
                Object logins = persisted.get("logins");
                Object channels = persisted.get("channels");
                Object publications = persisted.get("publications");
                state.put("logins", logins instanceof List ? logins : new ArrayList<>());
                state.put("channels", channels instanceof List ? channels : new ArrayList<>());
                state.put("publications", publications instanceof List ? publications : new ArrayList<>());
            }
        } catch (Exception e) {
            state = emptyState();
        }

        return state;
    }

    private static void saveState(Map<String, Object> state) throws Exception {
        Path tmp = Path.of(STATE_FILE.toString() + ".tmp");
        Files.write(tmp, MsgHelper.pack(state));
        Files.move(tmp, STATE_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
