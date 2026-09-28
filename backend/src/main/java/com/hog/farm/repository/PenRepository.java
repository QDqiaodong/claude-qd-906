package com.hog.farm.repository;

import com.hog.farm.domain.Pen;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PenRepository extends JpaRepository<Pen, Long> {

    boolean existsByPenCode(String penCode);

    Optional<Pen> findByPenCode(String penCode);

    List<Pen> findByHouseIdOrderByPenCodeAsc(Long houseId);

    List<Pen> findAllByOrderByPenCodeAsc();

    int countByHouseId(Long houseId);
}
