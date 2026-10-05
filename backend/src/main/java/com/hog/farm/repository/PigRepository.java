package com.hog.farm.repository;

import com.hog.farm.domain.Pig;
import com.hog.farm.dto.BreedStatDTO;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PigRepository extends JpaRepository<Pig, Long> {

    boolean existsByEarTag(String earTag);

    Optional<Pig> findByEarTag(String earTag);

    List<Pig> findAllByOrderByEarTagAsc();

    List<Pig> findByPenIdOrderByEarTagAsc(Long penId);

    List<Pig> findByStatusOrderByEarTagAsc(String status);

    List<Pig> findByBatchIdOrderByEarTagAsc(Long batchId);

    List<Pig> findByBatchIdOrderByIdAsc(Long batchId);

    /** 出栏 / 用药事务内按固定顺序加行锁，防止并发放行和确认期间补药。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pig p where p.id in :ids order by p.id asc")
    List<Pig> lockByIds(@Param("ids") List<Long> ids);

    @Query("select p from Pig p where p.penId = :penId order by p.id asc")
    List<Pig> findByPenIdOrderByIdAsc(@Param("penId") Long penId);

    /** 栋舍状态评估：栏位集合内的全部猪只（含历史遗留的终态未释放记录） */
    @Query("select p from Pig p where p.penId in :penIds order by p.id asc")
    List<Pig> findByPenIdInOrderByIdAsc(@Param("penIds") List<Long> penIds);

    /** 栋舍状态评估：栋舍标记为该栋舍的全部猪只（用于发现位置不明） */
    List<Pig> findByHouseIdOrderByIdAsc(Long houseId);

    long countByStatus(String status);

    /** 品种存栏分布：只统计在场的（已出栏 / 死亡 / 淘汰不入统计） */
    @Query("select new com.hog.farm.dto.BreedStatDTO(p.breed, count(p), coalesce(avg(p.weightKg), 0.0)) "
            + "from Pig p where p.status not in ('SOLD', 'DEAD', 'CULLED') group by p.breed")
    List<BreedStatDTO> statByBreed();
}
