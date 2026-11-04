import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Base64;

class PeerConnection implements Runnable {
    private Peer parent;
    private Socket socket;
    private final String socketId; // usado pelo hashCode(), deve ser imutável
    private String connectionId; // vai receber a porta correta em Peer.recvHello(), apenas para imprimir nos logs
    private BufferedReader in;
    private PrintWriter out;
    private boolean acknowledged;
    
    public PeerConnection(Peer parent, Socket socket) throws IOException {
        this.parent = parent;
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.out = new PrintWriter(socket.getOutputStream(), true);
        this.socketId = Peer.ipPortToId(socket.getInetAddress().getHostAddress(), socket.getPort());
        this.connectionId = socket.getInetAddress().getHostAddress() + ":porta_desconhecida" ;
        this.acknowledged = false;
        
        new Thread(this, "PeerConnection").start();
    }
    
    public void enviar(NetworkMessage msg) {
        String msgCodificada = Base64.getEncoder().encodeToString(msg.serializar());
        parent.log("===> [" + connectionId + "]: " + msg);
        out.println(msgCodificada);
    }
    
    @Override
    public void run() {
        parent.log("Nova conexão com: " + connectionId);
        try {
            while (parent.isConnected()) {
                String s = in.readLine();
                if (s == null) break;
                
                byte[] dados = Base64.getDecoder().decode(s);
                NetworkMessage msg = NetworkMessage.desserializar(dados);
                parent.log("<=== [" + connectionId + "]: " + msg);
                parent.recv(this, msg);
            }
        } catch (IOException e) {
            if (parent.isConnected() && this.isAcknowledged()) e.printStackTrace();
        }
        if (parent.isConnected()) {
            parent.desconectar(this);
        }
        parent.log("Encerrando conexão com: " + connectionId);
    }

    public Socket getSocket() {
        return socket;
    }

    public synchronized void setAcknowledged() {
        this.acknowledged = true;
    }

    public boolean isAcknowledged() {
        return acknowledged;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((socketId == null) ? 0 : socketId.hashCode());
        return result;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        PeerConnection other = (PeerConnection) obj;
        if (socketId == null) {
            if (other.socketId != null)
                return false;
        } else if (!socketId.equals(other.socketId))
            return false;
        return true;
    }
}
