import java.io.IOException;
import java.util.Scanner;

public class Aplicativo {
    // MUDAR ESSA VARIÁVEL PARA CADA COMPUTADOR NA APRESENTAÇÃO
    private static final String IP_LOCAL = "127.0.0.1";

    private static final String IP_SEED = "127.0.0.1";
    private static final int PORTA_SEED = 6767;
    public static void main(String[] args) throws IOException {
        Scanner teclado = new Scanner(System.in);
        System.out.print("Digite o nome desse peer: ");
        String nome = teclado.nextLine();

        System.out.print("Deseja ser um peer seed (ouvirá conexões em " + Peer.ipPortToId(IP_SEED, PORTA_SEED) + ")? (s/n) ");
        boolean peerSeed = teclado.nextLine().equals("s");
        
        Peer p;
        if (!peerSeed) {
            p = new Peer(nome, "V1.0", IP_LOCAL, IP_SEED, PORTA_SEED);
            p.conectar(IP_SEED, PORTA_SEED);
        } else {
            p = new Peer(nome, "V1.0", IP_SEED, PORTA_SEED);
        }
        
        System.out.println("=====================================================");
        System.out.println("DIGITE O NÚMERO PARA VER A INFORMAÇÃO CORRESPONDENTE:");
        System.out.println("[1] - VER CONEXÕES");
        System.out.println("[2] - VER BLOCKCHAIN");
        System.out.println("[3] - MINERAR BLOCO");
        System.out.println("[0] - DESCONECTAR ");
        System.out.println("=====================================================\n");

        try (Scanner s = new Scanner(System.in)) {
            while (s.hasNextLine()) {
                String linha = s.nextLine();
                if (linha.equals("1")) {
                    p.logKnownPeers();
                } else if (linha.equals("2")) {
                    p.logBlockchain();
                } else if (linha.equals("3")) {
                    System.out.print("Digite o conteúdo do novo bloco: ");
                    String data = teclado.nextLine();
                    p.minerar(data);
                } else if (linha.equals("0")) {
                    break;
                }
            }
        }
        p.close();

        teclado.close();
    }
}
