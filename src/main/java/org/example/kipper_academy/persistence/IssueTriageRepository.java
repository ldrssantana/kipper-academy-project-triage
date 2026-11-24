package org.example.kipper_academy.persistence;

import org.example.kipper_academy.domain.IssueTriage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IssueTriageRepository extends JpaRepository<IssueTriage, Long> {

    Optional<IssueTriage> findByRepositoryAndIssueNumber(String repository, Integer issueNumber);
}
