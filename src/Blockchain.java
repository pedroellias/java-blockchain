import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class Blockchain implements Serializable {

    private static final long serialVersionUID = 100L;

    public static final Block GENESIS_BLOCK = new Block(0, "0", "Bloco Genesis", 0L);

    @SuppressWarnings("serial")
    private final List<Block> chain;
    private final int difficulty;
    private volatile int chainVersion = 0;

    public Blockchain(int difficulty) {
        this.difficulty = difficulty;
        this.chain = new ArrayList<>();
        this.chain.add(GENESIS_BLOCK);
    }

    public int getDifficulty() {
        return difficulty;
    }

    public int getChainVersion() {
        return chainVersion;
    }

    public synchronized List<Block> getSnapshot() {
        return new ArrayList<>(this.chain);
    }

    public synchronized Block getLatestBlock() {
        return this.chain.get(this.chain.size() - 1);
    }

    public boolean isChainValid() {
        return validate(getSnapshot(), this.difficulty) == null;
    }

    /**
     * Mineração
     * Leitura do estado inicial sob sincronização.
     * Mineração pura executada FORA de blocos synchronized.
     * Confirmação e escrita sob sincronização se a versão não mudou.
     *
     * LIMITAÇÃO CONHECIDA (bloco perdido em fork): se a cadeia local for substituída por uma
     * maior vinda de outro peer enquanto este nó minera (ou logo depois de minerar, antes do
     * broadcast chegar aos demais), o bloco minerado sobre a base antiga é descartado
     * (DISCARDED_CHAIN_UPDATED) e os dados dele NÃO são re-minerados automaticamente, pois
     * não existe mempool/fila de transações. É o mesmo comportamento de um bloco órfão no
     * Bitcoin: quem minera sobre a ponta errada perde o trabalho. Quem chama addBlock deve
     * tratar DISCARDED_CHAIN_UPDATED: ou avisa o usuário, ou chama addBlock de novo com os
     * mesmos dados, o que re-minera sobre a nova ponta.
     * Resultados possíveis: SUCCESS (bloco entrou; o chamador deve fazer o broadcast),
     * CANCELLED (cancelamento externo) e DISCARDED_CHAIN_UPDATED (descrito acima).
     */
    public MineResult addBlock(String data, BooleanSupplier cancelled) {
        Block previousBlock;
        int versionAtStart;

        //estado
        synchronized (this) {
            previousBlock = getLatestBlock();
            versionAtStart = this.chainVersion;
        }

        int newIndex = previousBlock.getIndice() + 1;
        long timeStamp = System.currentTimeMillis();
        Block newBlock = new Block(newIndex, previousBlock.getHash(), data, timeStamp);

        // Cancelamento condicional baseado na versão da cadeia
        BooleanSupplier combinedCancelled = () ->
                (cancelled != null && cancelled.getAsBoolean()) || (this.chainVersion != versionAtStart);

        // Mineração
        boolean mined = newBlock.mine(this.difficulty, combinedCancelled);

        if (!mined) {
            return (this.chainVersion != versionAtStart)
                    ? MineResult.DISCARDED_CHAIN_UPDATED
                    : MineResult.CANCELLED;
        }

        synchronized (this) {
            if (this.chainVersion == versionAtStart) {
                this.chain.add(newBlock);
                this.chainVersion++;
                return MineResult.SUCCESS;
            } else {
                return MineResult.DISCARDED_CHAIN_UPDATED;
            }
        }
    }

    /**
     * Fluxo B/C da especificação corrigido
     *
     * A cadeia recebida só substitui a local se for válida E ESTRITAMENTE maior
     * (recebida.size() > local.size()). Cadeias de tamanho igual são sempre rejeitadas,
     * inclusive "só gênesis x só gênesis": como o gênesis é determinístico, todo peer já
     * nasce com ele e não há nada a ganhar. Aceitar tamanho igual também incrementaria o
     * chainVersion (cancelando uma mineração em andamento à toa) e, com re-propagação
     * implementada no Peer, faria a mesma cadeia circular entre os nós para sempre.
     *
     * A validação (recalcula todos os hashes) roda FORA do lock; só a comparação de
     * tamanho e a troca rodam sob synchronized.
     *
     * Quando a troca acontece, o chainVersion é incrementado, o que faz qualquer addBlock()
     * em andamento descartar o bloco que estava minerando (ver limitação em addBlock).
     */
    public ReplaceResult tryReplaceChain(List<Block> recebida) {
        if (recebida == null || recebida.isEmpty()) {
            return ReplaceResult.reject("CHAIN_ERROR: Cadeia nula ou vazia");
        }
        List<Block> copia = new ArrayList<>(recebida);

        // Validação estática
        String validationError = validate(copia, this.difficulty);
        if (validationError != null) {
            return ReplaceResult.reject("CHAIN_ERROR: Cadeia inválida (" + validationError + ")");
        }

        synchronized (this) {
            if (copia.size() <= this.chain.size()) {
                return ReplaceResult.reject(String.format(
                        "CHAIN_ERROR: Tamanho menor ou igual à cadeia local (recebida: %d, local: %d)",
                        copia.size(), this.chain.size()
                ));
            }

            this.chain.clear();
            this.chain.addAll(copia);
            this.chainVersion++;

            return ReplaceResult.accept();
        }
    }

    public static String validate(List<Block> mensagem, int difficulty) {
        try {
            if (mensagem == null || mensagem.isEmpty()) {
                return "Cadeia nula ou vazia";
            }

            Block genesis = mensagem.get(0);

            if (genesis == null) {
                return "O primeiro bloco (Gênesis) está nulo";
            }

            if (genesis.getIndice() != 0) {
                return "Primeiro bloco deve ter índice 0";
            }

            if (genesis.getHash() == null || !genesis.getHash().equals(GENESIS_BLOCK.getHash())) {
                return "Bloco Gênesis não confere com o padrão da rede";
            }

            if (!genesis.getHash().equals(genesis.calculateHash())) {
                return "Bloco Gênesis foi adulterado";
            }

            String targetPrefix = "0".repeat(difficulty);

            for (int i = 0; i < mensagem.size(); i++) {
                Block currentBlock = mensagem.get(i);

                if (currentBlock == null) {
                    return String.format("O bloco na posição %d está nulo", i);
                }

                if (currentBlock.getIndice() != i) {
                    return String.format("Índice inconsistente no bloco %d (esperado: %d)", currentBlock.getIndice(), i);
                }

                if (currentBlock.getHash() == null || !currentBlock.getHash().equals(currentBlock.calculateHash())) {
                    return String.format("O hash do bloco %d foi adulterado!", currentBlock.getIndice());
                }

                if (i > 0) {
                    Block previousBlock = mensagem.get(i - 1);

                    if (currentBlock.getPreviousHash() == null || !currentBlock.getPreviousHash().equals(previousBlock.getHash())) {
                        return String.format("O previousHash do bloco %d não confere com o bloco %d!",
                                currentBlock.getIndice(), previousBlock.getIndice());
                    }

                    if (!currentBlock.getHash().startsWith(targetPrefix)) {
                        return String.format("O bloco %d não atende à Proof of Work!", currentBlock.getIndice());
                    }
                }
            }

            return null;

        } catch (Exception e) {
            return "Cadeia com falhas: " + e.getClass().getSimpleName();
        }
    }

    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Blockchain | blocos=%d | difficulty=%d | chainVersion=%d",
                chain.size(), difficulty, chainVersion));
        for (Block b : chain) {
            sb.append(String.format("%n  #%-3d hash=%s  prev=%-12s  nonce=%-6d data=\"%s\"",
                    b.getIndice(),
                    StringUtil.shortHash(b.getHash()),
                    StringUtil.shortHash(b.getPreviousHash()),
                    b.getNonce(),
                    StringUtil.shorten(b.getData(), 40)));
        }
        return sb.toString();
    }


    public enum MineResult {
        SUCCESS,
        CANCELLED,
        DISCARDED_CHAIN_UPDATED
    }
}