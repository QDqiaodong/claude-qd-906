package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 栋舍状态切换台账：每一次「生效」的状态切换落一行。
 * request_no 是操作员侧幂等键——同一次切换重复提交时直接重放结果，不重复生效。
 */
@Entity
@Table(name = "house_status_log")
@Getter
@Setter
public class HouseStatusLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "house_id", nullable = false)
    private Long houseId;

    /** 幂等键：前端打开确认框时生成，重试沿用；唯一约束兜底重复提交 */
    @Column(name = "request_no", unique = true, length = 48)
    private String requestNo;

    /** 切换前状态 */
    @Column(name = "from_status", length = 16)
    private String fromStatus;

    /** 操作员请求的目标状态（ACTIVE / MAINTAIN / DISABLED） */
    @Column(name = "requested_status", length = 16)
    private String requestedStatus;

    /** 实际生效状态（请求停用但未腾空时生效为 DRAINING） */
    @Column(name = "to_status", length = 16)
    private String toStatus;

    /** 操作员填写的切换原因 */
    @Column(name = "reason", length = 128)
    private String reason;

    @Column(name = "operator", length = 32)
    private String operator;

    /** 生效时点在舍猪只数（存栏处置结论的追踪依据） */
    @Column(name = "pig_count")
    private Integer pigCount;

    /** 生效时点未完操作（清洗消毒 / 维修）栏位数 */
    @Column(name = "unfinished_pens")
    private Integer unfinishedPens;

    /** 恢复在用前评估出的待处理异常数（超容 / 位置不明 / 计数不符） */
    @Column(name = "anomaly_count")
    private Integer anomalyCount;

    /** 阻塞原因 / 异常明细等补充说明 */
    @Column(name = "detail", length = 255)
    private String detail;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
