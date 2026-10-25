import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Server implements Runnable {
    Peer parent;

    public Server(Peer parent) {
        this.parent = parent;
        
        new Thread(this, "SERVER_THREAD").start();
    }
    
    @Override
    public void run() {
        parent.log("Ouvindo novas conexões...");
        ServerSocket server = parent.getServerSocket();
        try {
            while (parent.isConnected()) {
                Socket cliente = server.accept();
                new PeerConnection(parent, cliente);
            }
        } catch (IOException e) {
            if (parent.isConnected()) e.printStackTrace();
        }
        parent.log("Parando de ouvir novas conexões.");
    }
}