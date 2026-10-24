public enum MessageType {
    HELLO,           // Mensagem inicial de handshake e apresentação.
    GET_PEERS,       // Solicitação de lista de endereços de peers conhecidos.
    PEERS_LIST,      // Resposta contendo lista de peers conhecidos.
    GET_BLOCKCHAIN,  // Solicitação da cópia completa da blockchain local.
    BLOCKCHAIN_COPY, // Envio/Anúncio da cópia serializada da blockchain.
    ACK,             // Confirmação de recebimento/sucesso de processamento
    REJECT           // Notificação de rejeição ou erro de validação
}