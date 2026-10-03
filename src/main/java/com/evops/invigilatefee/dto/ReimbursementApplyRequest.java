package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 交通补贴报销申请：一次跨区出行一条。考点由批次带出，金额按 出发地里程 + 交通方式 + 出行日期命中规则版本试算。
 * 本人视图（X-Self-Code）下 invigilatorCode 必须与头一致，教师只能为本人申请。
 */
@Data
public class ReimbursementApplyRequest {
    @NotNull(message = "批次 id 不能为空")
    private Long batchId;
    @NotBlank(message = "报销监考人员编码不能为空")
    private String invigilatorCode;
    @NotBlank(message = "出发地编码不能为空")
    private String originCode;
    /** BUS / RAIL / TAXI / SELF_DRIVE / OTHER */
    @NotBlank(message = "交通方式不能为空")
    private String transportMode;
    @NotNull(message = "出行日期不能为空")
    private LocalDate travelDate;
    private String remark;
}
