package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报销票据凭证：一条申请多张（去程 / 回程 / 多段），只本人提交、只追加不可改。
 * 各票金额合计 = 申报票面合计，参与“与里程估算取小”。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_fee_receipt")
public class FeeReceipt extends BaseEntity {
    private Long claimId;
    /** 该票对应交通方式（可分段）。 */
    private String transportMode;
    private BigDecimal amount;
    private LocalDateTime occurredAt;
    private String fileRef;
    private String contentHash;
    private String originalFilename;
    private String content;
    private String remark;
    private String operator;
}
