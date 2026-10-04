package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * 栋舍：猪场的最小建筑单元（配怀 / 分娩 / 保育 / 育肥 / 隔离）。
 * 停用（DISABLED）后其下所有栏位都不允许再转入猪只。
 */
@Entity
@Table(name = "house")
@Getter
@Setter
public class House implements TraceSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 栋舍编号：全场唯一，如 HS-A01 */
    @Column(name = "house_code", nullable = false, unique = true, length = 32)
    private String houseCode;

    /** 栋舍名称，如 一号配怀舍 */
    @Column(name = "house_name", length = 64)
    private String houseName;

    /** 栋舍类型：GESTATION / FARROWING / NURSERY / FINISHING / ISOLATION */
    @Column(name = "house_type", nullable = false, length = 24)
    private String houseType;

    /** 建筑面积（㎡） */
    @Column(name = "area_sqm", precision = 10, scale = 2)
    private BigDecimal areaSqm;

    /** 栋舍饲养员 */
    @Column(name = "keeper", length = 32)
    private String keeper;

    /** 投产日期 */
    @Column(name = "built_date")
    private LocalDate builtDate;

    /** 栏位数量（由栏位增删维护） */
    @Column(name = "pen_count")
    private Integer penCount;

    /** 栋舍状态：ACTIVE 在用 / MAINTAIN 检修 / DISABLED 停用 */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "note", length = 128)
    private String note;

    @Override
    public String getTraceCode() {
        return houseCode;
    }

    @Override
    public String getTraceName() {
        return houseName == null ? houseCode : houseName;
    }

    @Transient
    public boolean isDisabled() {
        return "DISABLED".equals(status);
    }
}
