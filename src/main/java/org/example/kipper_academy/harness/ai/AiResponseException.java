package org.example.kipper_academy.harness.ai;

/**
 * A IA respondeu, mas a resposta nao pode ser aceita: recusa, resposta cortada por limite de
 * tokens, JSON fora do schema, ou ferramentas demais. O harness converte em REVISAO_HUMANA.
 */
public class AiResponseException extends RuntimeException {

    public AiResponseException(String message) {
        super(message);
    }

    public AiResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
