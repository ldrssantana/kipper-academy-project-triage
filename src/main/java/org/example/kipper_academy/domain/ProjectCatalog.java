package org.example.kipper_academy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Entrada do catalogo de dominio: repositorio aceito, responsavel e orientacoes de triagem (SPEC 5).
 * Somente leitura pela aplicacao; populada por data.sql.
 */
@Entity
@Table(name = "project_catalog")
@Getter
@NoArgsConstructor
public class ProjectCatalog {

    @Id
    private String repository;

    @Column(nullable = false)
    private String responsible;

    @Column(name = "triage_guidance", nullable = false, columnDefinition = "TEXT")
    private String triageGuidance;
}
