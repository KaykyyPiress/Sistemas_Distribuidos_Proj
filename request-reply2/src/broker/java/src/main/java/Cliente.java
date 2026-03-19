
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import java.util.*;

public class Cliente {

    private static final String BROKER_URL = "tcp://broker:5555";
    private static final String USERNAME    = "bot_java_01";
    private static final String CANAL       = "geral";

    public static void main(String[] args) throws Exception {
        try (ZContext ctx = new ZContext()) {
            ZMQ.Socket socket = ctx.createSocket(SocketType.REQ);
            socket.connect(BROKER_URL);
            System.out.println("[CLIENTE-JAVA] Conectado ao broker em " + BROKER_URL);

            fazerLogin(socket, USERNAME);
            List<String> canais = listarCanais(socket);
            if (!canais.contains(CANAL)) {
                criarCanal(socket, USERNAME, CANAL);
            } else {
                System.out.println("[CLIENTE-JAVA] Canal ja existe, pulando criacao.");
            }
            listarCanais(socket);
            System.out.println("[CLIENTE-JAVA] Encerrando.");
        }
    }

    private static void fazerLogin(ZMQ.Socket socket, String username) throws Exception {
        while (true) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("username", username);
            Map<String, Object> req = buildRequest("login", payload);
            Map<String, Object> reply = sendRequest(socket, req);
            System.out.println("[LOGIN] Resposta: " + reply);
            if ("ok".equals(reply.get("status"))) {
                System.out.println("[LOGIN] Login bem-sucedido como: " + username);
                return;
            }
            Object msg = ((Map<?,?>) reply.getOrDefault("payload", new HashMap<>())).get("message");
            System.out.println("[LOGIN] Falha: " + msg + ". Tentando em 3s...");
            Thread.sleep(3000);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> listarCanais(ZMQ.Socket socket) throws Exception {
        Map<String, Object> req = buildRequest("list_channels", new LinkedHashMap<>());
        Map<String, Object> reply = sendRequest(socket, req);
        System.out.println("[LISTAR CANAIS] Resposta: " + reply);
        if ("ok".equals(reply.get("status"))) {
            Map<String, Object> pl = (Map<String, Object>) reply.getOrDefault("payload", new HashMap<>());
            List<Object> raw = (List<Object>) pl.getOrDefault("channels", new ArrayList<>());
            List<String> canais = new ArrayList<>();
            for (Object o : raw) canais.add(o.toString());
            System.out.println("[LISTAR CANAIS] Canais: " + canais);
            return canais;
        }
        return new ArrayList<>();
    }

    private static void criarCanal(ZMQ.Socket socket, String username, String canal) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("channel", canal);
        Map<String, Object> req = buildRequest("create_channel", payload);
        Map<String, Object> reply = sendRequest(socket, req);
        System.out.println("[CRIAR CANAL] Resposta: " + reply);
        if ("ok".equals(reply.get("status"))) {
            System.out.println("[CRIAR CANAL] Canal criado: " + canal);
        } else {
            Object msg = ((Map<?,?>) reply.getOrDefault("payload", new HashMap<>())).get("message");
            System.out.println("[CRIAR CANAL] Erro: " + msg);
        }
    }

    private static Map<String, Object> buildRequest(String type, Map<String, Object> payload) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("type", type);
        req.put("timestamp", (double) System.currentTimeMillis() / 1000.0);
        req.put("payload", payload);
        return req;
    }

    private static Map<String, Object> sendRequest(ZMQ.Socket socket, Map<String, Object> req) throws Exception {
        System.out.println("[CLIENTE-JAVA] Enviando: " + req);
        socket.send(MsgHelper.pack(req));
        byte[] raw = socket.recv();
        return MsgHelper.unpack(raw);
    }
}
