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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 栋舍：猪场的最小建筑单元（配怀 / 分娩 / 保育 / 育肥 / 隔离）。
 * 状态链路：在用 → 检修（立即生效、原地照护、冻结新增）；
 * 停用必须先腾空（全部猪只迁出且栏位无未完操作），未腾空时停留在「待清空」；
 * 检修 / 待清空 / 停用期间，其下栏位一律不允许新猪进入。
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

    /** 栋舍状态：ACTIVE 在用 / MAINTAIN 检修 / DRAINING 待清空 / DISABLED 停用 */
    @Column(name = "status", length = 16)
    private String status;

    /** 进入当前状态时操作员填写的原因（检修内容 / 停用缘由等） */
    @Column(name = "status_reason", length = 128)
    private String statusReason;

    /** 当前状态生效时间 */
    @Column(name = "status_changed_at")
    private LocalDateTime statusChangedAt;

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

    /** 非在用即受限：检修 / 待清空 / 停用都冻结新增入栏与转入 */
    @Transient
    public boolean isRestricted() {
        return !"ACTIVE".equals(status);
    }
}
