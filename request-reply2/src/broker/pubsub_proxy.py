import zmq


def main():
    context = zmq.Context()

    xsub = context.socket(zmq.XSUB)
    xsub.bind("tcp://*:5557")

    xpub = context.socket(zmq.XPUB)
    xpub.bind("tcp://*:5558")

    print("[PUBSUB-PROXY] Proxy iniciado (XSUB=5557, XPUB=5558).", flush=True)

    try:
        zmq.proxy(xsub, xpub)
    except KeyboardInterrupt:
        print("[PUBSUB-PROXY] Encerrando proxy.", flush=True)
    finally:
        xsub.close(0)
        xpub.close(0)
        context.term()


if __name__ == "__main__":
    main()
