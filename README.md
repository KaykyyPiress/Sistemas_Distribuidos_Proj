# Projeto de Sistemas Distribuídos – Parte 2

## Introdução

Este projeto implementa um sistema distribuído com dois padrões de comunicação:

- **Request-Reply** para operações de controle (login, criação de canal, listagem e requisição de publicação)
- **Publisher-Subscriber** para distribuição das mensagens publicadas nos canais

A arquitetura contém cinco componentes:

- **Cliente**: realiza requisições ao servidor via broker e se inscreve em canais no proxy Pub/Sub
- **Broker Req/Rep**: encaminha requisições entre clientes e servidores
- **Servidor**: processa requisições, persiste estado e publica mensagens
- **Proxy Pub/Sub**: intermedia publicadores e assinantes
- **Clientes/Servidores Java**: mantidos para interoperabilidade da parte 1

---

## Portas utilizadas

- **Broker (frontend - clientes)**: `tcp://localhost:5555`
- **Broker (backend - servidores)**: `tcp://localhost:5556`
- **Pub/Sub Proxy (XSUB - publicadores)**: `tcp://localhost:5557`
- **Pub/Sub Proxy (XPUB - assinantes)**: `tcp://localhost:5558`

---

## Fluxo de mensagens

### Operações de controle (Req/Rep)

Cliente → Broker → Servidor

- `login`
- `create_channel`
- `list_channels`
- `publish_message` (requisição para publicar em um canal)

Servidor → Broker → Cliente

- resposta `ok`/`error` para cada operação

### Publicação de mensagens (Pub/Sub)

1. Cliente envia `publish_message` ao servidor (Req/Rep)
2. Servidor publica no tópico/canal correspondente (Pub/Sub)
3. Clientes inscritos recebem a mensagem no canal

Toda mensagem possui timestamp de envio; no cliente assinante também é exibido o timestamp de recebimento.

---

## Serialização

A serialização é feita com **MessagePack** em Python e Java.

Formato geral das mensagens Req/Rep:

- `type`
- `timestamp`
- `payload`

Formato da publicação no Pub/Sub:

- `channel`
- `message`
- `username`
- `sent_timestamp`
- `published_timestamp`

---

## Persistência

O servidor Python mantém estado local em `state.msgpack` com:

- `logins`
- `channels`
- `publications`

Cada publicação gravada contém usuário, canal, conteúdo e timestamps.

A escrita em disco usa arquivo temporário + `fsync` + `replace` para reduzir risco de corrupção.

---

## Funcionamento do bot Python (parte 2)

Ao iniciar, o bot:

1. Faz login
2. Lista canais
3. Se houver menos de 5 canais, cria um novo
4. Se estiver inscrito em menos de 3 canais, se inscreve em mais um canal
5. Entra em loop infinito:
   - escolhe um canal aleatório
   - envia 10 mensagens aleatórias
   - aguarda 1 segundo entre mensagens

Em paralelo, o bot mantém um assinante ativo exibindo para cada mensagem recebida:

- canal
- mensagem
- timestamp de envio
- timestamp de recebimento

---

## Execução

Na pasta `request-reply2/src/broker`:

```bash
docker compose up --build
```

Esse comando inicializa:

- `broker`
- `pubsub-proxy`
- `servidor` (Python)
- `cliente` (Python)
- `servidor-java`
- `cliente-java`

---

## Observação sobre consistência

Como cada servidor mantém estado local independente, múltiplos servidores sem replicação podem apresentar visões diferentes de canais/publicações.
