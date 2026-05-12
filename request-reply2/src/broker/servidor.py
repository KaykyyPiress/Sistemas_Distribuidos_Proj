import os
import re
import socket as pysocket
import time
from pathlib import Path

import msgpack
import zmq

STATE_FILE = Path("state.msgpack")

USERNAME_REGEX = re.compile(r"^[a-zA-Z0-9_]{3,20}$")
CHANNEL_REGEX = re.compile(r"^[a-zA-Z0-9_-]{3,50}$")

DEFAULT_STATE = {
    "logins": [],
    "channels": [],
    "publications": [],
}

HEARTBEAT_EVERY_MESSAGES = 10
SYNC_EVERY_MESSAGES = 15

STATE_SYNC_TOPIC = "servers.state"
COORDINATOR_TOPIC = "servers"


class LamportClock:
    def __init__(self):
        self.value = 0

    def merge(self, received):
        self.value = max(self.value, int(received or 0))

    def tick(self):
        self.value += 1
        return self.value


def load_state():
    if not STATE_FILE.exists() or STATE_FILE.stat().st_size == 0:
        return {
            "logins": [],
            "channels": [],
            "publications": [],
        }

    try:
        with STATE_FILE.open("rb") as f:
            saved = msgpack.unpackb(f.read(), raw=False)

        if not isinstance(saved, dict):
            raise ValueError("estado persistido invalido")

        saved.setdefault("logins", [])
        saved.setdefault("channels", [])
        saved.setdefault("publications", [])
        return saved

    except Exception as exc:
        print(f"[SERVIDOR] Estado inválido ({exc}), reiniciando.", flush=True)
        return {
            "logins": [],
            "channels": [],
            "publications": [],
        }


def save_state(state):
    tmp_file = STATE_FILE.with_suffix(".tmp")
    payload = msgpack.packb(state, use_bin_type=True)

    with tmp_file.open("wb") as f:
        f.write(payload)
        f.flush()
        os.fsync(f.fileno())

    tmp_file.replace(STATE_FILE)


def make_response(status, lamport_clock, now_fn, payload=None):
    return {
        "status": status,
        "timestamp": now_fn(),
        "logical_clock": lamport_clock.tick(),
        "payload": payload or {},
    }


def publish_topic(pub_socket, topic, payload):
    pub_socket.send_multipart(
        [
            topic.encode("utf-8"),
            msgpack.packb(payload, use_bin_type=True),
        ]
    )


def handle_login(msg, state, lamport_clock, now_fn):
    username = str(msg.get("payload", {}).get("username", "")).strip()

    if not username:
        return make_response(
            "error",
            lamport_clock,
            now_fn,
            {"message": "Username nao informado."},
        )

    if not USERNAME_REGEX.match(username):
        return make_response(
            "error",
            lamport_clock,
            now_fn,
            {"message": "Username invalido. Use 3-20 caracteres alfanumericos ou underscore."},
        )

    login_timestamp = msg.get("timestamp", now_fn())
    login_entry = {
        "username": username,
        "login_timestamp": login_timestamp,
    }

    state["logins"].append(login_entry)
    save_state(state)

    print(f"[LOGIN] {username} fez login em {login_timestamp}", flush=True)
    print(f"[STATE] {len(state['logins'])} login(s) persistido(s).", flush=True)

    return make_response(
        "ok",
        lamport_clock,
        now_fn,
        {"message": f"Bem-vindo, {username}!"},
    )


def handle_create_channel(msg, state, lamport_clock, now_fn):
    channel = str(msg.get("payload", {}).get("channel", "")).strip()
    username = str(msg.get("payload", {}).get("username", "desconhecido")).strip()

    if not channel:
        return make_response(
            "error",
            lamport_clock,
            now_fn,
            {"message": "Nome do canal nao informado."},
        )

    if not CHANNEL_REGEX.match(channel):
        return make_response(
            "error",
            lamport_clock,
            now_fn,
            {"message": "Nome de canal invalido. Use 3-50 caracteres alfanumericos, underscore ou hifen."},
        )

    if channel in state["channels"]:
        return make_response(
            "error",
            lamport_clock,
            now_fn,
            {"message": f"Canal {channel} ja existe."},
        )

    state["channels"].append(channel)
    save_state(state)

    print(f"[CANAL] {username} criou o canal {channel}", flush=True)
    print(f"[STATE] {len(state['channels'])} canal(is) persistido(s).", flush=True)

    return make_response(
        "ok",
        lamport_clock,
        now_fn,
        {"message": f"Canal {channel} criado com sucesso."},
    )


def handle_list_channels(state, lamport_clock, now_fn):
    channels = list(state.get("channels", []))
    print(f"[LISTAR CANAIS] Enviando {len(channels)} canal(is).", flush=True)

    return make_response(
        "ok",
        lamport_clock,
        now_fn,
        {"channels": channels},
    )


def handle_publish_message(msg, state, pub_socket, lamport_clock, now_fn):
    payload = msg.get("payload", {})
    username = str(payload.get("username", "desconhecido")).strip()
    channel = str(payload.get("channel", "")).strip()
    content = str(payload.get("message", "")).strip()

    if not channel:
        return make_response(
            "error",
            lamport_clock,
            now_fn,
            {"message": "Canal nao informado."},
        )

    if channel not in state["channels"]:
        return make_response(
            "error",
            lamport_clock,
            now_fn,
            {"message": f"Canal {channel} nao existe."},
        )

    if not content:
        return make_response(
            "error",
            lamport_clock,
            now_fn,
            {"message": "Mensagem vazia."},
        )

    sent_timestamp = msg.get("timestamp", now_fn())
    published_timestamp = now_fn()

    publication = {
        "channel": channel,
        "message": content,
        "username": username,
        "sent_timestamp": sent_timestamp,
        "published_timestamp": published_timestamp,
        "logical_clock": lamport_clock.tick(),
    }

    publish_topic(pub_socket, channel, publication)

    state["publications"].append(publication)
    save_state(state)

    print(
        f"[PUB] {username} publicou em {channel}: {content} "
        f"(sent={sent_timestamp}, pub={published_timestamp}, lc={publication['logical_clock']})",
        flush=True,
    )
    print(f"[STATE] {len(state['publications'])} publicacao(oes) persistida(s).", flush=True)

    return make_response(
        "ok",
        lamport_clock,
        now_fn,
        {"message": "Publicacao enviada com sucesso."},
    )


def apply_state_sync_event(event, state):
    event_type = event.get("type")
    changed = False

    if event_type == "login_created":
        login = event.get("login", {})
        if isinstance(login, dict) and login not in state["logins"]:
            state["logins"].append(login)
            changed = True

    elif event_type == "channel_created":
        channel = str(event.get("channel", "")).strip()
        if channel and channel not in state["channels"]:
            state["channels"].append(channel)
            changed = True

    elif event_type == "publication_created":
        publication = event.get("publication", {})
        if isinstance(publication, dict) and publication not in state["publications"]:
            state["publications"].append(publication)
            changed = True

    elif event_type == "channels_snapshot":
        for channel in event.get("channels", []):
            channel = str(channel).strip()
            if channel and channel not in state["channels"]:
                state["channels"].append(channel)
                changed = True

    elif event_type == "state_snapshot":
        for login in event.get("logins", []):
            if isinstance(login, dict) and login not in state["logins"]:
                state["logins"].append(login)
                changed = True

        for channel in event.get("channels", []):
            channel = str(channel).strip()
            if channel and channel not in state["channels"]:
                state["channels"].append(channel)
                changed = True

        for publication in event.get("publications", []):
            if isinstance(publication, dict) and publication not in state["publications"]:
                state["publications"].append(publication)
                changed = True

    if changed:
        save_state(state)
        print(
            f"[SYNC] Estado sincronizado. "
            f"logins={len(state['logins'])}, "
            f"channels={len(state['channels'])}, "
            f"publications={len(state['publications'])}",
            flush=True,
        )


def main():
    server_name = os.getenv("SERVER_NAME") or pysocket.gethostname()
    peer_host = os.getenv("PEER_HOST", server_name)
    peer_port = int(os.getenv("PEER_PORT", "6000"))

    state = load_state()
    lamport_clock = LamportClock()

    clock_offset = 0.0
    coordinator = server_name
    server_rank = -1

    def now_synced():
        return time.time() + clock_offset

    context = zmq.Context()

    rep_socket = context.socket(zmq.REP)
    rep_socket.connect("tcp://broker:5556")

    pub_socket = context.socket(zmq.PUB)
    pub_socket.connect("tcp://pubsub-proxy:5557")

    sub_socket = context.socket(zmq.SUB)
    sub_socket.connect("tcp://pubsub-proxy:5558")
    sub_socket.setsockopt_string(zmq.SUBSCRIBE, STATE_SYNC_TOPIC)
    sub_socket.setsockopt_string(zmq.SUBSCRIBE, COORDINATOR_TOPIC)

    ref_socket = context.socket(zmq.REQ)
    ref_socket.connect("tcp://reference:5559")

    peer_rep = context.socket(zmq.REP)
    peer_rep.bind(f"tcp://*:{peer_port}")

    poller = zmq.Poller()
    poller.register(rep_socket, zmq.POLLIN)
    poller.register(peer_rep, zmq.POLLIN)
    poller.register(sub_socket, zmq.POLLIN)

    def call_reference(req):
        nonlocal clock_offset

        ref_socket.send(msgpack.packb(req, use_bin_type=True))
        reply = msgpack.unpackb(ref_socket.recv(), raw=False)

        if "reference_time" in reply:
            try:
                clock_offset = float(reply["reference_time"]) - time.time()
            except Exception:
                pass

        return reply

    def reference_list():
        return call_reference({"type": "list"}).get("servers", [])

    def publish_coordinator():
        publish_topic(pub_socket, COORDINATOR_TOPIC, {"coordinator": coordinator})
        print(f"[COORD] Coordenador anunciado: {coordinator}", flush=True)

    def publish_state_snapshot():
        publish_topic(
            pub_socket,
            STATE_SYNC_TOPIC,
            {
                "type": "channels_snapshot",
                "channels": list(state.get("channels", [])),
            },
        )

        publish_topic(
            pub_socket,
            STATE_SYNC_TOPIC,
            {
                "type": "state_snapshot",
                "logins": list(state.get("logins", [])),
                "channels": list(state.get("channels", [])),
                "publications": list(state.get("publications", [])),
            },
        )

        print(
            f"[SNAPSHOT] Snapshot enviado. "
            f"logins={len(state['logins'])}, "
            f"channels={len(state['channels'])}, "
            f"publications={len(state['publications'])}",
            flush=True,
        )

    def peer_request(server_info, payload):
        host = server_info.get("host")
        port = int(server_info.get("peer_port") or 0)

        if not host or not port:
            return None

        s = context.socket(zmq.REQ)
        s.setsockopt(zmq.RCVTIMEO, 800)
        s.setsockopt(zmq.SNDTIMEO, 800)
        s.connect(f"tcp://{host}:{port}")

        try:
            s.send(msgpack.packb(payload, use_bin_type=True))
            return msgpack.unpackb(s.recv(), raw=False)
        except Exception:
            return None
        finally:
            s.close(0)

    def start_election(servers=None):
        nonlocal coordinator

        if servers is None:
            servers = reference_list()

        better = [
            s
            for s in servers
            if int(s.get("rank", 10**9)) < server_rank and s.get("name") != server_name
        ]

        got_ok = False

        for server in better:
            reply = peer_request(
                server,
                {
                    "type": "election",
                    "from": server_name,
                    "rank": server_rank,
                },
            )
            if reply and reply.get("status") == "ok":
                got_ok = True

        if not got_ok:
            coordinator = server_name
            print(f"[ELEICAO] {server_name} assumiu a coordenacao.", flush=True)
            publish_coordinator()
        else:
            print(f"[ELEICAO] {server_name} nao assumiu a coordenacao.", flush=True)

    def refresh_coordinator():
        nonlocal coordinator, clock_offset

        servers = reference_list()

        if not servers:
            previous = coordinator
            coordinator = server_name
            if previous != coordinator:
                publish_coordinator()
            return

        elected = min(servers, key=lambda s: int(s.get("rank", 10**9)))
        previous = coordinator
        coordinator = elected.get("name", server_name)

        if previous != coordinator:
            print(f"[COORD] Coordenador atual: {coordinator}", flush=True)

        if coordinator == server_name and previous != coordinator:
            publish_coordinator()

        if coordinator != server_name:
            target = next((s for s in servers if s.get("name") == coordinator), None)
            reply = (
                peer_request(target, {"type": "berkeley_time_request", "from": server_name})
                if target
                else None
            )

            if reply and reply.get("status") == "ok" and "reference_time" in reply:
                try:
                    clock_offset = float(reply["reference_time"]) - time.time()
                except Exception:
                    pass
            else:
                print(f"[COORD] Coordenador {coordinator} indisponivel. Iniciando eleicao.", flush=True)
                start_election(servers)

    def process_client_message(msg):
        msg_type = msg.get("type", "")

        if msg_type == "login":
            response = handle_login(msg, state, lamport_clock, now_synced)
            if response.get("status") == "ok":
                username = str(msg.get("payload", {}).get("username", "")).strip()
                login_timestamp = msg.get("timestamp", now_synced())
                publish_topic(
                    pub_socket,
                    STATE_SYNC_TOPIC,
                    {
                        "type": "login_created",
                        "login": {
                            "username": username,
                            "login_timestamp": login_timestamp,
                        },
                    },
                )
            return response

        if msg_type == "create_channel":
            response = handle_create_channel(msg, state, lamport_clock, now_synced)
            if response.get("status") == "ok":
                created_channel = str(msg.get("payload", {}).get("channel", "")).strip()
                publish_topic(
                    pub_socket,
                    STATE_SYNC_TOPIC,
                    {
                        "type": "channel_created",
                        "channel": created_channel,
                    },
                )
            return response

        if msg_type == "list_channels":
            return handle_list_channels(state, lamport_clock, now_synced)

        if msg_type == "publish_message":
            response = handle_publish_message(msg, state, pub_socket, lamport_clock, now_synced)
            if response.get("status") == "ok":
                publication = state["publications"][-1]
                publish_topic(
                    pub_socket,
                    STATE_SYNC_TOPIC,
                    {
                        "type": "publication_created",
                        "publication": publication,
                    },
                )
            return response

        return make_response(
            "error",
            lamport_clock,
            now_synced,
            {"message": f"Operacao desconhecida: {msg_type}"},
        )

    register_reply = call_reference(
        {
            "type": "register",
            "name": server_name,
            "host": peer_host,
            "peer_port": peer_port,
        }
    )
    server_rank = int(register_reply.get("rank", -1))

    print(
        f"[SERVIDOR] {server_name} rank={server_rank}. "
        f"Estado: {len(state['logins'])} login(s), "
        f"{len(state['channels'])} canal(is), "
        f"{len(state['publications'])} pub(s).",
        flush=True,
    )

    refresh_coordinator()

    messages_since_heartbeat = 0
    messages_since_sync = 0

    while True:
        try:
            events = dict(poller.poll(500))

            if sub_socket in events:
                while True:
                    try:
                        topic_raw, raw_event = sub_socket.recv_multipart(flags=zmq.NOBLOCK)
                    except zmq.Again:
                        break

                    topic = topic_raw.decode("utf-8")

                    if topic == STATE_SYNC_TOPIC:
                        event = msgpack.unpackb(raw_event, raw=False)
                        apply_state_sync_event(event, state)

                    elif topic == COORDINATOR_TOPIC:
                        event = msgpack.unpackb(raw_event, raw=False)
                        announced = str(event.get("coordinator", "")).strip()
                        if announced:
                            coordinator = announced
                            print(f"[COORD] Coordenador recebido via PUB: {coordinator}", flush=True)

            if peer_rep in events:
                raw_peer = peer_rep.recv()
                peer_msg = msgpack.unpackb(raw_peer, raw=False)
                peer_type = peer_msg.get("type", "")

                if peer_type == "election":
                    peer_rep.send(msgpack.packb({"status": "ok", "from": server_name}, use_bin_type=True))

                    if int(peer_msg.get("rank", 10**9)) > server_rank:
                        start_election()

                elif peer_type == "berkeley_time_request":
                    if coordinator == server_name:
                        peer_rep.send(
                            msgpack.packb(
                                {"status": "ok", "reference_time": now_synced()},
                                use_bin_type=True,
                            )
                        )
                    else:
                        peer_rep.send(
                            msgpack.packb(
                                {"status": "error", "message": "nao sou coordenador"},
                                use_bin_type=True,
                            )
                        )

                elif peer_type == "client_request":
                    payload_msg = peer_msg.get("message", {})
                    lamport_clock.merge(payload_msg.get("logical_clock", 0))

                    refresh_coordinator()

                    if coordinator != server_name:
                        peer_rep.send(
                            msgpack.packb(
                                make_response(
                                    "error",
                                    lamport_clock,
                                    now_synced,
                                    {"message": "este servidor nao e o coordenador"},
                                ),
                                use_bin_type=True,
                            )
                        )
                    else:
                        response = process_client_message(payload_msg)
                        peer_rep.send(msgpack.packb(response, use_bin_type=True))

                else:
                    peer_rep.send(
                        msgpack.packb(
                            {"status": "error", "message": "mensagem peer desconhecida"},
                            use_bin_type=True,
                        )
                    )

            if rep_socket in events:
                raw = rep_socket.recv()
                msg = msgpack.unpackb(raw, raw=False)

                lamport_clock.merge(msg.get("logical_clock", 0))
                msg_type = msg.get("type", "")

                print(
                    f"[SERVIDOR] Mensagem recebida: type={msg_type}, lc={lamport_clock.value}",
                    flush=True,
                )

                write_ops = {"login", "create_channel", "publish_message"}

                if msg_type in write_ops:
                    refresh_coordinator()

                if msg_type in write_ops and coordinator != server_name:
                    servers = reference_list()
                    target = next((s for s in servers if s.get("name") == coordinator), None)

                    forwarded = (
                        peer_request(
                            target,
                            {"type": "client_request", "message": msg},
                        )
                        if target
                        else None
                    )

                    if forwarded is None:
                        print("[FORWARD] Coordenador indisponivel. Tentando eleicao.", flush=True)
                        start_election(servers)

                        if coordinator == server_name:
                            response = process_client_message(msg)
                        else:
                            response = make_response(
                                "error",
                                lamport_clock,
                                now_synced,
                                {"message": "coordinator indisponivel"},
                            )
                    else:
                        response = forwarded
                else:
                    response = process_client_message(msg)

                rep_socket.send(msgpack.packb(response, use_bin_type=True))

                messages_since_heartbeat += 1
                messages_since_sync += 1

                if messages_since_heartbeat >= HEARTBEAT_EVERY_MESSAGES:
                    call_reference(
                        {
                            "type": "heartbeat",
                            "name": server_name,
                            "rank": server_rank,
                            "host": peer_host,
                            "peer_port": peer_port,
                        }
                    )
                    print(
                        f"[HEARTBEAT] {server_name} enviou heartbeat apos 10 mensagens de cliente.",
                        flush=True,
                    )
                    messages_since_heartbeat = 0

                if messages_since_sync >= SYNC_EVERY_MESSAGES:
                    refresh_coordinator()
                    publish_state_snapshot()
                    messages_since_sync = 0

        except Exception as exc:
            print(f"[SERVIDOR] Erro inesperado: {exc}", flush=True)
            try:
                rep_socket.send(
                    msgpack.packb(
                        make_response(
                            "error",
                            lamport_clock,
                            now_synced,
                            {"code": "SERVER_ERROR", "message": str(exc)},
                        ),
                        use_bin_type=True,
                    )
                )
            except Exception:
                pass


if __name__ == "__main__":
    main()