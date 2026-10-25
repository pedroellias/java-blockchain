import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;

public final class Peer {
    public static final int MAX_PEERS = 5;
    public static final String SEED_IP = "127.0.0.1";
    public static final int SEED_PORTA = 6767;
    public static final String SEED_PROTOCOL_VERSION = "V1.0";
    // MUDAR ESSA VARIÁVEL PARA CADA COMPUTADOR NA APRESENTAÇÃO
    public static final String IP_PADRÃO = "127.0.0.1";

    public static final int DIFICULDADE = 2;

    private String name;
    private String protocolVersion;
    private Set<String> knownPeers;
    // private List<String> knownPeers;
    private Map<String, PeerConnection> connections;
    private String ip;
    private int port;
    private ServerSocket server;
    private Server listener;

    private volatile boolean connected;

    private Blockchain blockchain;

    private Peer(String name, String protocolVersion, String ip, int port) throws IOException {
        this.name = name;
        this.protocolVersion = protocolVersion;
        this.knownPeers = new HashSet<>();
        // this.knownPeers = new ArrayList<>(MAX_PEERS);
        this.connections = new Hashtable<>();
        
        this.server = new ServerSocket(port, 10, InetAddress.getByName(ip));
        this.ip = server.getInetAddress().getHostAddress();
        this.port = server.getLocalPort();

        this.addKnownPeer(SEED_IP, SEED_PORTA);
        this.addKnownPeer(this.ip, this.port);

        this.listener = new Server(this);

        this.connected = true;

        this.blockchain = null;
    }

    public Peer(String name, String protocolVersion) throws IOException {
        this(name, protocolVersion, IP_PADRÃO,0);
    }
    
    public Peer() throws IOException {
        this("seed", SEED_PROTOCOL_VERSION, SEED_IP, SEED_PORTA);
        // TODO: pode não precisar dessa linha se a ideia de usar sets em vez de list funcionar
        // knownPeers.removeLast();
        this.log("Known peers: " + knownPeers);
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
            int port = Integer.parseInt(split[2]);
            this.addKnownPeer(ip, port);
            this.addConnection(ip, port, remetente);
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
        // TODO: testando Set em vez de list para knownPeers, trocar tipo dessa linha se não funcionar
        Set<String> payload = (Set<String>) peersList.getPayload();
        
        for (String peer : payload) {
            if (this.knownPeers.contains(peer)) continue;

            String[] split = peer.split(":");
            String ip = split[0];
            int port = Integer.parseInt(split[1]);
            
            this.addKnownPeer(ip, port);
            this.conectar(ip, port);
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

    public NetworkMessage msgHello() {
        return new NetworkMessage(MessageType.HELLO, protocolVersion + "-" + name + "-" + port);
    }
    
    public NetworkMessage msgGetPeers() {
        return new NetworkMessage(MessageType.GET_PEERS, null);
    }

    public final synchronized void addKnownPeer(String ip, int port) {
        String id = ip + ":" + port;
        this.knownPeers.add(id);
    }

    public final synchronized void addConnection(String ip, int port, PeerConnection con) {
        String id = ip + ":" + port;
        this.connections.put(id, con);
    }
    
    public final synchronized void conectar(String ip, int port) {
        String id = ip + ":" + port;
        try {
            Socket newPeer = new Socket(ip, port);
            PeerConnection con = new PeerConnection(this, newPeer);
            con.enviar(msgHello());
            this.connections.put(id, con);

            con.enviar(msgGetPeers()); 
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
        Peer A;
        if (args.length == 0) {
            A = new Peer();
        } else {
            A = new Peer(args[0], "V1.0");
            A.conectar(SEED_IP, SEED_PORTA);
        }
        Thread.sleep(100000);
        A.close();
        Thread.sleep(2000);
        System.out.println("knowPeers de A: " + A.knownPeers);
        System.out.println("connections de A: " + A.connections);
    }
}
