package com.hog.farm.repository;

import com.hog.farm.domain.HouseStatusLog;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseStatusLogRepository extends JpaRepository<HouseStatusLog, Long> {

    /** 幂等键查询：同一次切换重复提交时重放 */
    Optional<HouseStatusLog> findByRequestNo(String requestNo);

    List<HouseStatusLog> findByHouseIdOrderByIdDesc(Long houseId);
}
