import time

import msgpack
import zmq

HEARTBEAT_TTL_SECONDS = 60


def prune_servers(servers):
    now = time.time()
    stale = [name for name, info in servers.items() if now - info["last_seen"] > HEARTBEAT_TTL_SECONDS]
    for name in stale:
        del servers[name]


def main():
    context = zmq.Context()
    socket = context.socket(zmq.REP)
    socket.bind("tcp://*:5559")

    servers = {}
    next_rank = 1

    print("[REFERENCE] Serviço de referência iniciado na porta 5559.", flush=True)

    while True:
        try:
            raw = socket.recv()
            msg = msgpack.unpackb(raw, raw=False)
            msg_type = msg.get("type", "")

            prune_servers(servers)

            if msg_type == "register":
                name = str(msg.get("name", "")).strip()
                if not name:
                    response = {"status": "error", "message": "name obrigatório"}
                else:
                    if name not in servers:
                        assigned_rank = next_rank
                        next_rank += 1
                        servers[name] = {"rank": assigned_rank, "last_seen": time.time()}
                    else:
                        servers[name]["last_seen"] = time.time()
                    response = {
                        "status": "ok",
                        "rank": servers[name]["rank"],
                        "reference_time": time.time(),
                    }
            elif msg_type == "list":
                response = {
                    "status": "ok",
                    "servers": [
                        {"name": name, "rank": info["rank"]}
                        for name, info in sorted(servers.items(), key=lambda item: item[1]["rank"])
                    ],
                    "reference_time": time.time(),
                }
            elif msg_type == "heartbeat":
                name = str(msg.get("name", "")).strip()
                if not name:
                    response = {"status": "error", "message": "name obrigatório"}
                else:
                    if name not in servers:
                        rank = int(msg.get("rank", next_rank))
                        servers[name] = {"rank": rank, "last_seen": time.time()}
                        next_rank = max(next_rank, rank + 1)
                    else:
                        servers[name]["last_seen"] = time.time()
                    response = {
                        "status": "ok",
                        "rank": servers[name]["rank"],
                        "reference_time": time.time(),
                    }
            else:
                response = {"status": "error", "message": f"operação desconhecida: {msg_type}"}

            socket.send(msgpack.packb(response, use_bin_type=True))
        except Exception as exc:
            socket.send(
                msgpack.packb(
                    {"status": "error", "message": str(exc), "reference_time": time.time()},
                    use_bin_type=True,
                )
            )


if __name__ == "__main__":
    main()
