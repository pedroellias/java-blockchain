import java.util.List;
import java.util.Map;
import java.util.Scanner;
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
    private Map<PeerConnection, String> connections;
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

        this.connected = true;
        this.blockchain = null;

        this.listener = new Server(this);
    }

    public Peer(String name, String protocolVersion) throws IOException {
        this(name, protocolVersion, IP_PADRÃO,0);
    }
    
    public Peer() throws IOException {
        this("seed", SEED_PROTOCOL_VERSION, SEED_IP, SEED_PORTA);
        // TODO: pode não precisar dessa linha se a ideia de usar sets em vez de list funcionar
        // knownPeers.removeLast();
        this.blockchain = new Blockchain(DIFICULDADE);
    }
    
    @SuppressWarnings("incomplete-switch")
    public synchronized void recv(PeerConnection remetente, NetworkMessage msg) {
        if (msg == null) {
            log("Mensagem inválida recebida. Ignorando.");
            return;
        }

        switch (msg.getType()) {
            case HELLO: {
                recvHello(remetente, msg);
                return;
            }
            case ACK: {
                recvAck(remetente, msg);
                return;
            }
            case REJECT: {
                recvReject(remetente, msg);
                return;
            }
        }

        if (!remetente.isAcknowledged()) {
            remetente.enviar(new NetworkMessage(MessageType.REJECT, "Peer não reconhecido."));
            return;
        }

        switch (msg.getType()) {
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
        }
    }

    public void recvHello(PeerConnection remetente, NetworkMessage hello) {
        String payload = (String) hello.getPayload();
        String[] split = payload.split("-");

        if (split.length != 3) {
            remetente.enviar(new NetworkMessage(MessageType.REJECT, "Nome inválido (não pode conter \"-\")"));
            return;
        }
        if (!split[0].equals(this.protocolVersion)) {
            remetente.enviar(new NetworkMessage(MessageType.REJECT, "Versão de protocolo não coincide com a versão atual (" + protocolVersion + ")"));
            return;
        }

        String ip = remetente.getSocket().getInetAddress().getHostAddress();
        int port = Integer.parseInt(split[2]);
        this.addKnownPeer(ip, port);
        this.addConnection(ip, port, remetente);
        remetente.setAcknowledged();
        remetente.enviar(new NetworkMessage(MessageType.ACK, null));
    }

    public void recvGetPeers(PeerConnection remetente, NetworkMessage getPeers) {
        remetente.enviar(new NetworkMessage(MessageType.PEERS_LIST, new ArrayList<>(this.knownPeers)));
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
        remetente.setAcknowledged();
        remetente.enviar(msgGetPeers()); 
        return;
    }
    
    public void recvReject(PeerConnection remetente, NetworkMessage reject) {
        // TODO: será que to REJECT deve fechar a conexão? em resposta à uma recusa de HELLO sim, mas o REJECT também pode acontecer em uma falha de validação de blockchain, e não sei se é a melhor estratégia.
        // PROBLEMA, não tem muito como verificar qual a razão da recusa aqui sem ter estabelecido um protocolo de motivos de REJECT, atualmente é só uma string arbitrária. seria uma extensão às instruções do trabalho
        desconectar(remetente);
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
        this.connections.put(con, id);
    }
    
    public synchronized void removeKnownPeer(String ip, int port) {
        String id = ipPortToId(ip, port);
        this.knownPeers.remove(id);
    }

    public final synchronized void removeConnection(PeerConnection con) {
        this.connections.remove(con);
    }
    
    public final synchronized void conectar(String ip, int port) {
        try {
            Socket newPeer = new Socket(ip, port);
            PeerConnection con = new PeerConnection(this, newPeer);
            con.enviar(msgHello());
            this.addConnection(ip, port, con);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public synchronized void desconectar(PeerConnection con) {
        String id = this.connections.get(con);

        // a connection já foi removida do mapa, ignorar
        if (id == null) return;

        try {
            con.getSocket().close();
            removeKnownPeer(idToIp(id), idToPort(id));
            removeConnection(con);
        } catch (IOException e) {
            log("Falha ao desconectar com " + id);
        }
    }
    
    public synchronized void close() throws IOException {
        this.log("Encerrando Peer...");
        this.connected = false;
        this.server.close();
        for (PeerConnection con : new Hashtable<PeerConnection, String>(connections).keySet()) {
            desconectar(con);
        }
    }

    public void log(String s) {
        System.out.println("[" + this.name + "@" + ip + ":" + port + "] " + s);
    }

    public static String ipPortToId(String ip, int port) {
        return ip + ":" + port;
    }

    public static String idToIp(String id) {
        return id.split(":")[0];
    }

    public static int idToPort(String id) {
        return Integer.parseInt(id.split(":")[1]);
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
        System.out.println("=====================================================");
        System.out.println("DIGITE O NÚMERO PARA VER A INFORMAÇÃO CORRESPONDENTE:");
        System.out.println("[1] - VER CONEXÕES  [0] - DESCONECTAR ");
        System.out.println("=====================================================\n");

        Peer A;
        if (args.length == 0) {
            A = new Peer();
        } else {
            A = new Peer(args[0], "V1.0");
            A.conectar(SEED_IP, SEED_PORTA);
        }
        try (Scanner s = new Scanner(System.in)) {
            while (s.hasNextLine()) {
                String linha = s.nextLine();
                if (linha.equals("1")) {
                    System.out.println("LISTA DE PEERS: " + A.knownPeers);
                    System.out.println("      CONEXÕES: " + A.connections);
                    System.out.println("");
                } else if (linha.equals("0")) {
                    break;
                }
            }
        }
        A.close();
        Thread.sleep(2000);
    }
}
