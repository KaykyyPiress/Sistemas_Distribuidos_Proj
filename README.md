# Projeto de Sistemas Distribuídos – Parte 3

## Objetivo

Nesta etapa foram adicionados:

1. **Relógio lógico (Lamport)** em clientes/bots e servidores
2. **Serviço de referência** para:
   - atribuição de rank aos servidores
   - manutenção da lista de servidores ativos
   - atualização por heartbeat
   - sincronização de relógio físico dos servidores

---

## Arquitetura

### Plano de controle (Req/Rep)
- Cliente ↔ Broker (`5555/5556`) ↔ Servidor
- Servidor ↔ Referência (`5559`)

### Plano de dados (Pub/Sub)
- Servidor (PUB) → Proxy Pub/Sub (`5557/5558`) → Clientes (SUB)

---

## Portas

- Broker frontend: `5555`
- Broker backend: `5556`
- Pub/Sub XSUB: `5557`
- Pub/Sub XPUB: `5558`
- Referência: `5559`

---

## Relógio lógico

Clientes/bots e servidores mantêm um contador lógico.

Regras aplicadas:

1. antes de **enviar** mensagem: incrementa contador e envia no campo `logical_clock`
2. ao **receber** mensagem: atualiza o contador para `max(local, recebido)`

Todas as mensagens seguem com:

- `timestamp` (relógio físico)
- `logical_clock` (relógio lógico)

---

## Serviço de referência (Parte 3)

Novo processo `reference.py` responsável por:

- `register`: cadastrar servidor e devolver `rank`
- `list`: devolver lista `{name, rank}` dos servidores ativos
- `heartbeat`: atualizar disponibilidade do servidor e devolver `reference_time`

Remoção de servidores inativos é feita por timeout de heartbeat.

---

## Sincronização de relógio físico

Cada servidor, ao registrar e a cada heartbeat, recebe `reference_time`.

Com isso calcula um `offset` local:

`offset = reference_time - local_time`

e passa a usar `local_time + offset` como timestamp físico sincronizado.

---

## Heartbeat

Cada servidor envia heartbeat ao serviço de referência **a cada 10 mensagens de clientes processadas**.

Esse heartbeat:

- mantém o servidor na lista de ativos
- atualiza o relógio físico via `reference_time`

---

## Execução

Na pasta `request-reply2/src/broker`:

```bash
docker compose up --build
```

Serviços iniciados:

- `broker`
- `pubsub-proxy`
- `reference`
- `servidor` (Python)
- `cliente` (Python)
- `servidor-java`
- `cliente-java`
