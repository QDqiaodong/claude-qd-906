package com.hog.farm.repository;

import com.hog.farm.domain.Batch;
import com.hog.farm.dto.BatchStatusStatDTO;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BatchRepository extends JpaRepository<Batch, Long> {

    boolean existsByBatchNo(String batchNo);

    Optional<Batch> findByBatchNo(String batchNo);

    List<Batch> findAllByOrderByBatchNoAsc();

    List<Batch> findByStatusOrderByBatchNoAsc(String status);

    @Query("select new com.hog.farm.dto.BatchStatusStatDTO(b.status, count(b), "
            + "coalesce(sum(b.intakeCount), 0), coalesce(sum(b.soldCount), 0)) "
            + "from Batch b group by b.status")
    List<BatchStatusStatDTO> statByStatus();
}
