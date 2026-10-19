# Plano de Trabalho: Implementação do Protocolo BCP

Este documento detalha o planejamento e a divisão de tarefas para a implementação do Blockchain Copy Protocol (BCP) em uma equipe de duas pessoas, visando a apresentação final em uma rede local de 5 computadores.

## Divisão de Papéis
A estratégia ideal é separar as responsabilidades por **Camadas de Atuação**:
* **Autor A (Foco em Redes e Arquitetura P2P):** Responsável pela comunicação, Sockets, Threads e Descoberta de Pares (Fluxo A).
* **Autor B (Foco em Blockchain e Consenso):** Responsável pela estrutura de dados, mineração, criptografia e regras de validação (Fluxos B e C).

---

## Fase 1: Planejamento e Estudos Iniciais (Trabalho Conjunto)
*Alinhamento tecnológico antes de iniciar a codificação.*

* **Decisão Arquitetural:** Definir se a serialização será nativa do Java (`ObjectInputStream`/`ObjectOutputStream`) ou baseada em JSON (usando bibliotecas como Gson ou Jackson para facilitar o debug).
* **Tópicos de Estudo - Autor A:** 
  * Sockets TCP (Cliente e Servidor).
  * Manipulação de múltiplas Threads (essencial para o nó atuar como Servent).
  * Serialização e transmissão de dados pela rede.
* **Tópicos de Estudo - Autor B:**
  * Funções de Hash (ex: SHA-256).
  * Estrutura básica de um Bloco e manipulação de listas encadeadas (`ArrayList`).
  * Conceito e lógica matemática do *Proof of Work* (PoW).

---

## Fase 2: Desenvolvimento da Base (Trabalho Paralelo)

### Tarefas do Autor A (Redes)
* [ ] **Classe Servent:** Criar a estrutura base do nó que inicia um `ServerSocket` (ex: porta 8333) rodando em uma Thread separada.
* [ ] **Estrutura de Mensagens:** Criar a classe `NetworkMessage` contendo o Enum `MessageType` e o payload genérico.
* [ ] **Gerenciador de Conexões:** Implementar uma lista local para armazenar endereços (`IP:Porta`) de peers conhecidos e métodos para iniciar conexões (`Socket` de saída).

### Tarefas do Autor B (Blockchain)
* [ ] **Estrutura do Bloco:** Criar a classe genérica `Block` (índice, timestamp, dados, `previousHash`, `hash` atual, `nonce`).
* [ ] **Criptografia:** Codificar o método determinístico responsável por gerar e calcular o Hash do bloco.
* [ ] **Mineração e Validação:** 
  * Estruturar a classe `Blockchain` contendo o `ArrayList<Block>`.
  * Implementar a lógica de mineração (PoW).
  * Criar o método vital `isChainValid()` para varrer e validar a integridade da cadeia.

---

## Fase 3: Implementação dos Fluxos do Protocolo (Trabalho Paralelo)

### Tarefas do Autor A (Fluxo A - Bootstrap)
* [ ] **Handshake:** Implementar o envio e validação da mensagem `HELLO` na abertura do Socket.
* [ ] **Troca de Peers:** Criar a lógica de requisição (`GET_PEERS`) e resposta (`PEERS_LIST`), garantindo a adição de novos nós na lista local sem duplicações.
* [ ] **Seed Node:** Hardcodar ao menos um endereço de IP estático para atuar como ponto de entrada inicial da rede P2P.

### Tarefas do Autor B (Fluxos B e C - Sincronização)
* [ ] **Empacotamento:** Lógica para serializar e preparar o `ArrayList<Block>` para transmissão.
* [ ] **Regra de Consenso:** Implementar a lógica de substituição de cadeia: validar a cadeia externa (`isChainValid()`) e verificar se é maior que a local (`size()`). Gerar `REJECT` caso contrário.
* [ ] **Gatilho de Broadcast:** Criar a chamada (hook) que avisa a camada de rede para disparar o `BLOCKCHAIN_COPY` assim que um bloco é minerado com sucesso.

---

## Fase 4: Integração (Trabalho Conjunto)
*União das camadas de rede e lógica de negócio.*

* [ ] **Conexão de Recebimento:** Autor A vincula o recebimento de blocos da rede ao validador do Autor B.
* [ ] **Conexão de Envio:** Autor B vincula o sucesso da mineração à função de broadcast do Autor A.
* [ ] **Tratamento de Exceções:** Garantir a estabilidade (ex: quedas de conexão abruptas durante a transferência de blocos).
* [ ] **Interface do Usuário (CLI):** Criar um menu interativo simples no terminal (ex: [1] Ver Peers, [2] Ver Blockchain, [3] Minerar).

---

## Fase 5: Setup e Roteiro de Testes (Apresentação Prática)
*Preparação para a demonstração nos 5 computadores físicos.*

1. **Configuração da Rede:** Conectar todos os 5 computadores na mesma LAN e mapear os IPs locais (`192.168.x.x`).
2. **Definição do Nó Semente:** Escolher o Computador 1 para ser o Seed Node. Atualizar o código dos outros 4 com esse IP.
3. **Roteiro de Demonstração (Laboratório):**
   * Ligar o **Nó 1** (inicia isolado com o Bloco Gênesis).
   * Ligar os **Nós 2 e 3**, demonstrando a conexão inicial no Nó 1 e a sincronização do Bloco Gênesis.
   * Ligar os **Nós 4 e 5** apontando para os Nós 2 e 3, evidenciando o recurso de descoberta de topologia P2P.
   * Acionar a mineração de um novo bloco no **Nó 4**.
   * Observar os logs em todos os outros 4 terminais, comprovando o recebimento via broadcast, validação e atualização do estado global de consenso.