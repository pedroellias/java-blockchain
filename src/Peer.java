import java.util.List;
import java.util.Map;
import java.util.Set;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;

public final class Peer {
    public final String seedIp;
    public final int seedPorta;

    public static final int DIFICULDADE = 5;

    private String name;
    private String protocolVersion;
    private Set<String> knownPeers;
    private Map<PeerConnection, String> connections;
    private String ip;
    private int port;
    private ServerSocket server;

    private volatile boolean connected;

    private Blockchain blockchain;

    private Peer(String name, String protocolVersion, String localIp, int localPort, String seedIp, int seedPorta) throws IOException {
        this.name = name;
        this.protocolVersion = protocolVersion;
        this.knownPeers = new HashSet<>();
        this.connections = new Hashtable<>();

        this.seedIp = seedIp;
        this.seedPorta = seedPorta;
        
        this.server = new ServerSocket(localPort, 10, InetAddress.getByName(localIp));
        this.ip = server.getInetAddress().getHostAddress();
        this.port = server.getLocalPort();

        this.addKnownPeer(seedIp, seedPorta);
        this.addKnownPeer(this.ip, this.port);

        this.connected = true;
        this.blockchain = new Blockchain(DIFICULDADE);

        new Server(this);
    }

    public Peer(String name, String protocolVersion, String localIp, String seedIp, int seedPorta) throws IOException {
        this(name, protocolVersion, localIp, 0, seedIp, seedPorta);
    }
    
    public Peer(String name, String protocolVersion, String seedIp, int seedPorta) throws IOException {
        this(name, protocolVersion, seedIp, seedPorta, seedIp, seedPorta);
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
            remetente.enviar(new NetworkMessage(MessageType.REJECT, "CONNECTION_ERROR: Peer não reconhecido."));
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
            remetente.enviar(new NetworkMessage(MessageType.REJECT, "CONNECTION_ERROR: Nome inválido (não pode conter \"-\")"));
            return;
        }
        if (!split[0].equals(this.protocolVersion)) {
            remetente.enviar(new NetworkMessage(MessageType.REJECT, "CONNECTION_ERROR: Versão de protocolo não coincide com a versão atual (" + protocolVersion + ")"));
            return;
        }

        String ip = remetente.getSocket().getInetAddress().getHostAddress();
        int port = Integer.parseInt(split[2]);
        this.addKnownPeer(ip, port);
        this.addConnection(ip, port, remetente);
        remetente.setAcknowledged();
        remetente.setConnectionId(ipPortToId(ip, port));
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

        broadcastMsg(new NetworkMessage(MessageType.GET_BLOCKCHAIN, null));
    }
    
    public void recvGetBlockchain(PeerConnection remetente, NetworkMessage getBlockchain) {
        remetente.enviar(new NetworkMessage(MessageType.BLOCKCHAIN_COPY, this.blockchain.getSnapshot()));
    }
    
    public void recvBlockchainCopy(PeerConnection remetente, NetworkMessage blockchainCopy) {
        @SuppressWarnings("unchecked")
        List<Block> payload = (List<Block>) blockchainCopy.getPayload();
        
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
        String payload = (String) reject.getPayload();
        // prefixamos todo payload de mensagens do tipo REJECT com "CONNECTION_ERROR" ou "CHAIN_ERROR" para permitir a tomada de decisão se o recebimento de um reject deve encerrar a conexão ou não
        if (payload.startsWith("CONNECTION_ERROR")) {
            desconectar(remetente);
        } else if (payload.startsWith("CHAIN_ERROR")) {
            log("Rejeitada blockchain de " + connections.get(remetente) + " pois: " + payload.substring("CHAIN_ERROR: ".length()));
        }
        
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
            con.setConnectionId(ipPortToId(ip, port));
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

    private void broadcastMsg(NetworkMessage  msg) {
        for (PeerConnection con : this.connections.keySet()) {
            con.enviar(msg);
        }
    }
    
    public void minerar(String data) {
        log("Minerando bloco...");
        LocalTime inicio = LocalTime.now();
        this.blockchain.addBlock(data, null);
        LocalTime fim = LocalTime.now();
        int s = fim.getSecond() - inicio.getSecond();
        int ms = fim.getNano() - inicio.getNano();
        if (ms < 0) ms += 1000_000_000;
        log("Bloco minerado (" + s + "." + ms + " segundos)");

        broadcastMsg(new NetworkMessage(MessageType.BLOCKCHAIN_COPY, this.blockchain.getSnapshot()));
    }

    public void log(String s) {
        System.out.println("[" + this.name + "@" + ip + ":" + port + "] " + s);
    }

    public void logKnownPeers() {
        StringBuilder s = new StringBuilder("Peers conhecidos: \n\t| IP              | PORTA |\n");
        for (String peer : this.knownPeers) {
            String ip = idToIp(peer);
            int port = idToPort(peer);
            s.append("\t| " + ip + " ".repeat(15 - ip.length()) + " | " + port + " ".repeat(5 - String.valueOf(port).length()) + " |\n");
        }
        s.append("\n");
        log(s.toString());
    }
    
    public void logBlockchain() {
        log(this.blockchain.toString());
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


}
