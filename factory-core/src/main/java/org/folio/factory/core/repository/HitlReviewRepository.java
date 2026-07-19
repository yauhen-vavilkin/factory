package org.folio.factory.core.repository;

import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HitlReviewRepository extends JpaRepository<HitlReview, UUID> {

    List<HitlReview> findByStatusOrderByCreatedAtAsc(HitlReviewStatus status);

    List<HitlReview> findByExecutionIdOrderByCreatedAtAsc(UUID executionId);

    Page<HitlReview> findByStatus(HitlReviewStatus status, Pageable pageable);

    long countByStatus(HitlReviewStatus status);
}
