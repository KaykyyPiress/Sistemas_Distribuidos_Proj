import zmq
import msgpack
import time

BROKER_URL = "tcp://broker:5555"
USERNAME = "bot_01"
CANAL_PADRAO = "geral"


def send_request(socket, message):
    socket.send(msgpack.packb(message, use_bin_type=True))
    raw_reply = socket.recv()
    return msgpack.unpackb(raw_reply, raw=False)


def fazer_login(socket, username):
    """Tenta fazer login. Repete em caso de erro."""
    while True:
        login_request = {
            "type": "login",
            "timestamp": time.time(),
            "payload": {
                "username": username
            }
        }
        reply = send_request(socket, login_request)
        print(f"[LOGIN] Resposta do servidor: {reply}")

        if reply.get("status") == "ok":
            print(f"[LOGIN] Login bem-sucedido como {username!r}.")
            return True
        else:
            erro = reply.get("payload", {}).get("message", "erro desconhecido")
            print(f"[LOGIN] Falha no login: {erro}. Tentando novamente em 3s...")
            time.sleep(3)


def listar_canais(socket):
    """Solicita a lista de canais disponiveis ao servidor."""
    list_request = {
        "type": "list_channels",
        "timestamp": time.time(),
        "payload": {}
    }
    reply = send_request(socket, list_request)
    print(f"[LISTAR CANAIS] Resposta do servidor: {reply}")

    if reply.get("status") == "ok":
        canais = reply.get("payload", {}).get("channels", [])
        print(f"[LISTAR CANAIS] Canais disponiveis: {canais}")
        return canais
    else:
        erro = reply.get("payload", {}).get("message", "erro desconhecido")
        print(f"[LISTAR CANAIS] Erro ao listar canais: {erro}")
        return []


def criar_canal(socket, username, nome_canal):
    """Solicita a criacao de um canal ao servidor."""
    create_request = {
        "type": "create_channel",
        "timestamp": time.time(),
        "payload": {
            "username": username,
            "channel": nome_canal
        }
    }
    reply = send_request(socket, create_request)
    print(f"[CRIAR CANAL] Resposta do servidor: {reply}")

    if reply.get("status") == "ok":
        print(f"[CRIAR CANAL] Canal {nome_canal!r} criado com sucesso.")
    else:
        erro = reply.get("payload", {}).get("message", "erro desconhecido")
        print(f"[CRIAR CANAL] Nao foi possivel criar o canal {nome_canal!r}: {erro}")


def main():
    context = zmq.Context()
    socket = context.socket(zmq.REQ)
    socket.connect(BROKER_URL)
    print(f"[CLIENTE] Conectado ao broker em {BROKER_URL}")

    # 1. Login (com retry automatico em caso de erro)
    fazer_login(socket, USERNAME)

    # 2. Listar canais disponiveis
    canais = listar_canais(socket)

    # 3. Criar canal "geral" caso ele nao exista
    if CANAL_PADRAO not in canais:
        criar_canal(socket, USERNAME, CANAL_PADRAO)
    else:
        print(f"[CLIENTE] Canal {CANAL_PADRAO!r} ja existe, pulando criacao.")

    # 4. Listar canais novamente para confirmar
    listar_canais(socket)

    socket.close()
    context.term()
    print("[CLIENTE] Encerrando.")


if __name__ == "__main__":
    main()
