
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

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

    public static void main(String[] args) throws Exception {
        Map<String, Object> state = loadState();
        System.out.println("[SERVIDOR-JAVA] Estado carregado: "
            + getList(state, "logins").size() + " login(s), "
            + getList(state, "channels").size() + " canal(is).");

        try (ZContext ctx = new ZContext()) {
            ZMQ.Socket socket = ctx.createSocket(SocketType.REP);
            socket.connect("tcp://broker:5556");
            System.out.println("[SERVIDOR-JAVA] Conectado ao broker na porta 5556. Aguardando...");

            while (!Thread.currentThread().isInterrupted()) {
                byte[] raw = socket.recv();
                Map<String, Object> msg = MsgHelper.unpack(raw);
                String type = (String) msg.getOrDefault("type", "");
                System.out.println("[SERVIDOR-JAVA] Mensagem recebida: type=" + type);

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
                    default:
                        response = makeResponse("error", map("message", "Operacao desconhecida: " + type));
                        break;
                }
                socket.send(MsgHelper.pack(response));
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
        System.out.println("[LISTAR CANAIS] Enviando " + channels.size() + " canal(is).");
        Map<String, Object> pl = new LinkedHashMap<>();
        pl.put("channels", channels);
        return makeResponse("ok", pl);
    }

    private static Map<String, Object> makeResponse(String status, Map<String, Object> payload) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", status);
        r.put("timestamp", now());
        r.put("payload", payload);
        return r;
    }

    private static Map<String, Object> map(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static double now() {
        return System.currentTimeMillis() / 1000.0;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("logins", new ArrayList<>());
        state.put("channels", new ArrayList<>());

        try {
            if (!Files.exists(STATE_FILE) || Files.size(STATE_FILE) == 0) {
                return state;
            }

            byte[] raw = Files.readAllBytes(STATE_FILE);
            Map<String, Object> persisted = MsgHelper.unpack(raw);
            if (persisted != null) {
                Object logins = persisted.get("logins");
                Object channels = persisted.get("channels");
                state.put("logins", logins instanceof List ? logins : new ArrayList<>());
                state.put("channels", channels instanceof List ? channels : new ArrayList<>());
            }
        } catch (Exception e) {
            System.out.println("[SERVIDOR-JAVA] Aviso: estado invalido ou corrompido. Reiniciando estado. Motivo: " + e.getMessage());
        }

        return state;
    }

    private static void saveState(Map<String, Object> state) throws Exception {
        Path tmp = Path.of(STATE_FILE.toString() + ".tmp");
        Files.write(tmp, MsgHelper.pack(state));
        Files.move(tmp, STATE_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
