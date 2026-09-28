package com.hog.farm.repository;

import com.hog.farm.domain.Pig;
import com.hog.farm.dto.BreedStatDTO;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PigRepository extends JpaRepository<Pig, Long> {

    boolean existsByEarTag(String earTag);

    Optional<Pig> findByEarTag(String earTag);

    List<Pig> findAllByOrderByEarTagAsc();

    List<Pig> findByPenIdOrderByEarTagAsc(Long penId);

    List<Pig> findByStatusOrderByEarTagAsc(String status);

    List<Pig> findByBatchIdOrderByEarTagAsc(Long batchId);

    long countByStatus(String status);

    /** 品种存栏分布：只统计在场的（已出栏 / 死亡 / 淘汰不入统计） */
    @Query("select new com.hog.farm.dto.BreedStatDTO(p.breed, count(p), coalesce(avg(p.weightKg), 0.0)) "
            + "from Pig p where p.status not in ('SOLD', 'DEAD', 'CULLED') group by p.breed")
    List<BreedStatDTO> statByBreed();
}
