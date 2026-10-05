package com.hog.farm.dto;

import lombok.Data;

/**
 * 栋舍状态切换请求：目标状态 + 切换原因 + 幂等键。
 * 目标状态只允许 在用 / 检修 / 停用；「待清空」由系统根据存栏与栏位情况自动落位，不接受直接指定。
 */
@Data
public class HouseStatusRequest {

    /** 目标状态：ACTIVE 在用 / MAINTAIN 检修 / DISABLED 停用 */
    private String target;

    /** 切换原因（检修内容 / 停用缘由 / 恢复说明），会写入状态台账 */
    private String reason;

    /** 操作人 */
    private String operator;

    /** 幂等键：打开确认框时生成，重试沿用；同一次切换不会重复生效 */
    private String requestNo;
}
