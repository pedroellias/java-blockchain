import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public final class Server implements Runnable {
    private Peer parent;
    private ServerSocket server;

    public Server(Peer parent) {
        this.parent = parent;
        this.server = parent.getServerSocket();
        
        new Thread(this, "SERVER_THREAD").start();
    }
    
    @Override
    public void run() {
        parent.log("Ouvindo novas conexões na porta: " + parent.getPort());
        
        try {
            while (parent.isConnected()) {
                Socket newPeer = server.accept();
                new PeerConnection(parent, newPeer);
            }
        } catch (IOException e) {
            if (parent.isConnected()) e.printStackTrace();
        }
        parent.log("Parando de ouvir novas conexões.");
    }
}