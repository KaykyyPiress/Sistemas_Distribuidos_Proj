import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class Cliente {

    private static final String BROKER_URL = "tcp://broker:5555";
    private static final String SUB_URL = "tcp://pubsub-proxy:5558";
    private static final String USERNAME = "bot_java_01";
    private static final int MIN_CHANNELS = 5;
    private static final int MIN_SUBSCRIPTIONS = 3;

    private static long logicalClock = 0;

    public static void main(String[] args) throws Exception {
        try (ZContext ctx = new ZContext()) {
            ZMQ.Socket req = ctx.createSocket(SocketType.REQ);
            req.connect(BROKER_URL);

            ZMQ.Socket sub = ctx.createSocket(SocketType.SUB);
            sub.connect(SUB_URL);

            final boolean[] running = new boolean[]{true};
            Thread listener = new Thread(() -> listenSubscriptions(sub, running));
            listener.setDaemon(true);
            listener.start();

            Random random = new Random();
            Set<String> subscribedChannels = new HashSet<>();

            try {
                fazerLogin(req, USERNAME);

                List<String> canais = listarCanais(req);
                if (canais.size() < MIN_CHANNELS) {
                    String novoCanal = generateChannelName(USERNAME, random);
                    if (criarCanal(req, USERNAME, novoCanal)) {
                        canais = listarCanais(req);
                    }
                }

                while (true) {
                    canais = listarCanais(req);
                    if (canais.isEmpty()) {
                        Thread.sleep(2000);
                        continue;
                    }

                    subscribeIfNeeded(sub, canais, subscribedChannels, random);

                    for (int i = 0; i < 10; i++) {
                        String canal = canais.get(random.nextInt(canais.size()));
                        String mensagem = generateMessage(random);
                        publicar(req, USERNAME, canal, mensagem);
                        Thread.sleep(1000);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running[0] = false;
                listener.join(500);
            }
        }
    }

    private static void listenSubscriptions(ZMQ.Socket sub, boolean[] running) {
        sub.setReceiveTimeOut(200);

        while (running[0] && !Thread.currentThread().isInterrupted()) {
            byte[] topic = sub.recv(0);
            if (topic == null) {
                continue;
            }
            byte[] payloadRaw = sub.recv(0);
            if (payloadRaw == null) {
                continue;
            }

            try {
                Map<String, Object> payload = MsgHelper.unpack(payloadRaw);
                mergeClock(payload.get("logical_clock"));

                String channel = payload.getOrDefault("channel", new String(topic, StandardCharsets.UTF_8)).toString();
                String message = payload.getOrDefault("message", "").toString();
                Object sentTimestamp = payload.getOrDefault("sent_timestamp", "?");
                double receivedTimestamp = now();

                System.out.println(
                    "[SUB-JAVA] canal=" + channel
                        + " | mensagem=" + message
                        + " | ts_envio=" + sentTimestamp
                        + " | ts_recebimento=" + receivedTimestamp
                        + " | lc=" + logicalClock
                );
            } catch (Exception e) {
                System.out.println("[SUB-JAVA] Erro ao processar mensagem: " + e.getMessage());
            }
        }
    }

    private static void subscribeIfNeeded(ZMQ.Socket sub, List<String> channels, Set<String> subscribed, Random random) {
        while (subscribed.size() < MIN_SUBSCRIPTIONS) {
            List<String> candidates = new ArrayList<>();
            for (String c : channels) {
                if (!subscribed.contains(c)) candidates.add(c);
            }
            if (candidates.isEmpty()) {
                return;
            }
            String selected = candidates.get(random.nextInt(candidates.size()));
            sub.subscribe(selected.getBytes(StandardCharsets.UTF_8));
            subscribed.add(selected);
            System.out.println("[SUB-JAVA] Inscrito no canal " + selected);
        }
    }

    private static void fazerLogin(ZMQ.Socket socket, String username) throws Exception {
        while (true) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("username", username);
            Map<String, Object> req = buildRequest("login", payload);
            Map<String, Object> reply = sendRequest(socket, req);
            if ("ok".equals(reply.get("status"))) {
                return;
            }
            Thread.sleep(3000);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> listarCanais(ZMQ.Socket socket) throws Exception {
        Map<String, Object> req = buildRequest("list_channels", new LinkedHashMap<>());
        Map<String, Object> reply = sendRequest(socket, req);
        if ("ok".equals(reply.get("status"))) {
            Map<String, Object> pl = (Map<String, Object>) reply.getOrDefault("payload", new HashMap<>());
            List<Object> raw = (List<Object>) pl.getOrDefault("channels", new ArrayList<>());
            List<String> canais = new ArrayList<>();
            for (Object o : raw) canais.add(o.toString());
            return canais;
        }
        return new ArrayList<>();
    }

    private static boolean criarCanal(ZMQ.Socket socket, String username, String canal) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("channel", canal);
        Map<String, Object> req = buildRequest("create_channel", payload);
        Map<String, Object> reply = sendRequest(socket, req);
        return "ok".equals(reply.get("status"));
    }

    private static void publicar(ZMQ.Socket socket, String username, String canal, String mensagem) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("channel", canal);
        payload.put("message", mensagem);

        Map<String, Object> req = buildRequest("publish_message", payload);
        sendRequest(socket, req);
    }

    private static String generateChannelName(String username, Random random) {
        return username + "_" + randomSuffix(random, 5);
    }

    private static String generateMessage(Random random) {
        return "msg_" + randomSuffix(random, 8);
    }

    private static String randomSuffix(Random random, int size) {
        final String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static synchronized void mergeClock(Object received) {
        long r = (received == null) ? 0 : ((Number) received).longValue();
        logicalClock = Math.max(logicalClock, r);
    }

    private static synchronized long tickClock() {
        logicalClock += 1;
        return logicalClock;
    }

    private static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    private static Map<String, Object> buildRequest(String type, Map<String, Object> payload) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("type", type);
        req.put("timestamp", now());
        req.put("logical_clock", tickClock());
        req.put("payload", payload);
        return req;
    }

    private static Map<String, Object> sendRequest(ZMQ.Socket socket, Map<String, Object> req) throws Exception {
        socket.send(MsgHelper.pack(req));
        byte[] raw = socket.recv();
        Map<String, Object> reply = MsgHelper.unpack(raw);
        mergeClock(reply.get("logical_clock"));
        return reply;
    }
}
