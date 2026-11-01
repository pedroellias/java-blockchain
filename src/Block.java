import java.io.Serializable;
import java.util.function.BooleanSupplier;

public final class Block implements Serializable {

    private static final long serialVersionUID = 13L;

    private String hash;
    private final int indice;
    private long nonce;
    private final String previousHash;
    private final String data;
    private final long timeStamp;

    public Block(int indice, String previousHash, String data, long timeStamp) {
        this.indice = indice;
        this.data = data == null ? "" : data;
        this.previousHash = previousHash == null ? "" : previousHash;
        this.timeStamp = timeStamp;
        this.hash = calculateHash();
    }

    public String getHash() {
        return hash;
    }

    public int getIndice() {
        return indice;
    }

    public long getNonce() {
        return nonce;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public String getData() {
        return data;
    }

    public long getTimeStamp() {
        return timeStamp;
    }

    public String calculateHash() {
        return StringUtil.applySha256(
                String.join("|",
                        String.valueOf(indice),
                        String.valueOf(timeStamp),
                        data,
                        previousHash,
                        String.valueOf(nonce))
        );
    }

    public boolean hasValidHash() {
        return this.hash != null && this.hash.equals(calculateHash());
    }

    public boolean mine(int difficulty, BooleanSupplier cancelled) {
        if (difficulty <= 0) {
            return true;
        }

        String target = "0".repeat(difficulty);

        while (hash == null || hash.length() < difficulty || !hash.startsWith(target)) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return false;
            }

            nonce++;
            hash = calculateHash();
        }

        return true;
    }
    @Override
    public String toString() {
        // Uma linha só e hashes abreviados (12 chars): é isto que aparece nos logs de rede,
        // onde BLOCKCHAIN_COPY imprime a lista inteira de blocos. Para o hash completo use getHash().
        return "Block#" + indice
                + "{hash=" + StringUtil.shortHash(hash)
                + ", prev=" + StringUtil.shortHash(previousHash)
                + ", nonce=" + nonce
                + ", data=\"" + StringUtil.shorten(data, 40) + "\"}";
    }

}

//classe do bloco funcionando com hash calculado usando o sha256