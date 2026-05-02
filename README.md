# Projeto de Sistemas Distribuídos – Parte 4
<!-- Arquivo reescrito para estabilizar merge da Parte 4 -->

## Objetivo

Nesta etapa foram consolidados:

1. **Relógio lógico (Lamport)** em clientes/bots e servidores
2. **Serviço de referência** para:
   - atribuição de rank aos servidores
   - manutenção da lista de servidores ativos
   - atualização por heartbeat
3. **Eleição de coordenador** entre servidores (menor rank)
4. **Sincronização de estado de canais** entre servidores Python e Java

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

## Serviço de referência (Parte 4)

Novo processo `reference.py` responsável por:

- `register`: cadastrar servidor e devolver `rank`
- `list`: devolver lista `{name, rank}` dos servidores ativos
- `heartbeat`: atualizar disponibilidade do servidor e devolver `rank`

Remoção de servidores inativos é feita por timeout de heartbeat.

---

## Relógio físico

Nesta parte, os servidores usam o relógio local (`time.time()` no Python e
`System.currentTimeMillis()/1000.0` no Java) para timestamps físicos.

---

## Heartbeat e refresh do coordenador

Cada servidor envia heartbeat ao serviço de referência a cada requisição processada.
Além disso, a cada **15 mensagens processadas** (`SYNC_EVERY_MESSAGES`), consulta
`list` no serviço de referência para atualizar a eleição de coordenador.

O heartbeat mantém o servidor na lista de ativos.

## Eleição de coordenador

A eleição é feita com base no menor `rank` entre os servidores ativos retornados
por `reference:list`.

## Sincronização de canais entre servidores

Como o broker distribui requisições entre múltiplos servidores, o estado de canais
é sincronizado por Pub/Sub no tópico `servers.state`.

- Ao criar canal com sucesso, o servidor publica evento `channel_created`.
- Os demais servidores consomem esse evento e atualizam seu estado local.
- Periodicamente, os servidores também publicam `channels_snapshot` para reconciliar
  diferenças acumuladas (ex.: restart, atraso de subscribe, perda de evento).

Isso evita falhas intermitentes de publicação do tipo `Canal ... nao existe`
quando `create_channel` e `publish_message` caem em instâncias diferentes.

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
