package com.hog.farm.repository;

import com.hog.farm.domain.House;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseRepository extends JpaRepository<House, Long> {

    boolean existsByHouseCode(String houseCode);

    Optional<House> findByHouseCode(String houseCode);

    List<House> findAllByOrderByHouseCodeAsc();

    List<House> findByStatusOrderByHouseCodeAsc(String status);

    /** 栋舍类型分布：左连栏位统计栏位数 / 存栏 / 容量 */
    @Query("select new com.hog.farm.dto.HouseTypeStatDTO(h.houseType, count(distinct h.id), count(p.id), "
            + "coalesce(sum(p.pigCount), 0), coalesce(sum(p.capacity), 0)) "
            + "from House h left join Pen p on p.houseId = h.id "
            + "group by h.houseType")
    List<com.hog.farm.dto.HouseTypeStatDTO> statByHouseType();

    @Query("select count(h) from House h where h.status = :status")
    long countByStatus(@Param("status") String status);
}
