package org.example.kipper_academy.external;

/** O comentario NAO foi publicado (rede, token invalido, repositorio nao autorizado...). */
public class PublicationException extends RuntimeException {

    public PublicationException(String message) {
        super(message);
    }

    public PublicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
