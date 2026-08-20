# Tarefas do microsserviço Order

Legenda:

- `[x]`: concluída;
- `[ ]`: pendente.

## 1. Contrato REST e demonstração

- [x] Substituir o starter básico pelo `spring-boot-starter-webmvc`.
- [x] Adicionar `spring-boot-starter-validation`.
- [x] Criar o Record `OrderResponse`.
- [x] Criar o Record interno `OrderResponse.Item`.
- [x] Adicionar validações Jakarta aos componentes dos Records.
- [x] Criar os status `PENDING`, `CONFIRMED` e `REJECTED`.
- [x] Tornar imutável a lista de itens recebida pelo Record.
- [x] Criar `OrderController` em `/api/v1/orders`.
- [x] Expor `GET /api/v1/orders/{orderId}`.
- [x] Validar o UUID recebido pelo controller.
- [x] Criar um pedido fictício com cinco itens eletrônicos.
- [x] Retornar `200 OK` para o UUID de demonstração.
- [x] Retornar `404 Not Found` para outro UUID válido.
- [x] Validar manualmente o endpoint no Postman.
- [x] Executar os testes Maven com sucesso.

## 2. Domínio

- [x] Definir as invariantes iniciais de `Order` e `OrderItem`.
- [x] Criar a entidade de domínio `Order` sem dependências do Spring ou JPA.
- [x] Criar o modelo de domínio `OrderItem`.
- [x] Criar a representação de status no domínio.
- [x] Definir a representação de valores monetários e moeda.
- [x] Garantir que um pedido possua pelo menos um item.
- [x] Garantir quantidade maior que zero.
- [x] Garantir preços e subtotais válidos.
- [x] Calcular ou validar o total do pedido no domínio.
- [x] Criar testes unitários para as invariantes do domínio.

## 3. Service de aplicação

- [x] Definir a porta de consulta de pedidos por UUID.
- [x] Criar o caso de uso/service de consulta por identificador.
- [x] Representar pedido não encontrado sem acoplar o service ao HTTP.
- [x] Criar o mapeamento do resultado da aplicação para `OrderResponse`.
- [x] Injetar o service no `OrderController`.
- [x] Remover a construção do pedido fictício do controller.
- [x] Manter `200 OK` para pedido existente.
- [x] Manter `404 Not Found` para pedido inexistente.
- [x] Criar testes unitários do service.
- [x] Criar teste do controller para sucesso, ausência e UUID inválido.

## 4. Persistência PostgreSQL

- [x] Adicionar Spring Data JPA.
- [x] Adicionar o driver PostgreSQL.
- [x] Configurar o datasource de `order_db`.
- [x] Externalizar usuário, senha e URL de conexão.
- [x] Criar os modelos JPA de pedido e itens.
- [x] Configurar o relacionamento entre pedido e itens.
- [x] Adicionar optimistic locking quando o modelo persistente for definido.
- [x] Criar o repository Spring Data.
- [x] Implementar o adaptador da porta de consulta.
- [x] Criar o mapeamento entre persistência e domínio.
- [x] Configurar o Hibernate apenas para validar o schema.

## 5. Flyway

- [x] Adicionar a dependência do Flyway compatível com PostgreSQL.
- [x] Criar `V1__create_orders_and_order_items.sql`.
- [x] Criar a tabela de pedidos.
- [x] Criar a tabela de itens do pedido.
- [x] Criar restrições de integridade e relacionamento.
- [x] Criar índices necessários para consulta.
- [x] Validar a migration no PostgreSQL local.
- [x] Criar teste de integração das migrations com Testcontainers.

## 6. Integração e aceite

- [x] Adicionar Testcontainers para PostgreSQL.
- [x] Criar teste de integração do repository/adaptador.
- [x] Preparar dados de teste de maneira reproduzível.
- [x] Executar toda a suíte Maven.
- [x] Subir a aplicação conectada ao PostgreSQL local.
- [x] Consultar no Postman um pedido realmente persistido.
- [x] Confirmar automaticamente que pedido inexistente retorna `404 Not Found`.
- [x] Substituir o pedido construído no controller por um pedido persistido pelo Flyway.
- [x] Atualizar `spec.md`, `plan.md` e este arquivo após a conclusão.

## 7. Criação de pedido e Transactional Outbox

- [x] Criar `POST /api/v1/orders`.
- [x] Criar `CreateOrderRequest` como Java Record.
- [x] Aceitar somente `customerId`, `productId` e `quantity` na criação.
- [x] Remover nome, preço, subtotal, total e moeda do contrato de criação.
- [x] Validar cliente, itens, produtos e quantidades.
- [x] Rejeitar propriedades JSON desconhecidas.
- [x] Criar resposta com `id`, `orderNumber`, `status` e `createdAt`.
- [x] Retornar `201 Created` e header `Location`.
- [x] Criar `CreateOrderService` e `OrderCreationPort`.
- [x] Gerar UUID e número legível para o pedido.
- [x] Criar pedidos inicialmente com status `PENDING`.
- [x] Permitir pedido `PENDING` ainda não precificado no domínio.
- [x] Refatorar persistência para dados de precificação ainda ausentes.
- [x] Criar migration `V3__support_order_creation_and_outbox.sql`.
- [x] Criar tabela `outbox_events` e seus índices.
- [x] Criar evento `OrderCreated` sem nome ou preço de produto.
- [x] Persistir pedido, itens e outbox na mesma transação.
- [x] Manter a outbox em `PENDING`, sem publisher Kafka.
- [x] Usar Jackson 3 para serialização do payload JSONB.
- [x] Testar domínio, service, controller, Flyway, persistência e outbox.
- [x] Executar 18 testes sem falhas.
- [x] Validar manualmente o `POST /api/v1/orders` com retorno `201 Created`.
- [x] Confirmar manualmente no PostgreSQL a persistência do pedido e de seus itens.
- [x] Confirmar manualmente o evento `OrderCreated` em `outbox_events` com status `PENDING`.
