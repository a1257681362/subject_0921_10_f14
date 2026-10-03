package com.evops.discipline.dto;

import lombok.Data;

/**
 * 后果执行（可空备注）：执行结果回写成绩档案；成绩尚未导入则挂 PENDING_SCORE，成绩落库时自动回写。
 */
@Data
public class CaseExecuteRequest {
    private String remark;
}
