import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class StringUtil {

    public static String applySha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < hash.length; i++) {
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    /**
     * Primeiros 12 caracteres de um hash, só para exibição em logs ("-" se vazio).
     * Nunca use isto para comparar hashes: a comparação sempre usa o hash completo.
     */
    public static String shortHash(String hash) {
        if (hash == null) return "null";
        if (hash.isEmpty()) return "-";
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    /** Texto em uma linha só, cortado em no máximo 'max' caracteres (termina em "..." se foi cortado). */
    public static String shorten(String text, int max) {
        if (text == null) return "null";
        String umaLinha = text.replace('\r', ' ').replace('\n', ' ');
        if (umaLinha.length() <= max) return umaLinha;
        return umaLinha.substring(0, Math.max(0, max - 3)) + "...";
    }
// esse string util veio do PDF que o alexandre mandou pra gente
}

