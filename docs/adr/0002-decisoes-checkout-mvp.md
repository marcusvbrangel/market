# ADR 0002 — Decisões do checkout MVP

- **Status:** aceita
- **Data:** 20 de agosto de 2026
- **Escopo:** primeiro fluxo vertical de checkout orquestrado pelo microsserviço `order`

## Contexto

O projeto já cria pedidos `PENDING` e publica `OrderCreated`, mas ainda não executa reserva, pagamento, compensação ou conclusão da saga. Antes de ampliar a Outbox e implementar os demais participantes, o MVP precisa fixar as regras que afetam contratos, estados, persistência, idempotência e testes.

Sem essas decisões, implementações igualmente plausíveis poderiam divergir em pontos centrais, como reservar parcialmente, agregar produtos repetidos, autorizar sem capturar, aceitar múltiplas moedas ou criar mais de um pedido quando o cliente repetir uma requisição após perder a resposta HTTP.

## Decisão

### Pagamento e moeda

- O MVP usará uma única operação financeira de autorização com captura imediata, expressa pelo comando `ChargePayment`.
- O sucesso financeiro do MVP será o evento `PaymentCaptured`; `PaymentAuthorized` não representará conclusão do pagamento.
- A única moeda aceita será `BRL`, sem conversão cambial.
- O PostgreSQL reforçará essa decisão com constraint que permite `currency` nula enquanto o pedido não estiver precificado e aceita somente `BRL` quando preenchida.
- Valores monetários usarão precisão decimal e a mesma moeda em pedido, reserva e pagamento.
- Resultado financeiro desconhecido seguirá para reconciliação e nunca será tratado automaticamente como sucesso ou falha definitiva.

### Estoque, preço e reserva

- O MVP terá um único centro de estoque.
- O `inventory` será temporariamente a autoridade de produto, nome, preço vigente e saldo disponível.
- A reserva será atômica para todos os itens: todos serão reservados ou nenhum será alterado.
- O `InventoryReserved` devolverá o snapshot de produto e preço que o `order` persistirá no pedido.
- Uma reserva válida terá TTL fixo de 10 minutos no MVP.
- O `inventory` calculará `expiresAt` em UTC no instante em que confirmar a reserva e o incluirá no evento de resultado.
- O TTL não será renovado automaticamente no MVP.
- Uma reserva expirada não poderá ser confirmada e deverá ser liberada de maneira idempotente.

### Estados comerciais do pedido

| Estado | Regra |
|---|---|
| `PENDING` | A saga ainda está avançando, compensando ou reconciliando uma resposta ambígua. |
| `CONFIRMED` | O pagamento foi capturado e a reserva de estoque foi confirmada com baixa definitiva. |
| `REJECTED` | Nenhum pagamento permanece capturado e nenhum estoque permanece reservado; o motivo é obrigatório. |

`CANCELLED` e cancelamento posterior à confirmação ficam fora do MVP.

### Estados técnicos da saga

| Estado | Significado |
|---|---|
| `WAITING_INVENTORY` | Aguarda o resultado da reserva. |
| `WAITING_PAYMENT` | Aguarda o resultado da captura. |
| `PAYMENT_RECONCILIATION` | O efeito financeiro é desconhecido e precisa ser consultado. |
| `COMMITTING_INVENTORY` | Aguarda a baixa definitiva da reserva. |
| `RELEASING_INVENTORY` | Compensa uma reserva que não deve permanecer. |
| `REFUNDING_PAYMENT` | Compensa um pagamento capturado que não pode concluir a compra. |
| `COMPLETED` | A saga terminou com pedido `CONFIRMED`. |
| `COMPENSATED` | Todos os efeitos já produzidos foram desfeitos. |
| `REJECTED` | A saga terminou sem necessidade de compensação pendente e o pedido foi rejeitado. |
| `MANUAL_REVIEW` | Uma ambiguidade ou compensação não pôde ser resolvida automaticamente. |

O estado comercial continuará simples; detalhes técnicos, tentativas e deadlines permanecerão no estado e no histórico da saga.

### Produtos repetidos

- Um request de criação não poderá conter o mesmo `productId` mais de uma vez.
- A API rejeitará produto repetido com HTTP `400` e código estável `DUPLICATE_PRODUCT`.
- O serviço não agregará quantidades implicitamente.
- O banco protegerá a regra com unicidade por `(order_id, product_id)`.
- A rejeição acontecerá antes de criar pedido, itens, registro de idempotência ou mensagem na Outbox.

### Semântica do `Idempotency-Key`

- `Idempotency-Key` será obrigatório em `POST /api/v1/orders`.
- A ausência da chave retornará HTTP `400`.
- A chave será opaca, case-sensitive e terá entre 1 e 100 caracteres.
- A chave deverá corresponder integralmente à expressão regular `[A-Za-z0-9._:-]{1,100}`.
- A API rejeitará espaços, controles, chave vazia e caracteres fora do conjunto permitido com HTTP `400` e código `INVALID_IDEMPOTENCY_KEY`.
- Clientes não deverão colocar dados pessoais, credenciais ou segredos na chave.
- O escopo de unicidade será `(customerId, idempotencyKey)`.
- Clientes diferentes poderão usar o mesmo valor de chave sem conflito.
- A chave não poderá ser reutilizada para outro payload dentro do mesmo escopo.
- O `order` persistirá o claim e o resultado na tabela dedicada `api_idempotency`.
- A tabela `orders` não receberá colunas de idempotência.
- O banco garantirá unicidade por `(customer_id, idempotency_key)` em `api_idempotency`.
- A migration adicionará `UNIQUE (id, customer_id)` em `orders` e uma foreign key composta `(order_id, customer_id)` em `api_idempotency`, garantindo que o claim aponte para um pedido do mesmo cliente.
- `api_idempotency` armazenará cliente, chave, `request_hash_version`, hash e os campos necessários para reconstruir a resposta: `orderId`, `orderNumber`, `status` e `createdAt`.
- A versão persistida no MVP será `request_hash_version=1`, protegida por constraint.
- `api_idempotency` não duplicará status HTTP, `Location` nem o corpo JSON completo.
- O status HTTP de criação e replay será sempre `201` por definição do contrato.
- O header `Location` será derivado deterministicamente como `/api/v1/orders/{orderId}`.
- O corpo será reconstruído deterministicamente a partir de `orderId`, `orderNumber`, `status` e `createdAt`.
- O fingerprint será o value object `OrderCreationRequestFingerprint(version, hash)`; versão e hash serão produzidos, persistidos e comparados juntos.
- O hash será SHA-256 sobre uma representação canônica versionada do comando de criação.
- A representação canônica incluirá o identificador da operação e versão, o `customerId` em formato UUID canônico e os itens ordenados por `productId`, contendo `productId` canônico e `quantity`.
- A ordem original do array de itens não terá significado para a idempotência.
- Diferenças de espaços, formatação JSON, ordem de propriedades e ordem dos itens não alterarão o hash.
- A validação de produtos repetidos ocorrerá antes da canonicalização.
- O claim será tentado atomicamente no PostgreSQL com `INSERT ... ON CONFLICT DO NOTHING` em `api_idempotency`.
- Bean Validation, validação da chave, rejeição de duplicatas, canonicalização e construção do agregado ocorrerão antes da fronteira transacional.
- A fronteira `@Transactional` ficará em `PostgresOrderCreationAdapter.createOrReplay`; pedido, itens, claim, resultado idempotente e `OrderCreated` serão confirmados ou revertidos juntos no PostgreSQL.
- O instante de criação será truncado para microssegundos antes de compor pedido, resposta e evento, alinhando-o à precisão do PostgreSQL e preservando `createdAt` exatamente no replay.
- A primeira requisição válida retornará HTTP `201`, `Idempotency-Replayed: false`, `Location` e a representação do pedido criado.
- Uma repetição com a mesma chave, versão e hash retornará HTTP `201`, `Idempotency-Replayed: true`, o mesmo `Location` e o mesmo corpo da primeira resposta.
- Uma repetição válida não criará outro pedido, item ou registro na Outbox.
- Uma repetição com a mesma chave, mas versão ou hash diferente, retornará HTTP `409` com código `IDEMPOTENCY_KEY_REUSED` e não alterará o pedido original.
- Requisições concorrentes com a mesma chave serão arbitradas pelo claim atômico e pela restrição única no PostgreSQL.
- Se a primeira requisição concorrente confirmar, as demais lerão o registro confirmado e aplicarão as regras de replay ou conflito.
- Se a primeira requisição concorrente fizer rollback, outra tentativa poderá criar o pedido.
- Erros de validação anteriores à transação não reservarão a chave.
- Uma transação revertida não reservará a chave.
- Uma resposta HTTP perdida depois do commit será recuperada por replay com a mesma chave.
- Registros de `api_idempotency` não terão expiração nem remoção automática no piloto.
- Logs não registrarão a chave completa; usarão apenas `orderId`, `correlationId` ou um fingerprint sanitizado quando necessário.
- A idempotência HTTP não transforma Kafka em exactly-once; a Outbox continuará oferecendo publicação at-least-once e os consumidores stateful continuarão deduplicando mensagens.

### Evidências implementadas

- Um vetor conhecido fixa `version=1` e o SHA-256 `5798d1b87558114d39b20bd51a3ff74cbb3be8e32cca09b2467dc487f2147e96` para o formato canônico aprovado.
- Um teste integrado usa `TransactionTemplate` para forçar rollback, confirma ausência de claim, pedido, itens e Outbox e cria novamente com a mesma chave.
- A integração PostgreSQL/Kafka executa criação e replay antes da publicação e observa exatamente uma mensagem `OrderCreated` no tópico.
- O OpenAPI referencia `ApiProblemResponse` nas respostas `400` e `409`; em runtime, o handler continua emitindo `ProblemDetail` compatível com esse schema.

## Consequências

Benefícios:

- retries do cliente não criam compras nem eventos duplicados;
- o comportamento concorrente possui proteção no banco, e não apenas em memória;
- pedido, intenção de publicação e identidade da requisição permanecem atomicamente consistentes;
- contratos de estoque e pagamento partem de regras financeiras e temporais explícitas;
- rejeitar produtos repetidos evita ambiguidade entre posição, quantidade e snapshot de preço.

Custos e restrições:

- a API passa a exigir um header adicional;
- a implementação passa a manter uma tabela e o snapshot mínimo necessário ao replay;
- o request precisa de canonicalização estável e versionada;
- conflitos de unicidade precisam ser traduzidos para respostas determinísticas;
- o MVP suporta apenas BRL, um centro de estoque e captura imediata;
- alterações incompatíveis nessas decisões exigirão novo ADR e evolução dos contratos afetados.

## Alternativas rejeitadas no MVP

- autorização e captura em momentos separados;
- múltiplas moedas ou conversão cambial;
- múltiplos centros de estoque;
- preço informado pelo cliente ou calculado pelo `order`;
- reserva parcial;
- renovação automática do TTL;
- agregação silenciosa de produtos repetidos;
- `Idempotency-Key` opcional;
- idempotência somente em memória;
- colunas de idempotência diretamente em `orders`;
- expiração ou reutilização automática da chave no piloto.

## Critérios de revisão futura

Esta decisão deverá ser revista quando o sistema adicionar múltiplos meios de pagamento, autorização e captura separadas, mais de uma moeda, múltiplos centros de distribuição, catálogo/preço dedicado, cancelamento pós-confirmação ou novos comandos HTTP idempotentes.

## Referências

- [Arquitetura completa do fluxo de compra](../../full-architecture.md)
- [Arquitetura geral](../architecture.md)
- [Diretrizes de desenvolvimento](../development-guidelines.md)
- [ADR 0003 — Envelope, roteamento e lease da Outbox](0003-envelope-roteamento-e-lease-da-outbox.md)
- [Especificação atual do `order`](../../order/docs/spec.md)
- [Contrato de idempotência HTTP](../../order/docs/http-idempotency.md)
