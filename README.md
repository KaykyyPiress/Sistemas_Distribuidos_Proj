# Projeto de Sistemas Distribuídos – Parte 1

## Introdução

Este projeto implementa um sistema distribuído baseado no padrão cliente-servidor com a utilização de um broker intermediário. O objetivo desta primeira parte é permitir que clientes realizem login, criem canais de comunicação e listem os canais existentes.

A arquitetura do sistema é composta por três principais componentes:

- **Cliente**: responsável por enviar requisições ao sistema  
- **Broker**: intermediário responsável por encaminhar mensagens entre clientes e servidores  
- **Servidor**: responsável por processar as requisições e manter o estado da aplicação  

A comunicação segue o padrão *request-reply* utilizando sockets.

---

## Portas utilizadas

- **Broker (frontend - clientes)**: `tcp://localhost:5555`  
- **Broker (backend - servidores)**: `tcp://localhost:5556`  

---

## Funcionamento geral

O fluxo de comunicação do sistema ocorre da seguinte forma:

1. O cliente envia uma requisição para o broker  
2. O broker encaminha a requisição para um dos servidores disponíveis  
3. O servidor processa a requisição  
4. A resposta retorna ao cliente através do broker  

### Fluxo simplificado

Cliente → Broker → Servidor  
Cliente ← Broker ← Servidor

---

## Tecnologias utilizadas

### Linguagens

Foram utilizadas as linguagens **Python** e **Java**. A escolha foi feita com base na familiaridade prévia do grupo com essas tecnologias, permitindo maior agilidade no desenvolvimento e integração dos componentes.

---

## Serialização

A comunicação entre os componentes é realizada utilizando **MessagePack**, um formato de serialização binário.

### Motivos da escolha:

- Atende ao requisito do projeto de não utilizar JSON ou texto simples  
- Possui melhor desempenho que JSON  
- Gera mensagens menores (mais eficiente para rede)  
- Suporte tanto em Python quanto em Java  

Todas as mensagens trocadas no sistema possuem:

- Tipo da requisição  
- Payload (dados)  
- Timestamp de envio  

---

## Persistência

Cada servidor mantém seu próprio estado local, armazenado em disco utilizando arquivos no formato MessagePack.

### Dados persistidos:

- Usuários que realizaram login (com timestamp)  
- Lista de canais criados  

Cada servidor possui seu próprio arquivo de estado, não sendo compartilhado com outros servidores, conforme especificação do projeto.

---

## Importante sobre consistência

Como cada servidor mantém seu próprio estado independente e o broker distribui as requisições entre múltiplos servidores, pode ocorrer inconsistência na visão do cliente.

### Exemplo:

- Um canal pode ser criado em um servidor  
- Outro servidor pode não conhecer esse canal  
- Requisições consecutivas podem retornar resultados diferentes  

Essa característica é esperada em sistemas distribuídos sem replicação de estado e não foi tratada nesta etapa do projeto.

---

## Funcionalidades implementadas

- Login de usuários  
- Criação de canais  
- Listagem de canais  
- Comunicação distribuída com broker  
- Persistência de dados em disco  
- Serialização binária com MessagePack  
- Uso de timestamp em todas as mensagens  

---

## Execução

O projeto pode ser executado com Docker através do comando:

docker compose up

Esse comando inicializa:

- Broker  
- Servidores (Python e Java)  
- Clientes (Python e Java)  

Permitindo simular um ambiente distribuído completo.

---

## Considerações finais

Esta primeira etapa estabelece a base do sistema distribuído, definindo:

- Estrutura de comunicação  
- Formato das mensagens  
- Persistência de dados  

As próximas etapas do projeto irão expandir o sistema, adicionando funcionalidades como envio e armazenamento de mensagens entre usuários nos canais.
