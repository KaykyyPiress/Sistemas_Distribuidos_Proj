import zmq


def main():
    context = zmq.Context()

    frontend = context.socket(zmq.ROUTER)
    frontend.bind("tcp://*:5555")

    backend = context.socket(zmq.DEALER)
    backend.bind("tcp://*:5556")

    print("[BROKER] Broker iniciado. Encaminhando mensagens entre clientes e servidores.", flush=True)

    try:
        zmq.proxy(frontend, backend)
    except KeyboardInterrupt:
        print("[BROKER] Encerrando broker.", flush=True)
    finally:
        frontend.close(0)
        backend.close(0)
        context.term()


if __name__ == "__main__":
    main()
