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
    private static final int SYNC_EVERY_MESSAGES = 15;
    private static final String STATE_SYNC_TOPIC = "servers.state";

    private static long logicalClock = 0;
    private static String coordinatorName = "";
    private static String peerHost = "servidor-java";
    private static int peerPort = 6001;

    public static void main(String[] args) throws Exception {
        Map<String, Object> state = loadState();
        String serverName = System.getenv().getOrDefault("SERVER_NAME", "servidor-java");
        peerHost = System.getenv().getOrDefault("PEER_HOST", "servidor-java");
        peerPort = Integer.parseInt(System.getenv().getOrDefault("PEER_PORT", "6001"));

        try (ZContext ctx = new ZContext()) {
            ZMQ.Socket repSocket = ctx.createSocket(SocketType.REP);
            repSocket.connect("tcp://broker:5556");

            ZMQ.Socket pubSocket = ctx.createSocket(SocketType.PUB);
            pubSocket.connect("tcp://pubsub-proxy:5557");
            ZMQ.Socket subSocket = ctx.createSocket(SocketType.SUB);
            subSocket.connect("tcp://pubsub-proxy:5558");
            subSocket.subscribe(STATE_SYNC_TOPIC.getBytes(StandardCharsets.UTF_8));

            ZMQ.Socket refSocket = ctx.createSocket(SocketType.REQ);
            refSocket.connect("tcp://reference:5559");
            ZMQ.Socket peerRep = ctx.createSocket(SocketType.REP);
            peerRep.bind("tcp://*:" + peerPort);

            Map<String, Object> registerReply = callReference(refSocket, mapOf("type", "register", "name", serverName, "host", peerHost, "peer_port", peerPort));
            int serverRank = ((Number) registerReply.getOrDefault("rank", -1)).intValue();
            coordinatorName = serverName;

            System.out.println("[SERVIDOR-JAVA] " + serverName + " rank=" + serverRank
                + " | Estado: " + getList(state, "logins").size() + " login(s), "
                + getList(state, "channels").size() + " canal(is), "
                + getList(state, "publications").size() + " publicacao(oes).");

            int messagesSinceSync = 0;

            while (!Thread.currentThread().isInterrupted()) {
                byte[] raw = repSocket.recv();
                drainStateSync(subSocket, state);
                handlePeerControl(peerRep, refSocket, serverName, serverRank, pubSocket, state);
                Map<String, Object> msg = MsgHelper.unpack(raw);
                mergeClock(msg.get("logical_clock"));

                String type = (String) msg.getOrDefault("type", "");
                System.out.println("[SERVIDOR-JAVA] Mensagem recebida: type=" + type + " lc=" + logicalClock);

                Map<String, Object> response;
                if (("login".equals(type) || "create_channel".equals(type) || "publish_message".equals(type))
                    && !coordinatorName.equals(serverName)) {
                    Map<String, Object> target = findCoordinator(refSocket);
                    Map<String, Object> forwarded = target == null ? null : peerRequest(target, mapOf("type", "client_request", "message", msg));
                    response = forwarded != null ? forwarded : makeResponse("error", map("message", "coordinator indisponivel"));
                } else {
                switch (type) {
                    case "login":
                        response = handleLogin(msg, state);
                        if ("ok".equals(response.get("status"))) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> payload = (Map<String, Object>) msg.getOrDefault("payload", new HashMap<>());
                            String username = payload.getOrDefault("username", "").toString().trim();
                            double loginTs = msg.containsKey("timestamp") ? toDouble(msg.get("timestamp")) : now();
                            Map<String, Object> loginEvt = mapOf("type", "login_created", "login", mapOf("username", username, "login_timestamp", loginTs));
                            pubSocket.sendMore(STATE_SYNC_TOPIC.getBytes(StandardCharsets.UTF_8));
                            pubSocket.send(MsgHelper.pack(loginEvt));
                        }
                        break;
                    case "create_channel":
                        response = handleCreateChannel(msg, state);
                        if ("ok".equals(response.get("status"))) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> payload = (Map<String, Object>) msg.getOrDefault("payload", new HashMap<>());
                            String createdChannel = payload.getOrDefault("channel", "").toString().trim();
                            Map<String, Object> evt = mapOf("type", "channel_created", "channel", createdChannel);
                            pubSocket.sendMore(STATE_SYNC_TOPIC.getBytes(StandardCharsets.UTF_8));
                            pubSocket.send(MsgHelper.pack(evt));
                        }
                        break;
                    case "list_channels":
                        response = handleListChannels(state);
                        break;
                    case "publish_message":
                        response = handlePublishMessage(msg, state, pubSocket);
                        if ("ok".equals(response.get("status"))) {
                            List<Object> pubs = getList(state, "publications");
                            if (!pubs.isEmpty()) {
                                Map<String, Object> evt = mapOf("type", "publication_created", "publication", pubs.get(pubs.size() - 1));
                                pubSocket.sendMore(STATE_SYNC_TOPIC.getBytes(StandardCharsets.UTF_8));
                                pubSocket.send(MsgHelper.pack(evt));
                            }
                        }
                        break;
                    default:
                        response = makeResponse("error", map("message", "Operacao desconhecida: " + type));
                        break;
                }
                }
                repSocket.send(MsgHelper.pack(response));

                callReference(refSocket, mapOf("type", "heartbeat", "name", serverName, "rank", serverRank, "host", peerHost, "peer_port", peerPort));
                messagesSinceSync++;
                if (messagesSinceSync >= SYNC_EVERY_MESSAGES) {
                    refreshCoordinator(refSocket, serverName);
                    publishChannelsSnapshot(pubSocket, state);
                    messagesSinceSync = 0;
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

    @SuppressWarnings("unchecked")
    private static void refreshCoordinator(ZMQ.Socket refSocket, String serverName) throws Exception {
        Map<String, Object> listReply = callReference(refSocket, mapOf("type", "list"));
        List<Object> servers = (List<Object>) listReply.getOrDefault("servers", new ArrayList<>());
        int bestRank = Integer.MAX_VALUE;
        String elected = serverName;
        for (Object entry : servers) {
            if (entry instanceof Map<?, ?> e) {
                Object rankObj = e.containsKey("rank") ? e.get("rank") : Integer.MAX_VALUE;
                int rank = ((Number) rankObj).intValue();
                Object nameObj = e.containsKey("name") ? e.get("name") : serverName;
                String name = nameObj.toString();
                if (rank < bestRank) {
                    bestRank = rank;
                    elected = name;
                }
            }
        }
        coordinatorName = elected;
        if (!coordinatorName.equals(serverName)) {
            Map<String, Object> target = null;
            for (Object entry : servers) {
                if (entry instanceof Map<?, ?> e && coordinatorName.equals(String.valueOf(e.get("name")))) {
                    target = (Map<String, Object>) e;
                    break;
                }
            }
            if (target == null || peerRequest(target, mapOf("type", "berkeley_time_request", "from", serverName)) == null) {
                startElection(refSocket, serverName, bestRank);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void startElection(ZMQ.Socket refSocket, String serverName, int myRank) throws Exception {
        Map<String, Object> listReply = callReference(refSocket, mapOf("type", "list"));
        List<Object> servers = (List<Object>) listReply.getOrDefault("servers", new ArrayList<>());
        boolean gotOk = false;
        for (Object entry : servers) {
            if (entry instanceof Map<?, ?> e) {
                Object rankObj = e.containsKey("rank") ? e.get("rank") : Integer.MAX_VALUE;
                int rank = ((Number) rankObj).intValue();
                if (rank < myRank) {
                    Map<String, Object> reply = peerRequest((Map<String, Object>) e, mapOf("type", "election", "from", serverName, "rank", myRank));
                    if (reply != null && "ok".equals(reply.get("status"))) gotOk = true;
                }
            }
        }
        if (!gotOk) coordinatorName = serverName;
    }

    private static Map<String, Object> peerRequest(Map<String, Object> target, Map<String, Object> payload) throws Exception {
        String host = String.valueOf(target.getOrDefault("host", ""));
        int port = ((Number) target.getOrDefault("peer_port", 0)).intValue();
        if (host.isEmpty() || port == 0) return null;
        try (ZContext tctx = new ZContext()) {
            ZMQ.Socket req = tctx.createSocket(SocketType.REQ);
            req.setReceiveTimeOut(800);
            req.setSendTimeOut(800);
            req.connect("tcp://" + host + ":" + port);
            req.send(MsgHelper.pack(payload));
            byte[] rep = req.recv();
            if (rep == null) return null;
            return MsgHelper.unpack(rep);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findCoordinator(ZMQ.Socket refSocket) throws Exception {
        Map<String, Object> listReply = callReference(refSocket, mapOf("type", "list"));
        List<Object> servers = (List<Object>) listReply.getOrDefault("servers", new ArrayList<>());
        for (Object entry : servers) {
            if (entry instanceof Map<?, ?> e && coordinatorName.equals(String.valueOf(e.get("name")))) {
                return (Map<String, Object>) e;
            }
        }
        return null;
    }

    private static void handlePeerControl(ZMQ.Socket peerRep, ZMQ.Socket refSocket, String serverName, int serverRank, ZMQ.Socket pubSocket, Map<String, Object> state) throws Exception {
        byte[] raw = peerRep.recv(ZMQ.DONTWAIT);
        if (raw == null) return;
        Map<String, Object> msg = MsgHelper.unpack(raw);
        String type = String.valueOf(msg.getOrDefault("type", ""));
        if ("election".equals(type)) {
            peerRep.send(MsgHelper.pack(mapOf("status", "ok", "from", serverName)));
            int senderRank = ((Number) msg.getOrDefault("rank", Integer.MAX_VALUE)).intValue();
            if (senderRank > serverRank) startElection(refSocket, serverName, serverRank);
        } else if ("berkeley_time_request".equals(type)) {
            if (coordinatorName.equals(serverName)) peerRep.send(MsgHelper.pack(mapOf("status", "ok", "reference_time", now())));
            else peerRep.send(MsgHelper.pack(mapOf("status", "error")));
        } else if ("client_request".equals(type)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> reqMsg = (Map<String, Object>) msg.getOrDefault("message", new LinkedHashMap<>());
            String reqType = String.valueOf(reqMsg.getOrDefault("type", ""));
            Map<String, Object> response;
            switch (reqType) {
                case "login":
                    response = handleLogin(reqMsg, state);
                    break;
                case "create_channel":
                    response = handleCreateChannel(reqMsg, state);
                    break;
                case "publish_message":
                    response = handlePublishMessage(reqMsg, state, pubSocket);
                    break;
                case "list_channels":
                    response = handleListChannels(state);
                    break;
                default:
                    response = makeResponse("error", map("message", "Operacao desconhecida: " + reqType));
                    break;
            }
            peerRep.send(MsgHelper.pack(response));
        } else {
            peerRep.send(MsgHelper.pack(mapOf("status", "error")));
        }
    }

    private static double now() {
        return (System.currentTimeMillis() / 1000.0);
    }

    @SuppressWarnings("unchecked")
    private static void drainStateSync(ZMQ.Socket subSocket, Map<String, Object> state) throws Exception {
        while (true) {
            byte[] topic = subSocket.recv(ZMQ.DONTWAIT);
            if (topic == null) return;
            byte[] payload = subSocket.recv();
            String topicStr = new String(topic, StandardCharsets.UTF_8);
            if (!STATE_SYNC_TOPIC.equals(topicStr)) continue;
            Map<String, Object> event = MsgHelper.unpack(payload);
            List<Object> channels = getList(state, "channels");
            List<Object> logins = getList(state, "logins");
            List<Object> publications = getList(state, "publications");
            boolean changed = false;
            if ("login_created".equals(event.get("type"))) {
                Object login = event.get("login");
                if (login instanceof Map<?, ?> && !logins.contains(login)) {
                    logins.add(login);
                    changed = true;
                }
            } else if ("channel_created".equals(event.get("type"))) {
                String channel = event.getOrDefault("channel", "").toString().trim();
                if (!channel.isEmpty() && !channels.contains(channel)) {
                    channels.add(channel);
                    changed = true;
                }
            } else if ("publication_created".equals(event.get("type"))) {
                Object publication = event.get("publication");
                if (publication instanceof Map<?, ?> && !publications.contains(publication)) {
                    publications.add(publication);
                    changed = true;
                }
            } else if ("channels_snapshot".equals(event.get("type"))) {
                Object rawChannels = event.get("channels");
                if (rawChannels instanceof List<?> list) {
                    for (Object c : list) {
                        String channel = String.valueOf(c).trim();
                        if (!channel.isEmpty() && !channels.contains(channel)) {
                            channels.add(channel);
                            changed = true;
                        }
                    }
                }
            } else if ("state_snapshot".equals(event.get("type"))) {
                Object rawLogins = event.get("logins");
                if (rawLogins instanceof List<?> list) {
                    for (Object l : list) {
                        if (l instanceof Map<?, ?> && !logins.contains(l)) {
                            logins.add(l);
                            changed = true;
                        }
                    }
                }
                Object rawChannels = event.get("channels");
                if (rawChannels instanceof List<?> list) {
                    for (Object c : list) {
                        String channel = String.valueOf(c).trim();
                        if (!channel.isEmpty() && !channels.contains(channel)) {
                            channels.add(channel);
                            changed = true;
                        }
                    }
                }
                Object rawPublications = event.get("publications");
                if (rawPublications instanceof List<?> list) {
                    for (Object p : list) {
                        if (p instanceof Map<?, ?> && !publications.contains(p)) {
                            publications.add(p);
                            changed = true;
                        }
                    }
                }
            }
            if (changed) {
                saveState(state);
            }
        }
    }

    private static void publishChannelsSnapshot(ZMQ.Socket pubSocket, Map<String, Object> state) throws Exception {
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("type", "channels_snapshot");
        evt.put("channels", new ArrayList<>(getList(state, "channels")));
        pubSocket.sendMore(STATE_SYNC_TOPIC.getBytes(StandardCharsets.UTF_8));
        pubSocket.send(MsgHelper.pack(evt));
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("type", "state_snapshot");
        full.put("logins", new ArrayList<>(getList(state, "logins")));
        full.put("channels", new ArrayList<>(getList(state, "channels")));
        full.put("publications", new ArrayList<>(getList(state, "publications")));
        pubSocket.sendMore(STATE_SYNC_TOPIC.getBytes(StandardCharsets.UTF_8));
        pubSocket.send(MsgHelper.pack(full));
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
