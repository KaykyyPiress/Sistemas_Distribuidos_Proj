import os
import re
import time
from pathlib import Path

import msgpack
import zmq

STATE_FILE = Path("state.msgpack")
USERNAME_REGEX = re.compile(r"^[a-zA-Z0-9_]{3,20}$")
CHANNEL_REGEX = re.compile(r"^[a-zA-Z0-9_-]{3,50}$")
DEFAULT_STATE = {"logins": [], "channels": [], "publications": []}


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
        print(
            f"[SERVIDOR] Aviso: arquivo de estado invalido ou corrompido ({exc}). Reiniciando estado.",
            flush=True,
        )
        return DEFAULT_STATE.copy()


def save_state(state):
    tmp_file = STATE_FILE.with_suffix(".tmp")
    payload = msgpack.packb(state, use_bin_type=True)

    with tmp_file.open("wb") as f:
        f.write(payload)
        f.flush()
        os.fsync(f.fileno())

    tmp_file.replace(STATE_FILE)


def make_response(status, payload=None):
    return {"status": status, "timestamp": time.time(), "payload": payload or {}}


def handle_login(msg, state):
    username = msg.get("payload", {}).get("username", "").strip()
    if not username:
        return make_response("error", {"message": "Username nao informado."})
    if not USERNAME_REGEX.match(username):
        return make_response(
            "error",
            {
                "message": "Username invalido. Use 3-20 caracteres alfanumericos ou underscore.",
            },
        )

    login_timestamp = msg.get("timestamp", time.time())
    state["logins"].append({"username": username, "login_timestamp": login_timestamp})
    save_state(state)
    print(f"[LOGIN] {username} fez login em {login_timestamp}", flush=True)
    return make_response("ok", {"message": f"Bem-vindo, {username}!"})


def handle_create_channel(msg, state):
    channel = msg.get("payload", {}).get("channel", "").strip()
    username = msg.get("payload", {}).get("username", "desconhecido")

    if not channel:
        return make_response("error", {"message": "Nome do canal nao informado."})
    if not CHANNEL_REGEX.match(channel):
        return make_response(
            "error",
            {
                "message": "Nome de canal invalido. Use 3-50 caracteres alfanumericos, underscore ou hifen.",
            },
        )
    if channel in state["channels"]:
        return make_response("error", {"message": f"Canal {channel} ja existe."})

    state["channels"].append(channel)
    save_state(state)
    print(f"[CANAL] {username} criou o canal {channel}", flush=True)
    return make_response("ok", {"message": f"Canal {channel} criado com sucesso."})


def handle_list_channels(state):
    channels = state.get("channels", [])
    print(f"[LISTAR CANAIS] Enviando {len(channels)} canal(is).", flush=True)
    return make_response("ok", {"channels": channels})


def handle_publish_message(msg, state, pub_socket):
    payload = msg.get("payload", {})
    username = payload.get("username", "desconhecido")
    channel = str(payload.get("channel", "")).strip()
    content = str(payload.get("message", "")).strip()

    if not channel:
        return make_response("error", {"message": "Canal nao informado."})
    if channel not in state["channels"]:
        return make_response("error", {"message": f"Canal {channel} nao existe."})
    if not content:
        return make_response("error", {"message": "Mensagem vazia."})

    sent_timestamp = msg.get("timestamp", time.time())
    published_timestamp = time.time()

    publication_payload = {
        "channel": channel,
        "message": content,
        "username": username,
        "sent_timestamp": sent_timestamp,
        "published_timestamp": published_timestamp,
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
        f"[PUB] {username} publicou em {channel}: {content} (sent={sent_timestamp}, pub={published_timestamp})",
        flush=True,
    )
    return make_response("ok", {"message": "Publicacao enviada com sucesso."})


def main():
    state = load_state()
    print(
        "[SERVIDOR] Estado carregado: "
        f"{len(state['logins'])} login(s), "
        f"{len(state['channels'])} canal(is), "
        f"{len(state['publications'])} publicacao(oes).",
        flush=True,
    )

    context = zmq.Context()

    rep_socket = context.socket(zmq.REP)
    rep_socket.connect("tcp://broker:5556")

    pub_socket = context.socket(zmq.PUB)
    pub_socket.connect("tcp://pubsub-proxy:5557")

    print("[SERVIDOR] Conectado ao broker (5556) e ao proxy Pub/Sub (5557).", flush=True)

    while True:
        try:
            raw = rep_socket.recv()
            msg = msgpack.unpackb(raw, raw=False)
            msg_type = msg.get("type", "")
            print(f"[SERVIDOR] Mensagem recebida: type={msg_type}", flush=True)

            if msg_type == "login":
                response = handle_login(msg, state)
            elif msg_type == "create_channel":
                response = handle_create_channel(msg, state)
            elif msg_type == "list_channels":
                response = handle_list_channels(state)
            elif msg_type == "publish_message":
                response = handle_publish_message(msg, state, pub_socket)
            else:
                response = make_response("error", {"message": f"Operacao desconhecida: {msg_type}"})

            rep_socket.send(msgpack.packb(response, use_bin_type=True))
        except Exception as exc:
            print(f"[SERVIDOR] Erro inesperado: {exc}", flush=True)
            rep_socket.send(
                msgpack.packb(
                    make_response("error", {"code": "SERVER_ERROR", "message": str(exc)}),
                    use_bin_type=True,
                )
            )


if __name__ == "__main__":
    main()
