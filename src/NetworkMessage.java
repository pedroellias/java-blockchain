import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class NetworkMessage implements Serializable {
    private static final long serialVersionUID = 13L;
    
    private MessageType type;
    @SuppressWarnings("serial")
    private Object payload;

    public NetworkMessage(MessageType type, Object payload) {
        this.type = type;
        this.payload = payload;
    }

    public byte[] serializar() {
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream(); ObjectOutputStream serializador = new ObjectOutputStream(stream)) {
            serializador.writeObject(this);
            return stream.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
    
    public static NetworkMessage desserializar(byte[] bytes) {
        if (bytes == null) return null;

        try (ByteArrayInputStream stream = new ByteArrayInputStream(bytes); ObjectInputStream desserializador = new ObjectInputStream(stream)) {
            NetworkMessage m = (NetworkMessage) desserializador.readObject();
            return m;
        } catch (IOException | ClassNotFoundException e) {
            return null;
        }
    }

    public MessageType getType() {
        return type;
    }

    public Object getPayload() {
        return payload;
    }

    @Override
    public String toString() {
        return "NetworkMessage [type=" + type + ", payload=" + payload + "]";
    }

    public static void main(String[] args) {
        NetworkMessage m = new NetworkMessage(MessageType.HELLO, "oieee...");

        var serializado = m.serializar();
        var desserializado = NetworkMessage.desserializar(serializado);

        System.out.println("m original: " + m);
        System.out.println("m desserializado: " + desserializado);
    }
}
