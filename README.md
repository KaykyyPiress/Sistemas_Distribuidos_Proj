# Projeto de Sistemas Distribuídos — Chat Distribuído em Python e Java

## Integrantes

- Kayky Pires
- Rafael Dias
---

## Resumo do projeto

Este projeto implementa um **chat distribuído** executado com Docker Compose. A aplicação possui clientes e servidores em **duas linguagens** — Python e Java — comunicando-se por ZeroMQ.

O sistema foi evoluído em etapas para incluir:

- comunicação cliente-servidor via padrão **Request/Reply**;
- publicação e assinatura de mensagens via **Pub/Sub**;
- bots clientes em Python e Java;
- servidores em Python e Java;
- relógio lógico de Lamport;
- serviço de referência para rank, heartbeat e descoberta de servidores;
- eleição de coordenador;
- sincronização e replicação de estado entre servidores;
- encaminhamento de escritas ao coordenador, seguindo a ideia de permissão centralizada.

---

## Arquitetura geral

### Plano de controle — Request/Reply

Usado para operações diretas dos clientes:

- login;
- criação de canal;
- listagem de canais;
- publicação de mensagem.

Fluxo principal:

```text
Cliente Python/Java -> Broker -> Servidor Python/Java
```

O broker distribui as requisições entre os servidores disponíveis.

### Plano de dados — Pub/Sub

Usado para entrega das mensagens publicadas nos canais:

```text
Servidor -> Pub/Sub Proxy -> Clientes inscritos
```

Quando uma mensagem é publicada em um canal, todos os clientes inscritos naquele canal recebem a publicação.

### Plano de coordenação entre servidores

Usado para manter os servidores consistentes e coordenados:

```text
Servidor Python <-> Servidor Java
Servidor <-> Reference
Servidor -> servers.state -> Servidores
```

O tópico `servers.state` é usado para sincronização de estado entre servidores.

---

## Serviços do Docker Compose

Na pasta `request-reply2/src/broker`, o `docker-compose.yml` inicia:

- `broker`: encaminha requisições REQ/REP entre clientes e servidores;
- `pubsub-proxy`: proxy XSUB/XPUB para mensagens Pub/Sub;
- `reference`: serviço de referência para rank, heartbeat e descoberta de peers;
- `servidor`: servidor Python;
- `cliente`: cliente/bot Python;
- `servidor-java`: servidor Java;
- `cliente-java`: cliente/bot Java.

---

## Como inicializar o projeto

### 1. Entrar na pasta do Compose

```bash
cd request-reply2/src/broker
```

### 2. Subir todos os serviços

```bash
docker compose up --build
```

Esse comando compila as imagens, sobe broker, proxy Pub/Sub, serviço de referência, servidores e clientes.

### 3. Ver containers em execução

Em outro terminal:

```bash
docker compose ps
```

### 4. Acompanhar logs

```bash
docker compose logs -f
```

Para acompanhar apenas servidores e referência:

```bash
docker compose logs -f servidor servidor-java reference
```

### 5. Parar o ambiente

```bash
docker compose down
```

Para limpar volumes persistidos durante testes:

```bash
docker compose down -v
```

---

## Resumo de cada parte do projeto

### Parte 1 — Comunicação cliente-servidor

A primeira etapa estabelece a comunicação básica entre cliente e servidor usando o padrão **Request/Reply**.

Nessa etapa, o cliente envia uma requisição e espera uma resposta do servidor.

### Parte 2 — Pub/Sub e canais

A segunda etapa adiciona o modelo **Publish/Subscribe**.

Com isso, os clientes podem se inscrever em canais e receber mensagens publicadas pelos servidores por meio do proxy Pub/Sub.

### Parte 3 — Relógio lógico e serviço de referência

A terceira etapa adiciona:

- relógio lógico de Lamport em clientes e servidores;
- timestamps nas mensagens;
- serviço `reference` para registrar servidores;
- atribuição de `rank` aos servidores;
- heartbeat para manter a lista de servidores ativos.

O `rank` é usado como prioridade para identificar servidores e apoiar a eleição de coordenador nas etapas seguintes.

### Parte 4 — Coordenação, eleição e sincronização entre servidores

A quarta etapa resolve um problema importante: o broker pode enviar `create_channel` para um servidor e `publish_message` para outro. Se os servidores tiverem estados locais diferentes, surgem erros como:

```text
Canal ... nao existe
```

Para evitar isso, foram adicionados:

- sincronização de estado pelo tópico `servers.state`;
- eventos incrementais, como `channel_created`;
- snapshots periódicos, como `channels_snapshot` e `state_snapshot`;
- eleição de coordenador por menor `rank`;
- comunicação peer-to-peer entre servidores via REQ/REP;
- metadados `host` e `peer_port` no serviço de referência.

Assim, servidores Python e Java passam a convergir para o mesmo estado.

### Parte 5 — Replicação completa e permissão centralizada

A etapa final fortalece a consistência do sistema.

Além dos canais, passam a ser replicados:

- logins;
- canais;
- publicações.

A replicação usa dois mecanismos:

1. **Eventos incrementais**:
   - `login_created`;
   - `channel_created`;
   - `publication_created`.
2. **Snapshot periódico**:
   - `state_snapshot`, contendo logins, canais e publicações.

Também foi adotada a ideia de **permissão centralizada**: operações de escrita (`login`, `create_channel` e `publish_message`) podem ser encaminhadas ao coordenador usando uma mensagem interna `client_request`.

Dessa forma, a autorização da escrita fica centralizada no coordenador, mas os demais servidores continuam recebendo replicação do estado para manter convergência.

---

## Como identificar o coordenador

O coordenador esperado é o servidor ativo com **menor rank**.

Exemplo de logs:

```text
[SERVIDOR] servidor-py rank=1
[SERVIDOR-JAVA] servidor-java rank=2
```

Nesse caso, `servidor-py` é o coordenador esperado.

Para consultar a lista de servidores ativos no `reference`:

```bash
docker compose exec reference python - <<'PY'
import zmq, msgpack
ctx = zmq.Context()
s = ctx.socket(zmq.REQ)
s.connect("tcp://localhost:5559")
s.send(msgpack.packb({"type": "list"}, use_bin_type=True))
print(msgpack.unpackb(s.recv(), raw=False))
PY
```

---

## Como testar falha de servidor

Para parar o servidor Python:

```bash
docker compose stop servidor
```

Para parar o servidor Java:

```bash
docker compose stop servidor-java
```

O serviço de referência remove servidores inativos após o timeout de heartbeat. Aguarde cerca de 60 segundos e consulte novamente a lista de servidores ativos.

Para religar:

```bash
docker compose start servidor
# ou
docker compose start servidor-java
```

---

## Observações finais

O projeto demonstra vários conceitos de sistemas distribuídos:

- comunicação por mensagens;
- desacoplamento via broker;
- Pub/Sub;
- relógio lógico;
- heartbeat;
- eleição de coordenador;
- replicação eventual;
- tolerância parcial a falhas;
- interoperabilidade entre linguagens diferentes.
