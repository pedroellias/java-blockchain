import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Base64;

class PeerConnection implements Runnable {
    private Peer parent;
    private Socket socket;
    private String socketId;
    private BufferedReader in;
    private PrintWriter out;

    public PeerConnection(Peer parent, Socket socket) throws IOException {
        this.parent = parent;
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.out = new PrintWriter(socket.getOutputStream(), true);
        this.socketId = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();

        new Thread(this, "PeerConnection").start();
    }

    public void enviar(NetworkMessage msg) {
        String msgCodificada = Base64.getEncoder().encodeToString(msg.serializar());
        out.println(msgCodificada);
    }
    
    @Override
    public void run() {
        parent.log("Nova conexão com: " + socketId);
        try {
            
            while (parent.isConnected()) {
                String s = in.readLine();
                if (s != null) {
                    byte[] dados = Base64.getDecoder().decode(s);
                    parent.log("Recebido: " + NetworkMessage.desserializar(dados) + " de " + socketId);
                    parent.recv(this, dados);
                }
            }
        } catch (IOException e) {
            if (parent.isConnected()) e.printStackTrace();
        }
        parent.log("Encerrando conexão com: " + socket.getInetAddress().getHostAddress() + ":" + socket.getLocalPort());
    }

    public Socket getSocket() {
        return socket;
    }
}
