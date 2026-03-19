import os
import re
import time
from pathlib import Path

import msgpack
import zmq

STATE_FILE = Path("state.msgpack")
USERNAME_REGEX = re.compile(r"^[a-zA-Z0-9_]{3,20}$")
CHANNEL_REGEX = re.compile(r"^[a-zA-Z0-9_-]{3,50}$")
DEFAULT_STATE = {"logins": [], "channels": []}


def load_state():
    if not STATE_FILE.exists() or STATE_FILE.stat().st_size == 0:
        return {"logins": [], "channels": []}

    try:
        with STATE_FILE.open("rb") as f:
            saved = msgpack.unpackb(f.read(), raw=False)

        if not isinstance(saved, dict):
            raise ValueError("estado persistido invalido")

        saved.setdefault("logins", [])
        saved.setdefault("channels", [])
        return saved
    except Exception as exc:
        print(f"[SERVIDOR] Aviso: arquivo de estado invalido ou corrompido ({exc}). Reiniciando estado.", flush=True)
        return {"logins": [], "channels": []}


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
        return make_response("error", {"message": "Username invalido. Use 3-20 caracteres alfanumericos ou underscore."})

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
        return make_response("error", {"message": "Nome de canal invalido. Use 3-50 caracteres alfanumericos, underscore ou hifen."})
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


def main():
    state = load_state()
    print(
        f"[SERVIDOR] Estado carregado: {len(state['logins'])} login(s), {len(state['channels'])} canal(is).",
        flush=True,
    )

    context = zmq.Context()
    socket = context.socket(zmq.REP)
    socket.connect("tcp://broker:5556")
    print("[SERVIDOR] Conectado ao broker na porta 5556. Aguardando mensagens...", flush=True)

    while True:
        try:
            raw = socket.recv()
            msg = msgpack.unpackb(raw, raw=False)
            msg_type = msg.get("type", "")
            print(f"[SERVIDOR] Mensagem recebida: type={msg_type}", flush=True)

            if msg_type == "login":
                response = handle_login(msg, state)
            elif msg_type == "create_channel":
                response = handle_create_channel(msg, state)
            elif msg_type == "list_channels":
                response = handle_list_channels(state)
            else:
                response = make_response("error", {"message": f"Operacao desconhecida: {msg_type}"})

            socket.send(msgpack.packb(response, use_bin_type=True))
        except Exception as exc:
            print(f"[SERVIDOR] Erro inesperado: {exc}", flush=True)
            socket.send(
                msgpack.packb(
                    make_response("error", {"code": "SERVER_ERROR", "message": str(exc)}),
                    use_bin_type=True,
                )
            )


if __name__ == "__main__":
    main()
