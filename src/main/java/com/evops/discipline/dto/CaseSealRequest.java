package com.evops.discipline.dto;

import lombok.Data;

/**
 * 结案封存：仅 EXECUTED 已执行完结或 REVOKED 已撤销的案件可封存；封存记录仅授权岗可查。
 */
@Data
public class CaseSealRequest {
    /** 封存备注（可空）。 */
    private String remark;
}
