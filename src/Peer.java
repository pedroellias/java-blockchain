import java.util.List;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;

public final class Peer {
    public static final int MAX_PEERS = 5;
    public static final String SEED_IP = "127.0.0.1";
    public static final int SEED_PORTA = 6767;
    public static final String SEED_PROTOCOL_VERSION = "V1.0";

    public static final int DIFICULDADE = 2;

    private String name;
    private String protocolVersion;
    private List<String> knownPeers;
    private String ip;
    private int port;
    private ServerSocket server;

    private Blockchain blockchain;

    public Peer(String name, String protocolVersion) throws IOException {
        this.name = name;
        this.protocolVersion = protocolVersion;
        this.knownPeers = new ArrayList<>(MAX_PEERS);
        
        this.server = new ServerSocket(0);
        this.port = server.getLocalPort();
        this.ip = server.getInetAddress().getHostAddress();

        this.addKnownPeer(SEED_IP, SEED_PORTA);
        this.addKnownPeer(this.ip, this.port);

        this.blockchain = new Blockchain(DIFICULDADE);
    }
    
    public Peer() throws IOException {
        this.name = "NodeSEED";
        this.protocolVersion = SEED_PROTOCOL_VERSION;
        this.knownPeers = new ArrayList<>(MAX_PEERS);
        
        this.server = new ServerSocket(SEED_PORTA);
        this.ip = SEED_IP;
        this.port = SEED_PORTA;

        this.addKnownPeer(SEED_IP, SEED_PORTA);
    }

    // TODO: enviar a mensagem de verdade
    public NetworkMessage sendHello() {
        NetworkMessage m = new NetworkMessage(MessageType.HELLO, protocolVersion + "-" + name + "-" + port);
        System.out.println("[" + name + "] enviou: " + m);
        return m;
    }

    // TODO: imagino que ao ler um hello e verificar que o protocolo é igual, o peer destinatário tem que registrar o peer remetente na lista de peers conhecidos, e então enviar uma mensagem ACK. Se houver falha, envia REJECT com mensagem de erro (ex: Versão de protocolo não coincide).
    // adicionei o numero da porta como parte do payload de enviar mensagem para tentar resolver isso
    // o IP será obtido através do socket (quando eu implementar a conexão de rede)
    public NetworkMessage recvHello(NetworkMessage hello) {
        String payload = (String) hello.getPayload();
        String[] split = payload.split("-");
        if (split[0].equals(this.protocolVersion)) {
            // TODO: adiciona o ip:porta em vez do nome:porta (vai vir do socket)
            this.addKnownPeer(split[1], Integer.parseInt(split[2]));
            System.out.println("[" + name + "] recebeu hello e registrou: " + split[1] + ":" + split[2]);
            return new NetworkMessage(MessageType.ACK, null);
        } else {
            return new NetworkMessage(MessageType.REJECT, "Versão de protocolo não coincide com a versão atual (" + protocolVersion + ")");
        }
    }

    public NetworkMessage sendGetPeers() {
        NetworkMessage m = new NetworkMessage(MessageType.GET_PEERS, null);
        System.out.println("[" + name + "] enviou: " + m);
        return m;
    }
    
    // TODO: devo separar isso na função "sendPeersList() ou, por ela só acontecer em resposta à um recvGetPeers(), isso nao é necessário? 
    // no caso, como o PEERS_LIST é exclusivamente uma resposta de GET_PEERS, separar as funções talvez não seja necessário."
    public NetworkMessage recvGetPeers(NetworkMessage getPeers) {
        return sendPeersList();
    }

    public NetworkMessage sendPeersList() {
        NetworkMessage m = new NetworkMessage(MessageType.PEERS_LIST, this.knownPeers);
        System.out.println("[" + name + "] enviou: " + m);
        return m;
    }

    // Não haverá mensagem resposta ACK por parte do recebedor de PEERS_LIST
    // decisão de troca de confiança <-> desempenho
    public void recvPeersList(NetworkMessage peersList) {
        @SuppressWarnings("unchecked")
        List<String> payload = (List<String>) peersList.getPayload();
        
        for (String peer : payload) {
            if (this.knownPeers.contains(peer)) continue;

            String[] split = peer.split(":");
            String ip = split[0];
            int port = Integer.parseInt(split[1]);
            
            if (ip.equals(this.ip) && port == this.port) continue;
            
            // TODO: abrir conexão com peer novo além de só adicioná-lo na lista.
            this.addKnownPeer(ip, port);
        }
    }
    
    
    public NetworkMessage sendGetBlockchain() {
        NetworkMessage m = new NetworkMessage(MessageType.GET_BLOCKCHAIN, null);
        System.out.println("[" + name + "] enviou: " + m);
        return m;
    }
    
    public NetworkMessage recvGetBlockchain(NetworkMessage getBlockchain) {
        return sendBlockchainCopy();
    }
    
    public NetworkMessage sendBlockchainCopy() {
        NetworkMessage m = new NetworkMessage(MessageType.GET_BLOCKCHAIN, this.blockchain.getSnapshot());
        System.out.println("[" + name + "] enviou: " + m);
        return m;
    }
    
    public void recvBlockchainCopy(NetworkMessage blockchainCopy) {
        @SuppressWarnings("unchecked")
        List<Block> payload = (List<Block>) blockchainCopy.getPayload();

        // TODO: a função abaixo vai virar tryAndReplaceChain(), e irá trocar lá dentro, ao invés de só verificar
        ReplaceResult troca = this.blockchain.tryReplaceChain(payload);
        if (troca.accepted()) {
            // TODO: algo como a linha abaixo, para realmente trocar a lista. darwin vai encapsular no Blockchain
            // this.blockchain.chain = payload; 
        } else {
            new NetworkMessage(MessageType.REJECT, troca.reason());
        }

    }

    public final synchronized void addKnownPeer(String ip, int port) {
        // TODO: conexão com o socket novo é feita aqui ou separado?
        this.knownPeers.add(ip + ":" + port);
    }
    
    public String getName() {
        return name;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }
    
    public static void main(String[] args) throws IOException {
        Peer B = new Peer();
        Peer A = new Peer("A", "V1.0");
        
        System.out.println(B.recvHello(A.sendHello()));

        NetworkMessage m = B.recvGetPeers(A.sendGetPeers());
        System.out.println(m);

        A.recvPeersList(m);
        System.out.println("lista de A: " + A.knownPeers);
    }
}
