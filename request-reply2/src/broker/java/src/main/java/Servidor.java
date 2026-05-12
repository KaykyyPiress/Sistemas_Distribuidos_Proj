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
    private static final int SYNC_EVERY_MESSAGES = 15;

    private static final String STATE_SYNC_TOPIC = "servers.state";
    private static final String COORDINATOR_TOPIC = "servers";

    private static long logicalClock = 0;

    private static String coordinatorName = "";
    private static String serverName = "servidor-java";
    private static String peerHost = "servidor-java";
    private static int peerPort = 6001;
    private static int serverRank = -1;

    public static void main(String[] args) throws Exception {
        Map<String, Object> state = loadState();

        serverName = System.getenv().getOrDefault("SERVER_NAME", "servidor-java");
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
            subSocket.subscribe(COORDINATOR_TOPIC.getBytes(StandardCharsets.UTF_8));

            ZMQ.Socket refSocket = ctx.createSocket(SocketType.REQ);
            refSocket.connect("tcp://reference:5559");

            ZMQ.Socket peerRep = ctx.createSocket(SocketType.REP);
            peerRep.bind("tcp://*:" + peerPort);

            Map<String, Object> registerReply = callReference(
                refSocket,
                mapOf(
                    "type", "register",
                    "name", serverName,
                    "host", peerHost,
                    "peer_port", peerPort
                )
            );

            serverRank = ((Number) registerReply.getOrDefault("rank", -1)).intValue();
            coordinatorName = serverName;

            System.out.println(
                "[SERVIDOR-JAVA] " + serverName + " rank=" + serverRank
                    + " | Estado: " + getList(state, "logins").size() + " login(s), "
                    + getList(state, "channels").size() + " canal(is), "
                    + getList(state, "publications").size() + " publicacao(oes)."
            );

            refreshCoordinator(refSocket, pubSocket);

            ZMQ.Poller poller = ctx.createPoller(3);
            poller.register(repSocket, ZMQ.Poller.POLLIN);
            poller.register(peerRep, ZMQ.Poller.POLLIN);
            poller.register(subSocket, ZMQ.Poller.POLLIN);

            int messagesSinceHeartbeat = 0;
            int messagesSinceSync = 0;

            while (!Thread.currentThread().isInterrupted()) {
                poller.poll(500);

                if (poller.pollin(2)) {
                    drainSubscriptions(subSocket, state);
                }

                if (poller.pollin(1)) {
                    handlePeerControl(peerRep, refSocket, pubSocket, state);
                }

                if (poller.pollin(0)) {
                    byte[] raw = repSocket.recv();
                    Map<String, Object> msg = MsgHelper.unpack(raw);
                    mergeClock(msg.get("logical_clock"));

                    String type = String.valueOf(msg.getOrDefault("type", ""));
                    System.out.println("[SERVIDOR-JAVA] Mensagem recebida: type=" + type + " lc=" + logicalClock);

                    if (isWriteOperation(type)) {
                        refreshCoordinator(refSocket, pubSocket);
                    }

                    Map<String, Object> response;

                    if (isWriteOperation(type) && !serverName.equals(coordinatorName)) {
                        response = forwardToCoordinator(refSocket, pubSocket, msg, state);
                    } else {
                        response = processClientMessage(msg, state, pubSocket);
                    }

                    repSocket.send(MsgHelper.pack(response));

                    messagesSinceHeartbeat++;
                    messagesSinceSync++;

                    if (messagesSinceHeartbeat >= HEARTBEAT_EVERY_MESSAGES) {
                        callReference(
                            refSocket,
                            mapOf(
                                "type", "heartbeat",
                                "name", serverName,
                                "rank", serverRank,
                                "host", peerHost,
                                "peer_port", peerPort
                            )
                        );
                        System.out.println("[HEARTBEAT-JAVA] " + serverName + " enviou heartbeat apos 10 mensagens de cliente.");
                        messagesSinceHeartbeat = 0;
                    }

                    if (messagesSinceSync >= SYNC_EVERY_MESSAGES) {
                        refreshCoordinator(refSocket, pubSocket);
                        publishStateSnapshot(pubSocket, state);
                        messagesSinceSync = 0;
                    }
                }
            }
        }
    }

    private static Map<String, Object> forwardToCoordinator(
        ZMQ.Socket refSocket,
        ZMQ.Socket pubSocket,
        Map<String, Object> msg,
        Map<String, Object> state
    ) throws Exception {
        Map<String, Object> target = findCoordinator(refSocket);

        Map<String, Object> forwarded = target == null
            ? null
            : peerRequest(target, mapOf("type", "client_request", "message", msg));

        if (forwarded != null) {
            System.out.println("[FORWARD-JAVA] Escrita encaminhada para coordenador: " + coordinatorName);
            return forwarded;
        }

        System.out.println("[FORWARD-JAVA] Coordenador indisponivel. Iniciando eleicao.");
        startElection(refSocket, pubSocket);

        if (serverName.equals(coordinatorName)) {
            System.out.println("[COORD-JAVA] Java assumiu coordenacao e processara a escrita localmente.");
            return processClientMessage(msg, state, pubSocket);
        }

        return makeResponse("error", map("message", "coordinator indisponivel"));
    }

    private static Map<String, Object> processClientMessage(
        Map<String, Object> msg,
        Map<String, Object> state,
        ZMQ.Socket pubSocket
    ) throws Exception {
        String type = String.valueOf(msg.getOrDefault("type", ""));
        Map<String, Object> response;

        switch (type) {
            case "login":
                response = handleLogin(msg, state);
                if ("ok".equals(response.get("status"))) {
                    List<Object> logins = getList(state, "logins");
                    if (!logins.isEmpty()) {
                        publishStateEvent(pubSocket, mapOf(
                            "type", "login_created",
                            "login", logins.get(logins.size() - 1)
                        ));
                    }
                }
                return response;

            case "create_channel":
                response = handleCreateChannel(msg, state);
                if ("ok".equals(response.get("status"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) msg.getOrDefault("payload", new HashMap<>());
                    String channel = payload.getOrDefault("channel", "").toString().trim();

                    publishStateEvent(pubSocket, mapOf(
                        "type", "channel_created",
                        "channel", channel
                    ));
                }
                return response;

            case "list_channels":
                return handleListChannels(state);

            case "publish_message":
                response = handlePublishMessage(msg, state, pubSocket);
                if ("ok".equals(response.get("status"))) {
                    List<Object> publications = getList(state, "publications");
                    if (!publications.isEmpty()) {
                        publishStateEvent(pubSocket, mapOf(
                            "type", "publication_created",
                            "publication", publications.get(publications.size() - 1)
                        ));
                    }
                }
                return response;

            default:
                return makeResponse("error", map("message", "Operacao desconhecida: " + type));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> handleLogin(Map<String, Object> msg, Map<String, Object> state) throws Exception {
        Map<String, Object> payload = (Map<String, Object>) msg.getOrDefault("payload", new HashMap<>());
        String username = payload.getOrDefault("username", "").toString().trim();

        if (username.isEmpty()) {
            return makeResponse("error", map("message", "Username nao informado."));
        }

        if (!USER_REGEX.matcher(username).matches()) {
            return makeResponse("error", map("message", "Username invalido."));
        }

        double ts = msg.containsKey("timestamp") ? toDouble(msg.get("timestamp")) : now();

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("username", username);
        entry.put("login_timestamp", ts);

        getList(state, "logins").add(entry);
        saveState(state);

        System.out.println("[LOGIN-JAVA] " + username + " fez login em " + ts);
        System.out.println("[STATE-JAVA] Estado salvo: " + getList(state, "logins").size() + " login(s).");

        return makeResponse("ok", map("message", "Bem-vindo, " + username + "!"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> handleCreateChannel(Map<String, Object> msg, Map<String, Object> state) throws Exception {
        Map<String, Object> payload = (Map<String, Object>) msg.getOrDefault("payload", new HashMap<>());
        String channel = payload.getOrDefault("channel", "").toString().trim();
        String username = payload.getOrDefault("username", "desconhecido").toString();

        if (channel.isEmpty()) {
            return makeResponse("error", map("message", "Nome do canal nao informado."));
        }

        if (!CHAN_REGEX.matcher(channel).matches()) {
            return makeResponse("error", map("message", "Nome de canal invalido."));
        }

        List<Object> channels = getList(state, "channels");

        if (channels.contains(channel)) {
            return makeResponse("error", map("message", "Canal " + channel + " ja existe."));
        }

        channels.add(channel);
        saveState(state);

        System.out.println("[CANAL-JAVA] " + username + " criou o canal " + channel);
        System.out.println("[STATE-JAVA] Estado salvo: " + channels.size() + " canal(is).");

        return makeResponse("ok", map("message", "Canal " + channel + " criado com sucesso."));
    }

    private static Map<String, Object> handleListChannels(Map<String, Object> state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channels", new ArrayList<>(getList(state, "channels")));
        return makeResponse("ok", payload);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> handlePublishMessage(
        Map<String, Object> msg,
        Map<String, Object> state,
        ZMQ.Socket pubSocket
    ) throws Exception {
        Map<String, Object> payload = (Map<String, Object>) msg.getOrDefault("payload", new HashMap<>());

        String channel = payload.getOrDefault("channel", "").toString().trim();
        String message = payload.getOrDefault("message", "").toString().trim();
        String username = payload.getOrDefault("username", "desconhecido").toString();

        if (channel.isEmpty()) {
            return makeResponse("error", map("message", "Canal nao informado."));
        }

        if (!getList(state, "channels").contains(channel)) {
            return makeResponse("error", map("message", "Canal " + channel + " nao existe."));
        }

        if (message.isEmpty()) {
            return makeResponse("error", map("message", "Mensagem vazia."));
        }

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

        System.out.println("[PUB-JAVA] " + username + " publicou em " + channel + ": " + message);
        System.out.println("[STATE-JAVA] Estado salvo: " + getList(state, "publications").size() + " publicacao(oes).");

        return makeResponse("ok", map("message", "Publicacao enviada com sucesso."));
    }

    private static void handlePeerControl(
        ZMQ.Socket peerRep,
        ZMQ.Socket refSocket,
        ZMQ.Socket pubSocket,
        Map<String, Object> state
    ) throws Exception {
        byte[] raw = peerRep.recv();
        Map<String, Object> msg = MsgHelper.unpack(raw);

        String type = String.valueOf(msg.getOrDefault("type", ""));

        if ("election".equals(type)) {
            peerRep.send(MsgHelper.pack(mapOf("status", "ok", "from", serverName)));

            int senderRank = ((Number) msg.getOrDefault("rank", Integer.MAX_VALUE)).intValue();

            if (senderRank > serverRank) {
                startElection(refSocket, pubSocket);
            }

            return;
        }

        if ("berkeley_time_request".equals(type)) {
            if (serverName.equals(coordinatorName)) {
                peerRep.send(MsgHelper.pack(mapOf("status", "ok", "reference_time", now())));
            } else {
                peerRep.send(MsgHelper.pack(mapOf("status", "error", "message", "nao sou coordenador")));
            }
            return;
        }

        if ("client_request".equals(type)) {
            refreshCoordinator(refSocket, pubSocket);

            if (!serverName.equals(coordinatorName)) {
                peerRep.send(MsgHelper.pack(makeResponse("error", map("message", "este servidor nao e coordenador"))));
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> requestMsg = (Map<String, Object>) msg.getOrDefault("message", new LinkedHashMap<>());

            mergeClock(requestMsg.get("logical_clock"));

            Map<String, Object> response = processClientMessage(requestMsg, state, pubSocket);
            peerRep.send(MsgHelper.pack(response));
            return;
        }

        peerRep.send(MsgHelper.pack(mapOf("status", "error", "message", "mensagem peer desconhecida")));
    }

    private static void refreshCoordinator(ZMQ.Socket refSocket, ZMQ.Socket pubSocket) throws Exception {
        List<Object> servers = listServers(refSocket);

        if (servers.isEmpty()) {
            coordinatorName = serverName;
            publishCoordinator(pubSocket);
            return;
        }

        Map<String, Object> electedServer = null;
        int bestRank = Integer.MAX_VALUE;

        for (Object entry : servers) {
            if (entry instanceof Map<?, ?> server) {
                Object rankObj = server.containsKey("rank") ? server.get("rank") : Integer.MAX_VALUE;
                int rank = ((Number) rankObj).intValue();

                if (rank < bestRank) {
                    bestRank = rank;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> casted = (Map<String, Object>) server;
                    electedServer = casted;
                }
            }
        }

        if (electedServer == null) {
            coordinatorName = serverName;
            publishCoordinator(pubSocket);
            return;
        }

        Object electedNameObj = electedServer.get("name");
        String electedName = electedNameObj != null ? electedNameObj.toString() : serverName;        String previousCoordinator = coordinatorName;
        coordinatorName = electedName;

        if (!coordinatorName.equals(previousCoordinator)) {
            System.out.println("[COORD-JAVA] Coordenador atual: " + coordinatorName);
        }

        if (serverName.equals(coordinatorName)) {
            if (!serverName.equals(previousCoordinator)) {
                System.out.println("[COORD-JAVA] Este servidor agora e o coordenador.");
                publishCoordinator(pubSocket);
            }
            return;
        }

        Map<String, Object> reply = peerRequest(
            electedServer,
            mapOf("type", "berkeley_time_request", "from", serverName)
        );

        if (reply == null || !"ok".equals(reply.get("status"))) {
            System.out.println("[COORD-JAVA] Coordenador esperado nao respondeu: " + coordinatorName);
            startElection(refSocket, pubSocket);
        }
    }

    private static void startElection(ZMQ.Socket refSocket, ZMQ.Socket pubSocket) throws Exception {
        List<Object> servers = listServers(refSocket);
        boolean gotOk = false;

        for (Object entry : servers) {
            if (entry instanceof Map<?, ?> server) {
                Object rankObj = server.containsKey("rank") ? server.get("rank") : Integer.MAX_VALUE;
                int rank = ((Number) rankObj).intValue();
                Object nameObj = server.get("name");
                String name = nameObj != null ? nameObj.toString() : "";
                if (rank < serverRank && !name.equals(serverName)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> target = (Map<String, Object>) server;

                    Map<String, Object> reply = peerRequest(
                        target,
                        mapOf("type", "election", "from", serverName, "rank", serverRank)
                    );

                    if (reply != null && "ok".equals(reply.get("status"))) {
                        gotOk = true;
                    }
                }
            }
        }

        if (!gotOk) {
            coordinatorName = serverName;
            System.out.println("[ELEICAO-JAVA] Nenhum servidor com rank menor respondeu. Java virou coordenador.");
            publishCoordinator(pubSocket);
        } else {
            System.out.println("[ELEICAO-JAVA] Servidor com rank menor respondeu. Java nao assumiu coordenacao.");
        }
    }

    private static Map<String, Object> peerRequest(Map<String, Object> target, Map<String, Object> payload) throws Exception {
        String host = String.valueOf(target.getOrDefault("host", ""));
        int port = ((Number) target.getOrDefault("peer_port", 0)).intValue();

        if (host.isEmpty() || port == 0) {
            return null;
        }

        try (ZContext ctx = new ZContext()) {
            ZMQ.Socket req = ctx.createSocket(SocketType.REQ);
            req.setReceiveTimeOut(800);
            req.setSendTimeOut(800);
            req.connect("tcp://" + host + ":" + port);
            req.send(MsgHelper.pack(payload));

            byte[] raw = req.recv();
            if (raw == null) {
                return null;
            }

            return MsgHelper.unpack(raw);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findCoordinator(ZMQ.Socket refSocket) throws Exception {
        List<Object> servers = listServers(refSocket);

        for (Object entry : servers) {
            if (entry instanceof Map<?, ?> server) {
                Object nameObj = server.get("name");
                String name = nameObj != null ? nameObj.toString() : "";                if (name.equals(coordinatorName)) {
                    return (Map<String, Object>) server;
                }
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listServers(ZMQ.Socket refSocket) throws Exception {
        Map<String, Object> reply = callReference(refSocket, mapOf("type", "list"));
        return (List<Object>) reply.getOrDefault("servers", new ArrayList<>());
    }

    private static void publishCoordinator(ZMQ.Socket pubSocket) throws Exception {
        pubSocket.sendMore(COORDINATOR_TOPIC.getBytes(StandardCharsets.UTF_8));
        pubSocket.send(MsgHelper.pack(mapOf("coordinator", serverName)));
    }

    private static void publishStateEvent(ZMQ.Socket pubSocket, Map<String, Object> event) throws Exception {
        pubSocket.sendMore(STATE_SYNC_TOPIC.getBytes(StandardCharsets.UTF_8));
        pubSocket.send(MsgHelper.pack(event));
    }

    private static void publishStateSnapshot(ZMQ.Socket pubSocket, Map<String, Object> state) throws Exception {
        Map<String, Object> channelsSnapshot = new LinkedHashMap<>();
        channelsSnapshot.put("type", "channels_snapshot");
        channelsSnapshot.put("channels", new ArrayList<>(getList(state, "channels")));
        publishStateEvent(pubSocket, channelsSnapshot);

        Map<String, Object> fullSnapshot = new LinkedHashMap<>();
        fullSnapshot.put("type", "state_snapshot");
        fullSnapshot.put("logins", new ArrayList<>(getList(state, "logins")));
        fullSnapshot.put("channels", new ArrayList<>(getList(state, "channels")));
        fullSnapshot.put("publications", new ArrayList<>(getList(state, "publications")));
        publishStateEvent(pubSocket, fullSnapshot);

        System.out.println(
            "[SNAPSHOT-JAVA] Snapshot enviado: "
                + getList(state, "logins").size() + " login(s), "
                + getList(state, "channels").size() + " canal(is), "
                + getList(state, "publications").size() + " publicacao(oes)."
        );
    }

    @SuppressWarnings("unchecked")
    private static void drainSubscriptions(ZMQ.Socket subSocket, Map<String, Object> state) throws Exception {
        while (true) {
            byte[] topicRaw = subSocket.recv(ZMQ.DONTWAIT);
            if (topicRaw == null) {
                return;
            }

            byte[] payloadRaw = subSocket.recv();
            if (payloadRaw == null) {
                return;
            }

            String topic = new String(topicRaw, StandardCharsets.UTF_8);

            if (COORDINATOR_TOPIC.equals(topic)) {
                Map<String, Object> event = MsgHelper.unpack(payloadRaw);
                Object coordinator = event.get("coordinator");
                if (coordinator != null) {
                    coordinatorName = coordinator.toString();
                    System.out.println("[COORD-JAVA] Coordenador recebido via PUB: " + coordinatorName);
                }
                continue;
            }

            if (!STATE_SYNC_TOPIC.equals(topic)) {
                continue;
            }

            Map<String, Object> event = MsgHelper.unpack(payloadRaw);
            applyStateSyncEvent(event, state);
        }
    }

    private static void applyStateSyncEvent(Map<String, Object> event, Map<String, Object> state) throws Exception {
        String type = String.valueOf(event.getOrDefault("type", ""));

        List<Object> logins = getList(state, "logins");
        List<Object> channels = getList(state, "channels");
        List<Object> publications = getList(state, "publications");

        boolean changed = false;

        if ("login_created".equals(type)) {
            Object login = event.get("login");
            if (login instanceof Map<?, ?> && !logins.contains(login)) {
                logins.add(login);
                changed = true;
            }
        } else if ("channel_created".equals(type)) {
            String channel = event.getOrDefault("channel", "").toString().trim();
            if (!channel.isEmpty() && !channels.contains(channel)) {
                channels.add(channel);
                changed = true;
            }
        } else if ("publication_created".equals(type)) {
            Object publication = event.get("publication");
            if (publication instanceof Map<?, ?> && !publications.contains(publication)) {
                publications.add(publication);
                changed = true;
            }
        } else if ("channels_snapshot".equals(type)) {
            Object rawChannels = event.get("channels");
            if (rawChannels instanceof List<?> list) {
                for (Object item : list) {
                    String channel = String.valueOf(item).trim();
                    if (!channel.isEmpty() && !channels.contains(channel)) {
                        channels.add(channel);
                        changed = true;
                    }
                }
            }
        } else if ("state_snapshot".equals(type)) {
            Object rawLogins = event.get("logins");
            if (rawLogins instanceof List<?> list) {
                for (Object login : list) {
                    if (login instanceof Map<?, ?> && !logins.contains(login)) {
                        logins.add(login);
                        changed = true;
                    }
                }
            }

            Object rawChannels = event.get("channels");
            if (rawChannels instanceof List<?> list) {
                for (Object item : list) {
                    String channel = String.valueOf(item).trim();
                    if (!channel.isEmpty() && !channels.contains(channel)) {
                        channels.add(channel);
                        changed = true;
                    }
                }
            }

            Object rawPublications = event.get("publications");
            if (rawPublications instanceof List<?> list) {
                for (Object publication : list) {
                    if (publication instanceof Map<?, ?> && !publications.contains(publication)) {
                        publications.add(publication);
                        changed = true;
                    }
                }
            }
        }

        if (changed) {
            saveState(state);
            System.out.println(
                "[SYNC-JAVA] Estado sincronizado: "
                    + logins.size() + " login(s), "
                    + channels.size() + " canal(is), "
                    + publications.size() + " publicacao(oes)."
            );
        }
    }

    private static boolean isWriteOperation(String type) {
        return "login".equals(type)
            || "create_channel".equals(type)
            || "publish_message".equals(type);
    }

    private static Map<String, Object> makeResponse(String status, Map<String, Object> payload) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("timestamp", now());
        response.put("logical_clock", tickClock());
        response.put("payload", payload);
        return response;
    }

    private static synchronized void mergeClock(Object received) {
        long receivedClock = received == null ? 0 : ((Number) received).longValue();
        logicalClock = Math.max(logicalClock, receivedClock);
    }

    private static synchronized long tickClock() {
        logicalClock += 1;
        return logicalClock;
    }

    private static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put(values[i].toString(), values[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> callReference(ZMQ.Socket refSocket, Map<String, Object> request) throws Exception {
        refSocket.send(MsgHelper.pack(request));
        return MsgHelper.unpack(refSocket.recv());
    }

    private static double toDouble(Object value) {
        if (value instanceof Double) return (Double) value;
        if (value instanceof Float) return ((Float) value).doubleValue();
        if (value instanceof Long) return ((Long) value).doubleValue();
        if (value instanceof Integer) return ((Integer) value).doubleValue();
        return Double.parseDouble(value.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> getList(Map<String, Object> state, String key) {
        return (List<Object>) state.computeIfAbsent(key, unused -> new ArrayList<>());
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
            System.out.println("[STATE-JAVA] Estado invalido. Reiniciando state.");
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