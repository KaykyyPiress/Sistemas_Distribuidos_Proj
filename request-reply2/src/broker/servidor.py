import os
import re
import socket as pysocket
import threading
import time
from pathlib import Path

import msgpack
import zmq

STATE_FILE = Path("state.msgpack")
USERNAME_REGEX = re.compile(r"^[a-zA-Z0-9_]{3,20}$")
CHANNEL_REGEX = re.compile(r"^[a-zA-Z0-9_-]{3,50}$")
DEFAULT_STATE = {"logins": [], "channels": [], "publications": []}
SYNC_EVERY_MESSAGES = 15
INTERNAL_PORT = 5560


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
        saved.setdefault("logins", [])
        saved.setdefault("channels", [])
        saved.setdefault("publications", [])
        return saved if isinstance(saved, dict) else DEFAULT_STATE.copy()
    except Exception:
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
    return {"status": status, "timestamp": now_fn(), "logical_clock": lamport_clock.tick(), "payload": payload or {}}


def handle_login(msg, state, lamport_clock, now_fn):
    username = msg.get("payload", {}).get("username", "").strip()
    if not USERNAME_REGEX.match(username):
        return make_response("error", lamport_clock, now_fn, {"message": "Username invalido."})
    ts = msg.get("timestamp", now_fn())
    state["logins"].append({"username": username, "login_timestamp": ts})
    save_state(state)
    print(f"[LOGIN] {username} fez login em {ts}", flush=True)
    return make_response("ok", lamport_clock, now_fn, {"message": f"Bem-vindo, {username}!"})


def handle_create_channel(msg, state, lamport_clock, now_fn):
    payload = msg.get("payload", {})
    channel = payload.get("channel", "").strip()
    username = payload.get("username", "desconhecido")
    if not CHANNEL_REGEX.match(channel):
        return make_response("error", lamport_clock, now_fn, {"message": "Nome de canal invalido."})
    if channel in state["channels"]:
        return make_response("error", lamport_clock, now_fn, {"message": f"Canal {channel} ja existe."})
    state["channels"].append(channel)
    save_state(state)
    print(f"[CANAL] {username} criou o canal {channel}", flush=True)
    return make_response("ok", lamport_clock, now_fn, {"message": f"Canal {channel} criado com sucesso."})


def handle_list_channels(state, lamport_clock, now_fn):
    return make_response("ok", lamport_clock, now_fn, {"channels": state.get("channels", [])})


def handle_publish_message(msg, state, pub_socket, lamport_clock, now_fn):
    payload = msg.get("payload", {})
    channel = str(payload.get("channel", "")).strip()
    content = str(payload.get("message", "")).strip()
    username = payload.get("username", "desconhecido")
    if channel not in state["channels"]:
        return make_response("error", lamport_clock, now_fn, {"message": f"Canal {channel} nao existe."})
    if not content:
        return make_response("error", lamport_clock, now_fn, {"message": "Mensagem vazia."})
    publication = {
        "channel": channel,
        "message": content,
        "username": username,
        "sent_timestamp": msg.get("timestamp", now_fn()),
        "published_timestamp": now_fn(),
        "logical_clock": lamport_clock.tick(),
    }
    pub_socket.send_multipart([channel.encode(), msgpack.packb(publication, use_bin_type=True)])
    state["publications"].append(publication)
    save_state(state)
    print(f"[PUB] {username} publicou em {channel}: {content}", flush=True)
    return make_response("ok", lamport_clock, now_fn, {"message": "Publicacao enviada com sucesso."})


def main():
    server_name = os.getenv("SERVER_NAME") or pysocket.gethostname()
    state = load_state()
    lamport_clock = LamportClock()
    clock_offset = 0.0
    coordinator_name = None
    server_rank = -1

    def now_synced():
        return time.time() + clock_offset

    ctx = zmq.Context()
    rep = ctx.socket(zmq.REP)
    rep.connect("tcp://broker:5556")
    pub = ctx.socket(zmq.PUB)
    pub.connect("tcp://pubsub-proxy:5557")
    ref = ctx.socket(zmq.REQ)
    ref.connect("tcp://reference:5559")

    internal_rep = ctx.socket(zmq.REP)
    internal_rep.bind(f"tcp://*:{INTERNAL_PORT}")

    def ref_call(req):
        ref.send(msgpack.packb(req, use_bin_type=True))
        return msgpack.unpackb(ref.recv(), raw=False)

    def get_servers():
        reply = ref_call({"type": "list"})
        return reply.get("servers", []) if reply.get("status") == "ok" else []

    def announce_coordinator(name):
        payload = {"coordinator": name, "timestamp": now_synced(), "logical_clock": lamport_clock.tick()}
        pub.send_multipart([b"servers", msgpack.packb(payload, use_bin_type=True)])

    def elect_coordinator():
        nonlocal coordinator_name
        servers = get_servers()
        if not servers:
            coordinator_name = server_name
            return
        best = min(servers, key=lambda s: int(s.get("rank", 1_000_000)))
        candidate = best.get("name", server_name)
        if candidate == server_name:
            coordinator_name = server_name
            announce_coordinator(server_name)
            return
        req = ctx.socket(zmq.REQ)
        req.setsockopt(zmq.RCVTIMEO, 700)
        req.setsockopt(zmq.SNDTIMEO, 700)
        req.connect(f"tcp://{candidate}:{INTERNAL_PORT}")
        try:
            req.send(msgpack.packb({"type": "election", "from": server_name}, use_bin_type=True))
            repm = msgpack.unpackb(req.recv(), raw=False)
            if repm.get("status") == "ok":
                coordinator_name = candidate
                return
        except Exception:
            coordinator_name = server_name
            announce_coordinator(server_name)
        finally:
            req.close(0)

    def sync_with_coordinator():
        nonlocal clock_offset
        if coordinator_name == server_name:
            return
        req = ctx.socket(zmq.REQ)
        req.setsockopt(zmq.RCVTIMEO, 700)
        req.setsockopt(zmq.SNDTIMEO, 700)
        req.connect(f"tcp://{coordinator_name}:{INTERNAL_PORT}")
        try:
            req.send(msgpack.packb({"type": "clock", "from": server_name}, use_bin_type=True))
            reply = msgpack.unpackb(req.recv(), raw=False)
            if reply.get("status") == "ok":
                clock_offset = float(reply.get("coordinator_time", time.time())) - time.time()
        except Exception:
            elect_coordinator()
        finally:
            req.close(0)

    def internal_loop():
        while True:
            msg = msgpack.unpackb(internal_rep.recv(), raw=False)
            t = msg.get("type")
            if t == "clock":
                internal_rep.send(msgpack.packb({"status": "ok", "coordinator_time": now_synced()}, use_bin_type=True))
            elif t == "election":
                internal_rep.send(msgpack.packb({"status": "ok", "server": server_name}, use_bin_type=True))
            else:
                internal_rep.send(msgpack.packb({"status": "error"}, use_bin_type=True))

    threading.Thread(target=internal_loop, daemon=True).start()

    reg = ref_call({"type": "register", "name": server_name})
    server_rank = int(reg.get("rank", -1))
    elect_coordinator()
    print(f"[SERVIDOR] {server_name} rank={server_rank} coordinator={coordinator_name}", flush=True)

    msg_count = 0
    while True:
        raw = rep.recv()
        msg = msgpack.unpackb(raw, raw=False)
        lamport_clock.merge(msg.get("logical_clock", 0))
        mt = msg.get("type", "")
        if mt == "login":
            resp = handle_login(msg, state, lamport_clock, now_synced)
        elif mt == "create_channel":
            resp = handle_create_channel(msg, state, lamport_clock, now_synced)
        elif mt == "list_channels":
            resp = handle_list_channels(state, lamport_clock, now_synced)
        elif mt == "publish_message":
            resp = handle_publish_message(msg, state, pub, lamport_clock, now_synced)
        else:
            resp = make_response("error", lamport_clock, now_synced, {"message": f"Operacao desconhecida: {mt}"})
        rep.send(msgpack.packb(resp, use_bin_type=True))
        msg_count += 1
        if msg_count % SYNC_EVERY_MESSAGES == 0:
            ref_call({"type": "heartbeat", "name": server_name, "rank": server_rank})
            sync_with_coordinator()


if __name__ == "__main__":
    main()
