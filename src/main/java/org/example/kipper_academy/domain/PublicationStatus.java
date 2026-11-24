package org.example.kipper_academy.domain;

/** Estado da publicacao do comentario de triagem no destino externo (GitHub). */
public enum PublicationStatus {
    /** Triagem aceita pelo harness, comentario ainda nao publicado no destino externo. */
    PENDING,
    /** Comentario publicado e confirmado pelo servico externo. */
    PUBLISHED,
    /** Tentativa de publicacao falhou; nada foi publicado, pode ser tentado de novo. */
    FAILED,
    /** Encaminhado para revisao humana; nenhum comentario e publicado. */
    SKIPPED_REVIEW
}
