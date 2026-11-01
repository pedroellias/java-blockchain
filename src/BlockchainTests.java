import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Testes do Autor B: Block, StringUtil, ReplaceResult e Blockchain (validação, consenso, mineração).
 * Sem dependências externas (não precisa de JUnit): compile junto com as classes do projeto e rode o main.
 *
 *   javac -encoding UTF-8 *.java
 *   java -Dstdout.encoding=UTF-8 BlockchainTests
 *
 * Sai com código 1 se algum teste falhar.
 */
public class BlockchainTests {

    static final int DIF = 2;

    // sha256("0|0|Bloco Genesis|0|0") calculado de forma independente (sha256sum).
    // Todos os nós da rede precisam concordar nesse valor, senão nenhuma cadeia é aceita.
    static final String GENESIS_HASH_ESPERADO =
            "32dcea21185fe3ca34f0a0c070704b7db0e5c7cdceabd128ab4adb3c9269f02e";

    static int ok = 0, falhas = 0;

    interface Teste { void rodar() throws Exception; }

    static void teste(String nome, Teste t) {
        try {
            t.rodar();
            ok++;
            System.out.println("  [OK]    " + nome);
        } catch (Throwable e) {
            falhas++;
            System.out.println("  [FALHA] " + nome + "\n            -> " + e);
        }
    }

    static void verdade(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    static void igual(Object esperado, Object atual, String msg) {
        if (!Objects.equals(esperado, atual)) {
            throw new AssertionError(msg + " (esperado: " + esperado + ", atual: " + atual + ")");
        }
    }

    // ---------- helpers ----------

    static Blockchain cadeia(int dif, int n, String prefixo) {
        Blockchain c = new Blockchain(dif);
        for (int i = 1; i <= n; i++) {
            Blockchain.MineResult r = c.addBlock(prefixo + "-" + i, null);
            if (r != Blockchain.MineResult.SUCCESS) throw new IllegalStateException("mineração falhou: " + r);
        }
        return c;
    }

    /**
     * Devolve uma cópia do bloco com o texto dos dados trocado SEM recalcular o hash, como se
     * alguém tivesse alterado os bytes em trânsito. Os textos precisam ter o mesmo tamanho,
     * senão o prefixo de tamanho da serialização Java ficaria inconsistente.
     */
    static Block adulterarDados(Block original, String de, String para) throws Exception {
        if (de.length() != para.length()) throw new IllegalArgumentException("textos de tamanhos diferentes");
        byte[] bytes = serializar(original);
        int pos = indexOf(bytes, de.getBytes(StandardCharsets.US_ASCII));
        verdade(pos >= 0, "texto \"" + de + "\" não encontrado nos bytes do bloco");
        byte[] novo = para.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(novo, 0, bytes, pos, novo.length);
        return (Block) desserializar(bytes);
    }

    static byte[] serializar(Object o) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(o);
        }
        return bos.toByteArray();
    }

    static Object desserializar(byte[] bytes) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return ois.readObject();
        }
    }

    static int indexOf(byte[] alvo, byte[] busca) {
        for (int i = 0; i <= alvo.length - busca.length; i++) {
            int j = 0;
            while (j < busca.length && alvo[i + j] == busca[j]) j++;
            if (j == busca.length) return i;
        }
        return -1;
    }

    static String erroDe(List<Block> lista) {
        return Blockchain.validate(lista, DIF);
    }

    // ---------- main ----------

    public static void main(String[] args) throws Exception {
        long inicio = System.currentTimeMillis();

        System.out.println("\n== StringUtil ==");
        testesStringUtil();
        System.out.println("\n== Block ==");
        testesBlock();
        System.out.println("\n== ReplaceResult ==");
        testesReplaceResult();
        System.out.println("\n== Blockchain: mineração básica ==");
        testesMineracao();
        System.out.println("\n== Blockchain: validate() ==");
        testesValidate();
        System.out.println("\n== Blockchain: consenso (tryReplaceChain) ==");
        testesConsenso();
        System.out.println("\n== Blockchain: concorrência e fork ==");
        testesConcorrencia();

        System.out.println("\n== toString (leitura no terminal) ==");
        testesToString();

        System.out.println("\n-----------------------------------------");
        System.out.printf("%d testes: %d OK, %d falha(s)  (%d ms)%n",
                ok + falhas, ok, falhas, System.currentTimeMillis() - inicio);
        System.exit(falhas == 0 ? 0 : 1);
    }

    // ---------- StringUtil ----------

    static void testesStringUtil() {
        teste("SHA-256 de \"abc\" bate com o vetor de teste oficial", () ->
                igual("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                        StringUtil.applySha256("abc"), "hash de abc"));

        teste("SHA-256 da string vazia bate com o vetor oficial", () ->
                igual("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                        StringUtil.applySha256(""), "hash de vazio"));

        teste("Sempre 64 caracteres hex minúsculos (testa o zero à esquerda de cada byte)", () -> {
            Random rnd = new Random(42);
            for (int i = 0; i < 2000; i++) {
                String entrada = "entrada-" + rnd.nextLong();
                String h = StringUtil.applySha256(entrada);
                verdade(h.length() == 64, "tamanho " + h.length() + " para " + entrada);
                verdade(h.matches("[0-9a-f]{64}"), "formato inválido: " + h);
            }
        });

        teste("Acentos: mesma entrada gera mesmo hash (UTF-8 fixo, independente do SO)", () ->
                igual(StringUtil.applySha256("coração"), StringUtil.applySha256("coração"), "determinismo"));
    }

    // ---------- Block ----------

    static void testesBlock() {
        teste("Bloco gênesis tem hash esperado (igual em todas as máquinas)", () ->
                igual(GENESIS_HASH_ESPERADO, Blockchain.GENESIS_BLOCK.getHash(), "hash do gênesis"));

        teste("Mesmos dados geram o mesmo hash; dados diferentes geram hash diferente", () -> {
            Block a = new Block(1, "abc", "dados", 100L);
            Block b = new Block(1, "abc", "dados", 100L);
            Block c = new Block(1, "abc", "outros", 100L);
            igual(a.getHash(), b.getHash(), "mesmo conteúdo");
            verdade(!a.getHash().equals(c.getHash()), "dados diferentes devem mudar o hash");
        });

        teste("data/previousHash nulos viram string vazia", () -> {
            Block b = new Block(1, null, null, 1L);
            igual("", b.getData(), "data");
            igual("", b.getPreviousHash(), "previousHash");
        });

        teste("mine(): hash começa com N zeros, nonce > 0 e hash == calculateHash()", () -> {
            Block b = new Block(1, Blockchain.GENESIS_BLOCK.getHash(), "x", 5L);
            verdade(b.mine(3, null), "mine deveria retornar true");
            verdade(b.getHash().startsWith("000"), "sem PoW: " + b.getHash());
            igual(b.calculateHash(), b.getHash(), "hash consistente");
            verdade(b.hasValidHash(), "hasValidHash");
        });

        teste("mine(): cancelamento interrompe e devolve false (sem travar)", () -> {
            Block b = new Block(1, Blockchain.GENESIS_BLOCK.getHash(), "x", 5L);
            long t0 = System.currentTimeMillis();
            boolean r = b.mine(10, () -> true); // dificuldade 10 é impraticável: só termina se cancelar
            verdade(!r, "deveria retornar false ao cancelar");
            verdade(System.currentTimeMillis() - t0 < 2000, "demorou demais para cancelar");
            verdade(!b.getHash().startsWith("0".repeat(10)), "não pode ter PoW completo");
        });

        teste("mine(): dificuldade 0 retorna true sem minerar", () -> {
            Block b = new Block(1, "p", "x", 5L);
            verdade(b.mine(0, null), "dificuldade 0");
            igual(0L, b.getNonce(), "nonce deve continuar 0");
        });

        teste("hasValidHash() detecta adulteração dos dados", () -> {
            Block b = new Block(1, "p", "dados originais", 5L);
            verdade(b.hasValidHash(), "antes de adulterar");
            Block adulterado = adulterarDados(b, "dados originais", "DADOS ORIGINAIS");
            verdade(!adulterado.hasValidHash(), "depois de adulterar");
            verdade(b.hasValidHash(), "o original não pode ser afetado");
        });
    }

    // ---------- ReplaceResult ----------

    static void testesReplaceResult() {
        teste("accept() e reject(motivo) preenchem os campos corretamente", () -> {
            ReplaceResult a = ReplaceResult.accept();
            ReplaceResult r = ReplaceResult.reject("CHAIN_ERROR: x");
            verdade(a.accepted(), "accept().accepted()");
            verdade(!r.accepted(), "reject().accepted()");
            igual("CHAIN_ERROR: x", r.reason(), "motivo");
            verdade(a.reason() != null && !a.reason().isEmpty(), "accept deve ter texto");
        });

        teste("ReplaceResult sobrevive à serialização", () -> {
            ReplaceResult r = (ReplaceResult) desserializar(serializar(ReplaceResult.reject("motivo")));
            igual(ReplaceResult.reject("motivo"), r, "equals do record");
        });
    }

    // ---------- Blockchain: mineração básica ----------

    static void testesMineracao() {
        teste("Cadeia nova: só o gênesis, válida, versão 0", () -> {
            Blockchain c = new Blockchain(DIF);
            igual(1, c.getSnapshot().size(), "tamanho");
            verdade(c.isChainValid(), "isChainValid");
            igual(0, c.getChainVersion(), "chainVersion");
            igual(DIF, c.getDifficulty(), "dificuldade");
        });

        teste("3 blocos minerados: SUCCESS, índices, encadeamento, PoW e versão = 3", () -> {
            Blockchain c = cadeia(DIF, 3, "B");
            List<Block> s = c.getSnapshot();
            igual(4, s.size(), "tamanho");
            igual(3, c.getChainVersion(), "chainVersion");
            igual(null, erroDe(s), "validate");
            for (int i = 1; i < s.size(); i++) {
                igual(i, s.get(i).getIndice(), "índice");
                igual(s.get(i - 1).getHash(), s.get(i).getPreviousHash(), "encadeamento do bloco " + i);
                verdade(s.get(i).getHash().startsWith("0".repeat(DIF)), "PoW do bloco " + i);
            }
            igual(s.get(3).getHash(), c.getLatestBlock().getHash(), "getLatestBlock");
        });

        teste("getSnapshot() devolve cópia: alterar a lista não altera a cadeia", () -> {
            Blockchain c = cadeia(DIF, 2, "S");
            List<Block> s = c.getSnapshot();
            s.clear();
            igual(3, c.getSnapshot().size(), "cadeia interna");
        });

        teste("Cancelamento externo: CANCELLED, cadeia e versão intactas", () -> {
            Blockchain c = new Blockchain(6);
            Blockchain.MineResult r = c.addBlock("x", () -> true);
            igual(Blockchain.MineResult.CANCELLED, r, "resultado");
            igual(1, c.getSnapshot().size(), "tamanho");
            igual(0, c.getChainVersion(), "versão");
        });
    }

    // ---------- Blockchain: validate() ----------

    static void testesValidate() {
        teste("Cadeia nula ou vazia é inválida", () -> {
            verdade(Blockchain.validate(null, DIF) != null, "null");
            verdade(Blockchain.validate(new ArrayList<>(), DIF) != null, "vazia");
        });

        teste("Bloco nulo no meio da cadeia é inválido", () -> {
            List<Block> l = new ArrayList<>();
            l.add(Blockchain.GENESIS_BLOCK);
            l.add(null);
            String e = erroDe(l);
            verdade(e != null && e.contains("nulo"), "erro: " + e);
        });

        teste("Gênesis diferente do padrão da rede é recusado", () -> {
            List<Block> l = new ArrayList<>();
            l.add(new Block(0, "0", "Outro genesis", 0L));
            String e = erroDe(l);
            verdade(e != null && e.contains("confere com o"), "erro: " + e);
        });

        teste("Índice fora de sequência é recusado", () -> {
            Block b = new Block(5, Blockchain.GENESIS_BLOCK.getHash(), "x", 7L);
            b.mine(DIF, null);
            List<Block> l = new ArrayList<>(List.of(Blockchain.GENESIS_BLOCK, b));
            String e = erroDe(l);
            verdade(e != null && e.contains("inconsistente"), "erro: " + e);
        });

        teste("Adulterar o conteúdo de um bloco sem refazer o hash é detectado", () -> {
            Blockchain c = cadeia(DIF, 3, "ADU");
            List<Block> s = c.getSnapshot();
            s.set(2, adulterarDados(s.get(2), "ADU-2", "FAL-2"));
            String e = erroDe(s);
            verdade(e != null && e.contains("adulterado"), "erro: " + e);
        });

        teste("Trocar um bloco por outro re-minerado quebra o encadeamento (previousHash)", () -> {
            Blockchain c = cadeia(DIF, 3, "FRK");
            List<Block> s = c.getSnapshot();
            Block falso = new Block(1, Blockchain.GENESIS_BLOCK.getHash(), "BLOCO FALSO", 99L);
            falso.mine(DIF, null);
            s.set(1, falso); // PoW e hash do falso são válidos, mas o bloco 2 aponta para o original
            String e = erroDe(s);
            verdade(e != null && e.contains("previousHash"), "erro: " + e);
        });

        teste("Bloco com hash correto mas sem Proof of Work é recusado", () -> {
            Block b;
            int k = 0;
            do {
                b = new Block(1, Blockchain.GENESIS_BLOCK.getHash(), "sem-pow-" + k++, 7L);
            } while (b.getHash().startsWith("0".repeat(DIF)));
            List<Block> l = new ArrayList<>(List.of(Blockchain.GENESIS_BLOCK, b));
            String e = erroDe(l);
            verdade(e != null && e.contains("Proof of Work"), "erro: " + e);
        });

        teste("Cadeia sobrevive à serialização Java (como trafega pela rede) e continua válida", () -> {
            Blockchain c = cadeia(DIF, 3, "NET");
            @SuppressWarnings("unchecked")
            List<Block> recebida = (List<Block>) desserializar(serializar(c.getSnapshot()));
            igual(null, erroDe(recebida), "validate após rede");
            igual(c.getLatestBlock().getHash(), recebida.get(recebida.size() - 1).getHash(), "mesmo hash final");
        });

        teste("Adulteração dos bytes em trânsito (dados trocados) é detectada no destino", () -> {
            Blockchain c = new Blockchain(DIF);
            c.addBlock("PAGAMENTO 10", null);
            c.addBlock("PAGAMENTO 20", null);
            byte[] bytes = serializar(c.getSnapshot());
            byte[] orig = "PAGAMENTO 10".getBytes(StandardCharsets.US_ASCII);
            byte[] novo = "PAGAMENTO 99".getBytes(StandardCharsets.US_ASCII);
            int pos = indexOf(bytes, orig);
            verdade(pos >= 0, "texto não encontrado nos bytes serializados");
            System.arraycopy(novo, 0, bytes, pos, novo.length);
            @SuppressWarnings("unchecked")
            List<Block> recebida = (List<Block>) desserializar(bytes);
            String e = erroDe(recebida);
            verdade(e != null && e.contains("adulterado"), "erro: " + e);
        });
    }

    // ---------- Blockchain: consenso ----------

    static void testesConsenso() {
        teste("Cadeia maior e válida é aceita: substitui a local e incrementa a versão", () -> {
            Blockchain local = cadeia(DIF, 1, "L");
            Blockchain remota = cadeia(DIF, 3, "R");
            int v = local.getChainVersion();
            ReplaceResult r = local.tryReplaceChain(remota.getSnapshot());
            verdade(r.accepted(), "deveria aceitar: " + r.reason());
            igual(4, local.getSnapshot().size(), "tamanho");
            igual(remota.getLatestBlock().getHash(), local.getLatestBlock().getHash(), "ponta");
            igual(v + 1, local.getChainVersion(), "versão");
            verdade(local.isChainValid(), "local válida depois da troca");
        });

        teste("Cadeia de MESMO tamanho (divergente) é rejeitada: local e versão intactas", () -> {
            Blockchain local = cadeia(DIF, 2, "A");
            Blockchain remota = cadeia(DIF, 2, "B");
            String pontaAntes = local.getLatestBlock().getHash();
            int v = local.getChainVersion();
            ReplaceResult r = local.tryReplaceChain(remota.getSnapshot());
            verdade(!r.accepted(), "deveria rejeitar");
            verdade(r.reason().startsWith("CHAIN_ERROR"), "prefixo do motivo: " + r.reason());
            igual(pontaAntes, local.getLatestBlock().getHash(), "ponta local");
            igual(v, local.getChainVersion(), "versão não pode mudar");
        });

        teste("Cadeia MENOR é rejeitada", () -> {
            Blockchain local = cadeia(DIF, 3, "A");
            Blockchain remota = cadeia(DIF, 1, "B");
            String pontaAntes = local.getLatestBlock().getHash();
            ReplaceResult r = local.tryReplaceChain(remota.getSnapshot());
            verdade(!r.accepted(), "deveria rejeitar");
            igual(pontaAntes, local.getLatestBlock().getHash(), "ponta local");
        });

        teste("Só gênesis x só gênesis (tamanho igual) é rejeitado e NÃO incrementa a versão", () -> {
            Blockchain local = new Blockchain(DIF);
            Blockchain remota = new Blockchain(DIF);
            ReplaceResult r = local.tryReplaceChain(remota.getSnapshot());
            verdade(!r.accepted(), "aceitou cadeia do mesmo tamanho: " + r.reason());
            igual(0, local.getChainVersion(), "versão (trocar por cadeia igual cancelaria mineração em andamento)");
        });

        teste("Receber de novo a mesma cadeia já aceita é rejeitado (impede loop de re-propagação)", () -> {
            Blockchain local = new Blockchain(DIF);
            Blockchain remota = cadeia(DIF, 2, "R");
            verdade(local.tryReplaceChain(remota.getSnapshot()).accepted(), "primeira vez");
            int v = local.getChainVersion();
            verdade(!local.tryReplaceChain(remota.getSnapshot()).accepted(), "segunda vez deveria rejeitar");
            igual(v, local.getChainVersion(), "versão não pode mudar");
        });

        teste("Cadeia maior porém adulterada é rejeitada e a local fica intacta", () -> {
            Blockchain local = cadeia(DIF, 1, "L");
            Blockchain remota = cadeia(DIF, 4, "R");
            List<Block> s = remota.getSnapshot();
            s.set(2, adulterarDados(s.get(2), "R-2", "X-2"));
            String pontaAntes = local.getLatestBlock().getHash();
            ReplaceResult r = local.tryReplaceChain(s);
            verdade(!r.accepted(), "deveria rejeitar");
            verdade(r.reason().startsWith("CHAIN_ERROR"), "prefixo: " + r.reason());
            igual(pontaAntes, local.getLatestBlock().getHash(), "ponta local");
            igual(2, local.getSnapshot().size(), "tamanho local");
        });

        teste("Cadeia nula ou vazia é rejeitada sem exceção", () -> {
            Blockchain local = new Blockchain(DIF);
            verdade(!local.tryReplaceChain(null).accepted(), "null");
            verdade(!local.tryReplaceChain(new ArrayList<>()).accepted(), "vazia");
        });

        teste("Dificuldade diferente entre nós: cadeia minerada com menos zeros é rejeitada", () -> {
            Blockchain fraca = cadeia(1, 4, "F");     // PoW de 1 zero
            Blockchain exigente = new Blockchain(3);  // exige 3 zeros
            ReplaceResult r = exigente.tryReplaceChain(fraca.getSnapshot());
            verdade(!r.accepted(), "deveria rejeitar (por isso DIFICULDADE precisa ser igual em todas as máquinas)");
        });

        teste("tryReplaceChain copia a lista recebida (mexer na lista depois não afeta a cadeia)", () -> {
            Blockchain local = new Blockchain(DIF);
            Blockchain remota = cadeia(DIF, 2, "R");
            List<Block> recebida = remota.getSnapshot();
            verdade(local.tryReplaceChain(recebida).accepted(), "aceitar");
            recebida.clear();
            igual(3, local.getSnapshot().size(), "cadeia local");
            verdade(local.isChainValid(), "ainda válida");
        });

        teste("Depois de adotar a cadeia remota, minerar em cima dela funciona", () -> {
            Blockchain local = new Blockchain(DIF);
            Blockchain remota = cadeia(DIF, 2, "R");
            local.tryReplaceChain(remota.getSnapshot());
            igual(Blockchain.MineResult.SUCCESS, local.addBlock("NOVO", null), "mineração");
            igual(4, local.getSnapshot().size(), "tamanho");
            verdade(local.isChainValid(), "válida");
        });
    }

    // ---------- toString ----------

    static boolean temHashCompleto(String texto) {
        return java.util.regex.Pattern.compile("[0-9a-f]{64}").matcher(texto).find();
    }

    static void testesToString() {
        teste("StringUtil.shortHash: 12 caracteres, e trata nulo/vazio/curto", () -> {
            igual("32dcea21185f", StringUtil.shortHash(GENESIS_HASH_ESPERADO), "hash longo");
            igual("0", StringUtil.shortHash("0"), "hash curto (previousHash do gênesis)");
            igual("-", StringUtil.shortHash(""), "vazio");
            igual("null", StringUtil.shortHash(null), "nulo");
        });

        teste("StringUtil.shorten: uma linha só, corta com \"...\" e respeita o limite", () -> {
            String umaLinha = StringUtil.shorten("a\nb\r\nc", 40);
            verdade(umaLinha.indexOf('\n') < 0 && umaLinha.indexOf('\r') < 0, "quebras viram espaço: " + umaLinha);
            String cortado = StringUtil.shorten("x".repeat(100), 40);
            igual(40, cortado.length(), "tamanho máximo");
            verdade(cortado.endsWith("..."), "marca de corte");
            igual("curto", StringUtil.shorten("curto", 40), "texto curto não muda");
            igual("null", StringUtil.shorten(null, 40), "nulo");
        });

        teste("Block.toString: uma linha, hash abreviado, sem o hash de 64 caracteres", () -> {
            Block b = Blockchain.GENESIS_BLOCK;
            String t = b.toString();
            verdade(t.startsWith("Block#0{"), "início: " + t);
            verdade(t.contains(StringUtil.shortHash(b.getHash())), "hash abreviado: " + t);
            verdade(!temHashCompleto(t), "não pode ter o hash completo: " + t);
            verdade(t.indexOf('\n') < 0, "uma linha só");
        });

        teste("Block.toString: dados longos e com quebra de linha continuam em uma linha curta", () -> {
            Block b = new Block(7, "p".repeat(64), "linha1\nlinha2 " + "z".repeat(200), 1L);
            String t = b.toString();
            verdade(t.indexOf('\n') < 0 && t.indexOf('\r') < 0, "sem quebra de linha: " + t);
            verdade(t.length() < 140, "curto o bastante (" + t.length() + "): " + t);
            verdade(t.contains("..."), "dados cortados: " + t);
        });

        teste("Block.toString: não quebra com data/previousHash vazios", () -> {
            String t = new Block(1, null, null, 1L).toString();
            verdade(t.contains("prev=-") && t.contains("data=\"\""), t);
        });

        teste("Blockchain.toString: cabeçalho + uma linha por bloco, sem hash completo", () -> {
            Blockchain c = cadeia(DIF, 3, "TS");
            String[] linhas = c.toString().split("\\R");
            igual(5, linhas.length, "cabeçalho + 4 blocos");
            verdade(linhas[0].contains("blocos=4") && linhas[0].contains("chainVersion=3")
                    && linhas[0].contains("difficulty=" + DIF), "cabeçalho: " + linhas[0]);
            verdade(linhas[1].contains("#0") && linhas[1].contains("Bloco Genesis"), "linha do gênesis: " + linhas[1]);
            verdade(linhas[4].contains("#3") && linhas[4].contains("TS-3"), "linha do último bloco: " + linhas[4]);
            verdade(!temHashCompleto(c.toString()), "não pode ter hash completo");
            List<Block> s = c.getSnapshot();
            for (int i = 0; i < s.size(); i++) {
                verdade(linhas[i + 1].contains(StringUtil.shortHash(s.get(i).getHash())), "hash do bloco " + i);
            }
        });

        teste("Lista de blocos (o que o NetworkMessage imprime nos logs de rede) fica sem hash completo", () -> {
            Blockchain c = cadeia(DIF, 3, "NM");
            String t = String.valueOf(c.getSnapshot());
            verdade(!temHashCompleto(t), "log de rede ainda tem hash completo");
            verdade(t.indexOf('\n') < 0, "log de rede em uma linha");
        });

        teste("ReplaceResult.toString: ACEITA/REJEITADA com o motivo", () -> {
            igual("ACEITA (Chain aceita)", ReplaceResult.accept().toString(), "aceita");
            igual("REJEITADA (CHAIN_ERROR: x)", ReplaceResult.reject("CHAIN_ERROR: x").toString(), "rejeitada");
        });
    }

    // ---------- Blockchain: concorrência e fork ----------

    static void testesConcorrencia() {
        teste("Mineração é descartada quando a cadeia muda no meio (chainVersion): bloco local não entra", () -> {
            final int dif = 4;
            Blockchain remota = cadeia(dif, 1, "REMOTO");
            Blockchain.MineResult r = null;
            Blockchain local = null;
            // Na chance de ~1/65536 o hash inicial já satisfaz o PoW e o bloco é aceito sem passar pelo
            // gancho; por isso repetimos algumas vezes.
            for (int tentativa = 0; tentativa < 5; tentativa++) {
                final Blockchain l = new Blockchain(dif);
                final boolean[] disparou = {false};
                BooleanSupplier gancho = () -> {
                    if (!disparou[0]) {
                        disparou[0] = true;
                        // outra thread (rede) troca a cadeia enquanto a mineração está em andamento
                        verdade(l.tryReplaceChain(remota.getSnapshot()).accepted(), "troca durante a mineração");
                    }
                    return false; // cancelamento externo NÃO pedido: quem cancela é o chainVersion
                };
                local = l;
                r = l.addBlock("BLOCO_LOCAL_PERDIDO", gancho);
                if (r != Blockchain.MineResult.SUCCESS) break;
            }
            igual(Blockchain.MineResult.DISCARDED_CHAIN_UPDATED, r, "resultado");
            igual(2, local.getSnapshot().size(), "tamanho (gênesis + bloco remoto)");
            igual(remota.getLatestBlock().getHash(), local.getLatestBlock().getHash(), "ponta é a remota");
            verdade(local.getSnapshot().stream().noneMatch(b -> "BLOCO_LOCAL_PERDIDO".equals(b.getData())),
                    "o bloco minerado sobre a base antiga não pode entrar na cadeia");
            verdade(local.isChainValid(), "válida");

            // recuperação documentada no Javadoc: chamar addBlock de novo re-minera sobre a nova ponta
            igual(Blockchain.MineResult.SUCCESS, local.addBlock("BLOCO_LOCAL_PERDIDO", null), "re-mineração");
            igual(3, local.getSnapshot().size(), "tamanho depois de re-minerar");
            igual(remota.getLatestBlock().getHash(), local.getSnapshot().get(1).getHash(), "bloco remoto continua no lugar");
            verdade(local.isChainValid(), "válida depois de re-minerar");
        });

        teste("Várias threads minerando ao mesmo tempo: cadeia final válida e contagem consistente", () -> {
            final Blockchain c = new Blockchain(DIF);
            final int threads = 6, porThread = 6;
            AtomicInteger sucessos = new AtomicInteger();
            AtomicInteger descartes = new AtomicInteger();
            ExecutorService ex = Executors.newFixedThreadPool(threads);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                fs.add(ex.submit(() -> {
                    for (int i = 0; i < porThread; i++) {
                        Blockchain.MineResult r = c.addBlock("T" + id + "-" + i, null);
                        if (r == Blockchain.MineResult.SUCCESS) sucessos.incrementAndGet();
                        else if (r == Blockchain.MineResult.DISCARDED_CHAIN_UPDATED) descartes.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
            ex.shutdown();

            verdade(sucessos.get() >= 1, "pelo menos uma mineração deveria ter dado certo");
            igual(1 + sucessos.get(), c.getSnapshot().size(), "tamanho = gênesis + sucessos");
            igual(sucessos.get(), c.getChainVersion(), "versão = sucessos");
            igual(null, erroDe(c.getSnapshot()), "cadeia final");
            igual(threads * porThread, sucessos.get() + descartes.get(), "toda chamada termina em SUCCESS ou DISCARDED");
        });

        teste("Mineração concorrente com troca de cadeia por outra thread mantém a cadeia válida", () -> {
            final int dif = DIF;
            final Blockchain local = new Blockchain(dif);
            final Blockchain remota = cadeia(dif, 1, "R");
            ExecutorService ex = Executors.newFixedThreadPool(2);
            Future<?> minerador = ex.submit(() -> {
                for (int i = 0; i < 30; i++) local.addBlock("M" + i, null);
            });
            Future<?> rede = ex.submit(() -> {
                for (int i = 0; i < 30; i++) {
                    // cresce a cadeia remota e tenta impor à local, como faria o recvBlockchainCopy
                    remota.addBlock("R" + i, null);
                    local.tryReplaceChain(remota.getSnapshot());
                }
            });
            minerador.get(60, TimeUnit.SECONDS);
            rede.get(60, TimeUnit.SECONDS);
            ex.shutdown();
            igual(null, erroDe(local.getSnapshot()), "cadeia local depois da corrida");
        });
    }
}