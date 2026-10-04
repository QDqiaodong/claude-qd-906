package com.hog.farm.repository;

import com.hog.farm.domain.Pen;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PenRepository extends JpaRepository<Pen, Long> {

    boolean existsByPenCode(String penCode);

    Optional<Pen> findByPenCode(String penCode);

    List<Pen> findByHouseIdOrderByPenCodeAsc(Long houseId);

    List<Pen> findAllByOrderByPenCodeAsc();

    int countByHouseId(Long houseId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Pen p set "
            + "p.pigCount = case when coalesce(p.pigCount, 0) + :delta < 0 then 0 "
            + "else coalesce(p.pigCount, 0) + :delta end, "
            + "p.status = case when p.status in ('CLEANING', 'REPAIR') then p.status "
            + "when (case when coalesce(p.pigCount, 0) + :delta < 0 then 0 "
            + "else coalesce(p.pigCount, 0) + :delta end) >= p.capacity then 'FULL' else 'NORMAL' end "
            + "where p.id = :penId")
    int updatePigCount(@Param("penId") Long penId, @Param("delta") int delta);
}
