import java.io.Serializable;

public record ReplaceResult(boolean accepted, String reason) implements Serializable {

    public static ReplaceResult accept() {
        return new ReplaceResult(true, "Chain aceita");
    }

    public static ReplaceResult reject(String reason) {
        return new ReplaceResult(false, reason);
    }
}