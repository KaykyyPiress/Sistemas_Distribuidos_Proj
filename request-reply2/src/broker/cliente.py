import random
import string
import threading
import time

import msgpack
import zmq

BROKER_URL = "tcp://broker:5555"
SUB_URL = "tcp://pubsub-proxy:5558"
USERNAME = "bot_01"
MIN_CHANNELS = 5
MIN_SUBSCRIPTIONS = 3


def send_request(socket, message):
    socket.send(msgpack.packb(message, use_bin_type=True))
    raw_reply = socket.recv()
    return msgpack.unpackb(raw_reply, raw=False)


def build_request(message_type, payload):
    return {
        "type": message_type,
        "timestamp": time.time(),
        "payload": payload,
    }


def fazer_login(socket, username):
    while True:
        reply = send_request(socket, build_request("login", {"username": username}))
        print(f"[LOGIN] Resposta do servidor: {reply}")

        if reply.get("status") == "ok":
            print(f"[LOGIN] Login bem-sucedido como {username!r}.")
            return True

        erro = reply.get("payload", {}).get("message", "erro desconhecido")
        print(f"[LOGIN] Falha no login: {erro}. Tentando novamente em 3s...")
        time.sleep(3)


def listar_canais(socket):
    reply = send_request(socket, build_request("list_channels", {}))
    print(f"[LISTAR CANAIS] Resposta do servidor: {reply}")

    if reply.get("status") == "ok":
        canais = reply.get("payload", {}).get("channels", [])
        print(f"[LISTAR CANAIS] Canais disponíveis: {canais}")
        return canais

    erro = reply.get("payload", {}).get("message", "erro desconhecido")
    print(f"[LISTAR CANAIS] Erro ao listar canais: {erro}")
    return []


def criar_canal(socket, username, nome_canal):
    payload = {"username": username, "channel": nome_canal}
    reply = send_request(socket, build_request("create_channel", payload))
    print(f"[CRIAR CANAL] Resposta do servidor: {reply}")

    if reply.get("status") == "ok":
        print(f"[CRIAR CANAL] Canal {nome_canal!r} criado com sucesso.")
        return True

    erro = reply.get("payload", {}).get("message", "erro desconhecido")
    print(f"[CRIAR CANAL] Não foi possível criar o canal {nome_canal!r}: {erro}")
    return False


def publicar(socket, username, canal, mensagem):
    payload = {
        "username": username,
        "channel": canal,
        "message": mensagem,
    }
    reply = send_request(socket, build_request("publish_message", payload))
    status = reply.get("status")
    if status != "ok":
        erro = reply.get("payload", {}).get("message", "erro desconhecido")
        print(f"[PUB-REQ] Falha ao publicar em {canal!r}: {erro}")
    else:
        print(f"[PUB-REQ] Publicação confirmada em {canal!r}.")


def generate_channel_name(username):
    suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=5))
    return f"{username}_{suffix}"


def generate_message():
    suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=8))
    return f"msg_{suffix}"


def listen_subscriptions(sub_socket, stop_event):
    poller = zmq.Poller()
    poller.register(sub_socket, zmq.POLLIN)

    while not stop_event.is_set():
        events = dict(poller.poll(timeout=200))
        if sub_socket not in events:
            continue

        topic, raw_payload = sub_socket.recv_multipart()
        received_timestamp = time.time()
        payload = msgpack.unpackb(raw_payload, raw=False)

        channel = payload.get("channel", topic.decode("utf-8", errors="replace"))
        message = payload.get("message", "")
        sent_timestamp = payload.get("sent_timestamp", "?")

        print(
            "[SUB] "
            f"canal={channel} | mensagem={message} | "
            f"ts_envio={sent_timestamp} | ts_recebimento={received_timestamp}",
            flush=True,
        )


def main():
    context = zmq.Context()

    req_socket = context.socket(zmq.REQ)
    req_socket.connect(BROKER_URL)
    print(f"[CLIENTE] Conectado ao broker em {BROKER_URL}")

    sub_socket = context.socket(zmq.SUB)
    sub_socket.connect(SUB_URL)
    print(f"[CLIENTE] Conectado ao proxy Pub/Sub em {SUB_URL}")

    stop_event = threading.Event()
    listener = threading.Thread(target=listen_subscriptions, args=(sub_socket, stop_event), daemon=True)
    listener.start()

    subscribed_channels = set()

    try:
        fazer_login(req_socket, USERNAME)

        canais = listar_canais(req_socket)
        if len(canais) < MIN_CHANNELS:
            novo_canal = generate_channel_name(USERNAME)
            if criar_canal(req_socket, USERNAME, novo_canal):
                canais = listar_canais(req_socket)

        if canais and len(subscribed_channels) < MIN_SUBSCRIPTIONS:
            candidatos = [c for c in canais if c not in subscribed_channels]
            if candidatos:
                canal = random.choice(candidatos)
                sub_socket.setsockopt_string(zmq.SUBSCRIBE, canal)
                subscribed_channels.add(canal)
                print(f"[SUB] Inscrito no canal {canal!r}")

        while True:
            canais = listar_canais(req_socket)
            if not canais:
                print("[CLIENTE] Nenhum canal disponível. Tentando novamente em 2s...")
                time.sleep(2)
                continue

            if len(subscribed_channels) < MIN_SUBSCRIPTIONS:
                candidatos = [c for c in canais if c not in subscribed_channels]
                if candidatos:
                    canal = random.choice(candidatos)
                    sub_socket.setsockopt_string(zmq.SUBSCRIBE, canal)
                    subscribed_channels.add(canal)
                    print(f"[SUB] Inscrito no canal {canal!r}")

            for _ in range(10):
                canal = random.choice(canais)
                mensagem = generate_message()
                publicar(req_socket, USERNAME, canal, mensagem)
                time.sleep(1)

    except KeyboardInterrupt:
        print("[CLIENTE] Encerrando por interrupção do usuário.")
    finally:
        stop_event.set()
        listener.join(timeout=1)
        req_socket.close(0)
        sub_socket.close(0)
        context.term()
        print("[CLIENTE] Encerrado.")


if __name__ == "__main__":
    main()
