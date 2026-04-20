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
DEFAULT_STATE = {"logins": [], "channels": [], "publications": []}
HEARTBEAT_EVERY_MESSAGES = 10


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
        return DEFAULT_STATE.copy()

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
        return DEFAULT_STATE.copy()


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


def handle_login(msg, state, lamport_clock, now_fn):
    username = msg.get("payload", {}).get("username", "").strip()
    if not username:
        return make_response("error", lamport_clock, now_fn, {"message": "Username nao informado."})
    if not USERNAME_REGEX.match(username):
        return make_response(
            "error",
            lamport_clock,
            now_fn,
            {"message": "Username invalido. Use 3-20 caracteres alfanumericos ou underscore."},
        )

    login_timestamp = msg.get("timestamp", now_fn())
    state["logins"].append({"username": username, "login_timestamp": login_timestamp})
    save_state(state)
    print(f"[LOGIN] {username} fez login em {login_timestamp}", flush=True)
    return make_response("ok", lamport_clock, now_fn, {"message": f"Bem-vindo, {username}!"})


def handle_create_channel(msg, state, lamport_clock, now_fn):
    channel = msg.get("payload", {}).get("channel", "").strip()
    username = msg.get("payload", {}).get("username", "desconhecido")

    if not channel:
        return make_response("error", lamport_clock, now_fn, {"message": "Nome do canal nao informado."})
    if not CHANNEL_REGEX.match(channel):
        return make_response(
            "error",
            lamport_clock,
            now_fn,
            {"message": "Nome de canal invalido. Use 3-50 caracteres alfanumericos, underscore ou hifen."},
        )
    if channel in state["channels"]:
        return make_response("error", lamport_clock, now_fn, {"message": f"Canal {channel} ja existe."})

    state["channels"].append(channel)
    save_state(state)
    print(f"[CANAL] {username} criou o canal {channel}", flush=True)
    return make_response("ok", lamport_clock, now_fn, {"message": f"Canal {channel} criado com sucesso."})


def handle_list_channels(state, lamport_clock, now_fn):
    channels = state.get("channels", [])
    print(f"[LISTAR CANAIS] Enviando {len(channels)} canal(is).", flush=True)
    return make_response("ok", lamport_clock, now_fn, {"channels": channels})


def handle_publish_message(msg, state, pub_socket, lamport_clock, now_fn):
    payload = msg.get("payload", {})
    username = payload.get("username", "desconhecido")
    channel = str(payload.get("channel", "")).strip()
    content = str(payload.get("message", "")).strip()

    if not channel:
        return make_response("error", lamport_clock, now_fn, {"message": "Canal nao informado."})
    if channel not in state["channels"]:
        return make_response("error", lamport_clock, now_fn, {"message": f"Canal {channel} nao existe."})
    if not content:
        return make_response("error", lamport_clock, now_fn, {"message": "Mensagem vazia."})

    sent_timestamp = msg.get("timestamp", now_fn())
    published_timestamp = now_fn()

    publication_payload = {
        "channel": channel,
        "message": content,
        "username": username,
        "sent_timestamp": sent_timestamp,
        "published_timestamp": published_timestamp,
        "logical_clock": lamport_clock.tick(),
    }

    pub_socket.send_multipart(
        [
            channel.encode("utf-8"),
            msgpack.packb(publication_payload, use_bin_type=True),
        ]
    )

    state["publications"].append(publication_payload)
    save_state(state)

    print(
        f"[PUB] {username} publicou em {channel}: {content} "
        f"(sent={sent_timestamp}, pub={published_timestamp}, lc={publication_payload['logical_clock']})",
        flush=True,
    )
    return make_response("ok", lamport_clock, now_fn, {"message": "Publicacao enviada com sucesso."})


def main():
    server_name = os.getenv("SERVER_NAME") or pysocket.gethostname()
    state = load_state()

    lamport_clock = LamportClock()
    clock_offset = 0.0

    def now_synced():
        return time.time() + clock_offset

    context = zmq.Context()

    rep_socket = context.socket(zmq.REP)
    rep_socket.connect("tcp://broker:5556")

    pub_socket = context.socket(zmq.PUB)
    pub_socket.connect("tcp://pubsub-proxy:5557")

    ref_socket = context.socket(zmq.REQ)
    ref_socket.connect("tcp://reference:5559")

    def call_reference(req):
        ref_socket.send(msgpack.packb(req, use_bin_type=True))
        return msgpack.unpackb(ref_socket.recv(), raw=False)

    register_reply = call_reference({"type": "register", "name": server_name})
    server_rank = register_reply.get("rank", -1)
    nonlocal_offset = register_reply.get("reference_time", time.time()) - time.time()
    clock_offset = nonlocal_offset

    print(
        f"[SERVIDOR] {server_name} rank={server_rank}. "
        f"Estado: {len(state['logins'])} login(s), {len(state['channels'])} canal(is), {len(state['publications'])} pub(s).",
        flush=True,
    )

    messages_since_hb = 0

    while True:
        try:
            raw = rep_socket.recv()
            msg = msgpack.unpackb(raw, raw=False)
            lamport_clock.merge(msg.get("logical_clock", 0))
            msg_type = msg.get("type", "")
            print(f"[SERVIDOR] Mensagem recebida: type={msg_type}, lc={lamport_clock.value}", flush=True)

            if msg_type == "login":
                response = handle_login(msg, state, lamport_clock, now_synced)
            elif msg_type == "create_channel":
                response = handle_create_channel(msg, state, lamport_clock, now_synced)
            elif msg_type == "list_channels":
                response = handle_list_channels(state, lamport_clock, now_synced)
            elif msg_type == "publish_message":
                response = handle_publish_message(msg, state, pub_socket, lamport_clock, now_synced)
            else:
                response = make_response("error", lamport_clock, now_synced, {"message": f"Operacao desconhecida: {msg_type}"})

            rep_socket.send(msgpack.packb(response, use_bin_type=True))

            messages_since_hb += 1
            if messages_since_hb >= HEARTBEAT_EVERY_MESSAGES:
                hb_reply = call_reference({"type": "heartbeat", "name": server_name, "rank": server_rank})
                if hb_reply.get("status") == "ok":
                    clock_offset = hb_reply.get("reference_time", time.time()) - time.time()
                messages_since_hb = 0
        except Exception as exc:
            print(f"[SERVIDOR] Erro inesperado: {exc}", flush=True)
            rep_socket.send(
                msgpack.packb(
                    make_response("error", lamport_clock, now_synced, {"code": "SERVER_ERROR", "message": str(exc)}),
                    use_bin_type=True,
                )
            )


if __name__ == "__main__":
    main()
