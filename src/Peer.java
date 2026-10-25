import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Hashtable;

public final class Peer {
    public static final int MAX_PEERS = 5;
    public static final String SEED_IP = "127.0.0.1";
    public static final int SEED_PORTA = 6767;
    public static final String SEED_PROTOCOL_VERSION = "V1.0";

    public static final int DIFICULDADE = 2;

    private String name;
    private String protocolVersion;
    private List<String> knownPeers;
    private Map<String, PeerConnection> connections;
    private String ip;
    private int port;
    private ServerSocket server;
    private Server listener;

    private volatile boolean connected;

    private Blockchain blockchain;

    private Peer(String name, String protocolVersion, int port) throws IOException {
        this.name = name;
        this.protocolVersion = protocolVersion;
        this.knownPeers = new ArrayList<>(MAX_PEERS);
        this.connections = new Hashtable<>();
        
        this.server = new ServerSocket(port);
        this.ip = server.getInetAddress().getHostAddress();
        this.port = server.getLocalPort();

        this.addKnownPeer(SEED_IP, SEED_PORTA);
        this.addKnownPeer(this.ip, this.port);

        this.listener = new Server(this);

        this.connected = true;

        this.blockchain = null;
    }

    public Peer(String name, String protocolVersion) throws IOException {
        this(name, protocolVersion, 0);
    }
    
    public Peer() throws IOException {
        this("seed", SEED_PROTOCOL_VERSION, SEED_PORTA);
        knownPeers.removeLast();
        this.blockchain = new Blockchain(DIFICULDADE);
    }
    
    public synchronized void recv(PeerConnection remetente, byte[] bytes) {
        NetworkMessage msg = NetworkMessage.desserializar(bytes);
        switch (msg.getType()) {
            case HELLO: {
                recvHello(remetente, msg);
                break;
            }
            case GET_PEERS: {
                recvGetPeers(remetente, msg);
                break;
            }
            case PEERS_LIST: {
                recvPeersList(remetente, msg);
                break;
            }
            case GET_BLOCKCHAIN: {
                recvGetBlockchain(remetente, msg);
                break;
            }
            case BLOCKCHAIN_COPY: {
                recvBlockchainCopy(remetente, msg);
                break;
            }
            case ACK: {
                recvAck(remetente, msg);
                break;
            }
            case REJECT: {
                recvReject(remetente, msg);
                break;
            }
            default: {
                System.err.println("UNREACHABLE");
                System.exit(-1);
            }
        }
    }

    public void recvHello(PeerConnection remetente, NetworkMessage hello) {
        String payload = (String) hello.getPayload();
        String[] split = payload.split("-");

        if (split[0].equals(this.protocolVersion)) {
            String ip = remetente.getSocket().getInetAddress().getHostAddress();
            int port = remetente.getSocket().getPort();
            this.addKnownPeer(ip, port);
            remetente.enviar(new NetworkMessage(MessageType.ACK, null));
        } else {
            remetente.enviar(new NetworkMessage(MessageType.REJECT, "Versão de protocolo não coincide com a versão atual (" + protocolVersion + ")"));
        }
    }

    public void recvGetPeers(PeerConnection remetente, NetworkMessage getPeers) {
        remetente.enviar(new NetworkMessage(MessageType.PEERS_LIST, this.knownPeers));
    }


    // Não haverá mensagem resposta ACK por parte do recebedor de PEERS_LIST
    // decisão de troca de confiança <-> desempenho
    public void recvPeersList(PeerConnection remetente, NetworkMessage peersList) {
        @SuppressWarnings("unchecked")
        List<String> payload = (List<String>) peersList.getPayload();
        
        for (String peer : payload) {
            if (this.knownPeers.contains(peer)) continue;

            String[] split = peer.split(":");
            String ip = split[0];
            int port = Integer.parseInt(split[1]);
            
            // TODO: verificar se isso aqui é necessário (ou se o primeiro if já cobre, oq eu acho que sim)
            if (ip.equals(this.ip) && port == this.port) continue;
            
            this.addKnownPeer(ip, port);
        }
    }
    
    public void recvGetBlockchain(PeerConnection remetente, NetworkMessage getBlockchain) {
        remetente.enviar(new NetworkMessage(MessageType.BLOCKCHAIN_COPY, this.blockchain.getSnapshot()));
    }
    
    public void recvBlockchainCopy(PeerConnection remetente, NetworkMessage blockchainCopy) {
        @SuppressWarnings("unchecked")
        List<Block> payload = (List<Block>) blockchainCopy.getPayload();
        
        if (this.blockchain == null) {
            this.blockchain = new Blockchain(DIFICULDADE, payload);
            return;
        }
        
        ReplaceResult troca = this.blockchain.tryReplaceChain(payload);
        if (!troca.accepted()) {
            remetente.enviar(new NetworkMessage(MessageType.REJECT, troca.reason()));
        }
    }
    
    public void recvAck(PeerConnection remetente, NetworkMessage ack) {
        return;
    }
    
    public void recvReject(PeerConnection remetente, NetworkMessage reject) {
        return;
    }

    public NetworkMessage sendHello() {
        return new NetworkMessage(MessageType.HELLO, protocolVersion + "-" + name);
    }

    public final synchronized void addKnownPeer(String ip, int port) {
        String id = ip + ":" + port;
        this.knownPeers.add(id);
    }
    
    // TODO: TIRAR O RETORNO DO PEER CONNECTION, É APENAS PARA TESTE
    public final synchronized void conectar(String ip, int port) {
        String id = ip + ":" + port;
        try {
            Socket newPeer = new Socket(ip, port);
            PeerConnection con = new PeerConnection(this, newPeer);
            con.enviar(sendHello());
            this.connections.put(id, con);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void close() throws IOException {
        this.log("Encerrando Peer...");
        this.connected = false;
        this.server.close();
        for (PeerConnection s : connections.values()) {
            s.getSocket().close();
        }
    }

    public void log(String s) {
        System.out.println("[" + this.name + "@" + ip + ":" + port + "] " + s);
    }
    
    public String getName() {
        return name;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public Blockchain getBlockchain() {
        return this.blockchain;
    }
    
    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    public ServerSocket getServerSocket() {
        return server;
    }

    public boolean isConnected() {
        return connected;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        
        if (args.length == 0) {
            Peer A = new Peer("A", "V1.0");
            A.conectar(SEED_IP, SEED_PORTA);
            Thread.sleep(6000);
            A.close();
            Thread.sleep(6000);
            System.out.println("knowPeers de A: " + A.knownPeers);
        } else {
            Peer seed = new Peer();
            Thread.sleep(6000);
            seed.close();
            Thread.sleep(6000);
            System.out.println("knowPeers de seed: " + seed.knownPeers);
        }
    }
}
