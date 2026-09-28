package com.hog.farm.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 前端视图对象集合：所有列表接口统一返回这里的结构（带中文名称、颜色、派生比率），
 * 避免前端自己拼字典与算比率。
 */
public final class Views {

    private Views() {
    }

    /** 栋舍平面格子图：一个栋舍 + 它的若干栏位格子 */
    @Data
    public static class HouseVO {
        private Long id;
        private String houseCode;
        private String houseName;
        private String houseType;
        private String houseTypeName;
        private String houseTypeColor;
        private BigDecimal areaSqm;
        private String keeper;
        private String builtDate;
        private Integer penCount;
        private String status;
        private String statusName;
        private String note;
        /** 栋舍总容量 */
        private Integer capacity = 0;
        /** 栋舍总存栏 */
        private Integer pigCount = 0;
        /** 栋舍存栏率 0~1 */
        private Double usageRate = 0D;
        /** 停用栋舍不允许转入 */
        private Boolean disabled = Boolean.FALSE;
        private List<PenVO> pens = new ArrayList<>();
    }

    /** 格子图里的一个栏位格子 */
    @Data
    public static class PenVO {
        private Long id;
        private Long houseId;
        private String houseCode;
        private String houseName;
        private String penCode;
        private String penName;
        private Integer capacity;
        private Integer pigCount;
        private Integer remaining;
        private Double usageRate;
        private String status;
        private String statusName;
        private String note;
    }

    /** 猪只台账行 */
    @Data
    public static class PigVO {
        private Long id;
        private String earTag;
        private String breed;
        private String breedName;
        private String breedColor;
        private String gender;
        private String genderName;
        private String birthDate;
        private Long ageInDays;
        private String sourceType;
        private String sourceName;
        private Long penId;
        private String penCode;
        private Long houseId;
        private String houseCode;
        private Long batchId;
        private String batchNo;
        private String status;
        private String statusName;
        private BigDecimal weightKg;
        private String statusDate;
        private String note;
    }

    /** 出栏批次卡片 */
    @Data
    public static class BatchVO {
        private Long id;
        private String batchNo;
        private String batchName;
        private Long houseId;
        private String houseCode;
        private String breed;
        private String breedName;
        private Integer intakeCount;
        private Integer soldCount;
        private Integer deadCount;
        private Integer remainCount;
        private Double soldRate;
        private String startDate;
        private String expectDate;
        private String status;
        private String statusName;
        private String statusColor;
        /** 下一个可推进状态（终态为 null） */
        private String nextStatus;
        private String nextStatusName;
        private String buyer;
        private BigDecimal avgWeightKg;
        private String note;
    }

    /** 转栏记录行 */
    @Data
    public static class TransferVO {
        private Long id;
        private Long pigId;
        private String earTag;
        private Long fromPenId;
        private String fromPenCode;
        private Long toPenId;
        private String toPenCode;
        private String transferDate;
        private String reason;
        private String reasonName;
        private String operator;
        private String remark;
        private String createdAt;
    }

    /** 饲喂记录行 */
    @Data
    public static class FeedingVO {
        private Long id;
        private Long feedBatchId;
        private String feedNo;
        private Long penId;
        private String penCode;
        private String feedName;
        private String feedType;
        private String feedTypeName;
        private BigDecimal amountKg;
        private Integer pigCount;
        private BigDecimal perHeadKg;
        private String feedTime;
        private String operator;
        private String remark;
    }

    /** 免疫记录行 */
    @Data
    public static class VaccineVO {
        private Long id;
        private Long pigId;
        private String earTag;
        private String vaccineName;
        private BigDecimal doseMl;
        private String vaccineDate;
        private String nextDate;
        private String operator;
        private String remark;
        private Boolean overdue;
    }

    /** @ManyToAny 的一个多态来源标签 */
    @Data
    public static class TargetVO {
        /** PIG / BATCH / HOUSE / FEED_BATCH */
        private String targetType;
        private String targetTypeName;
        private Long targetId;
        /** 来源对象编号（耳标号 / 批次号 / 栋舍编号 / 饲料批次号） */
        private String targetCode;
        private String targetName;
    }

    /** 溯源时间线节点 */
    @Data
    public static class TraceVO {
        private Long id;
        private String traceNo;
        private String traceType;
        private String traceTypeName;
        private String traceTypeColor;
        private String eventTitle;
        private String eventDesc;
        private String operator;
        private String eventTime;
        /** 时间线分组用的日期 yyyy-MM-dd */
        private String day;
        private List<TargetVO> targets = new ArrayList<>();
    }

    /** 顶部概览数字 */
    @Data
    public static class OverviewVO {
        private Long houseCount;
        private Long penCount;
        private Long pigCount;
        private Long onFarmCount;
        private Long batchCount;
        private Long feedBatchCount;
        private Long transferCount;
        private Long feedingCount;
        private Long vaccineCount;
        private Long traceCount;
        private Integer penCapacity;
        private Double penUsageRate;
    }
}
