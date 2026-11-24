package org.example.kipper_academy.domain;

/** Estado da publicacao do comentario de triagem no destino externo (GitHub). */
public enum PublicationStatus {
    /** Evento aceito pela ingestao, ainda nao processado pelo worker. */
    PENDING,
    /** Comentario publicado e confirmado pelo servico externo. */
    PUBLISHED,
    /** Tentativa de publicacao falhou; nada foi publicado, pode ser tentado de novo. */
    FAILED,
    /** Encaminhado para revisao humana; nenhum comentario e publicado. */
    SKIPPED_REVIEW
}
