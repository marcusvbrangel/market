# Redpanda Console

## Estado atual

O Redpanda Console está instalado, configurado e validado no ambiente Docker Compose local do projeto Market.

Configuração atual:

| Item | Valor |
|---|---|
| Imagem | `docker.redpanda.com/redpandadata/console:v3.10.0` |
| Container | `market-redpanda-console` |
| URL local | `http://localhost:8088` |
| Broker interno | `redpanda:9092` |
| Schema Registry interno | `http://redpanda:8081` |
| Admin API interna | `http://redpanda:9644` |
| Rede Docker | `market_net` |

Em 19 de agosto de 2026, o Console foi iniciado com sucesso, respondeu HTTP `200`, conectou-se ao cluster Redpanda e identificou o tópico `market.order.events.v1`. A interface também foi validada manualmente pelo usuário.

## Como iniciar

Na raiz do projeto:

```bash
docker compose -f compose.yaml up -d
```

Para iniciar somente o Console e suas dependências:

```bash
docker compose -f compose.yaml up -d redpanda-console
```

Depois, acessar pelo navegador:

```text
http://localhost:8088
```

## Como usar

Na página inicial, selecionar o cluster disponível. O menu do Console permite:

- consultar os tópicos e suas configurações;
- visualizar partições e distribuição das mensagens;
- consultar mensagens por tópico;
- conferir chave, payload, headers, partition, offset e timestamp;
- consultar schemas quando forem registrados no Schema Registry;
- verificar informações do broker e do cluster.

### Inspecionar eventos de pedidos

Para consultar os eventos publicados pelo microsserviço `order`:

1. abrir `Topics`;
2. selecionar `market.order.events.v1`;
3. abrir a seção de mensagens;
4. selecionar todas as partições ou a partição desejada;
5. iniciar a consulta a partir do offset ou instante necessário;
6. localizar o evento pelo `orderId` usado como chave Kafka.

Uma mensagem `OrderCreated` deve possuir:

- chave igual ao `orderId`;
- payload JSON com dados do pedido e seus itens;
- header `eventId` para deduplicação;
- header `eventType` com valor `OrderCreated`;
- header `schemaVersion` com valor `1`;
- header `correlationId`;
- header `occurredAt` em UTC.

O evento não contém nome nem preço dos produtos.

## Comandos operacionais

Consultar o estado do container:

```bash
docker compose -f compose.yaml ps redpanda-console
```

Consultar logs:

```bash
docker compose -f compose.yaml logs -f redpanda-console
```

Reiniciar:

```bash
docker compose -f compose.yaml restart redpanda-console
```

Parar somente o Console:

```bash
docker compose -f compose.yaml stop redpanda-console
```

## Diagnóstico

Se `http://localhost:8088` não responder:

1. confirmar se o container está em execução com `docker compose ps`;
2. conferir os logs do `redpanda-console`;
3. confirmar se o container `market-redpanda` está saudável;
4. verificar se a porta `8088` já está sendo usada por outro processo;
5. reiniciar o Console após o Redpanda ficar saudável.

Nos logs, a mensagem `successfully connected to kafka cluster` confirma a conexão com o broker.

## Restrições

O Console é uma ferramenta operacional do ambiente local. Nesta fase não há autenticação configurada, portanto:

- a porta `8088` deve permanecer acessível somente na máquina de desenvolvimento;
- o Console não deve ser exposto publicamente;
- alterações ou exclusões de tópicos e mensagens devem ser feitas com cuidado;
- uma futura implantação compartilhada deverá definir autenticação, autorização e acesso de rede antes da exposição.
