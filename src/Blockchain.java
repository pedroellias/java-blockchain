import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;

public class Blockchain implements Serializable {

    private static final long serialVersionUID = 100L;

    private static final Block GENESIS_BLOCK = new Block(0, "0", "Bloco Genesis", 0L);

    private final List<Block> chain;
    private final int difficulty;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private volatile int chainVersion = 0;

    private transient ChainUpdateListener listener;

    public Blockchain(int difficulty) {
        this.difficulty = difficulty;
        this.chain = new ArrayList<>();
        this.chain.add(GENESIS_BLOCK);
    }

    public void setChainUpdateListener(ChainUpdateListener listener) {
        this.listener = listener;
    }

    public int getDifficulty() {
        return difficulty;
    }

    public int getChainVersion() {
        return chainVersion;
    }

    public List<Block> getSnapshot() {
        rwLock.readLock().lock();
        try {
            return new ArrayList<>(this.chain);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Block getLatestBlock() {
        rwLock.readLock().lock();
        try {
            return this.chain.get(this.chain.size() - 1);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public boolean isChainValid() {
        return validate(getSnapshot(), this.difficulty) == null;
    }

    /**
     * Mineração em Três Fases:
     * Leitura do estado inicial sob Read Lock.
     * Mineração fora do Lock (evita travar threads/rede).
     * Confirmação e adição sob Write Lock caso a versão não tenha mudado.
     */
    public MineResult addBlock(String data, BooleanSupplier cancelled) {
        Block previousBlock;
        int versionAtStart;

        // Leitura sob Lock
        rwLock.readLock().lock();
        try {
            previousBlock = getLatestBlock();
            versionAtStart = this.chainVersion;
        } finally {
            rwLock.readLock().unlock();
        }

        int newIndex = previousBlock.getIndice() + 1;
        long timeStamp = System.currentTimeMillis();
        Block newBlock = new Block(newIndex, previousBlock.getHash(), data, timeStamp);

        //pedido externo OU mudança na versão da cadeia
        BooleanSupplier combinedCancelled = () ->
                (cancelled != null && cancelled.getAsBoolean()) || (this.chainVersion != versionAtStart);

        // Mineração Fora do Lock
        boolean mined = newBlock.mine(this.difficulty, combinedCancelled);

        if (!mined) {
            return (this.chainVersion != versionAtStart)
                    ? MineResult.DISCARDED_CHAIN_UPDATED
                    : MineResult.CANCELLED;
        }

        List<Block> snapshotToNotify = null;

        // Gravação sob Write Lock
        rwLock.writeLock().lock();
        try {
            if (this.chainVersion == versionAtStart) {
                this.chain.add(newBlock);
                this.chainVersion++;
                snapshotToNotify = new ArrayList<>(this.chain);
            } else {
                return MineResult.DISCARDED_CHAIN_UPDATED;
            }
        } finally {
            rwLock.writeLock().unlock();
        }

        // evitar deadlocks
        if (snapshotToNotify != null) {
            notifyListenerSafely(snapshotToNotify);
            return MineResult.SUCCESS;
        }

        return MineResult.DISCARDED_CHAIN_UPDATED;
    }

    public ReplaceResult tryReplaceChain(List<Block> recebida) { //Substituindo a blockchain
        // Cópia defensiva imediata
        if (recebida == null || recebida.isEmpty()) {
            return ReplaceResult.reject("Cadeia nula ou vazia");
        }
        List<Block> copia = new ArrayList<>(recebida);

        // Validação pura e estática (fora do lock)
        String validationError = validate(copia, this.difficulty);
        if (validationError != null) {
            return ReplaceResult.reject("Cadeia inválida: " + validationError);
        }

        List<Block> snapshotToNotify = null;

        //Comparação de tamanho e substituição sob Write Lock
        rwLock.writeLock().lock();
        try {
            if (copia.size() <= this.chain.size()) {
                return ReplaceResult.reject(String.format(
                        "Tamanho menor ou igual à cadeia local (recebida: %d, local: %d)",
                        copia.size(), this.chain.size()
                ));
            }
            this.chain.clear();
            this.chain.addAll(copia);
            this.chainVersion++;
            snapshotToNotify = new ArrayList<>(this.chain);

        } finally {
            rwLock.writeLock().unlock();
        }

        // Notificação fora do Lock
        if (snapshotToNotify != null) {
            notifyListenerSafely(snapshotToNotify);
        }

        return ReplaceResult.accept();
    }

    private void notifyListenerSafely(List<Block> snapshot) {
        if (this.listener != null) {
            try {
                this.listener.onChainUpdated(snapshot);
            } catch (Exception e) {
                System.err.println("Aviso: Falha ao executar listener: " + e.getMessage());
            }
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

    public enum MineResult {
        SUCCESS,
        CANCELLED,
        DISCARDED_CHAIN_UPDATED
    }
}