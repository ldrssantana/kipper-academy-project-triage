package org.example.kipper_academy.persistence;

import org.example.kipper_academy.domain.ProjectCatalog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectCatalogRepository extends JpaRepository<ProjectCatalog, String> {
}
