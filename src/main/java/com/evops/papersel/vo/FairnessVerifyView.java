package com.evops.papersel.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 事后公平核验结果：
 * 用运行记录里冻结的参数、配比、根种子与池子快照重新抽一遍，逐套比种子、中选清单与卷面指纹。
 */
@Data
public class FairnessVerifyView {
    private Long runId;
    private String runNo;
    private String groupCode;
    private Long rootSeed;
    private String selVersionNo;
    private String storedPoolFingerprint;
    /** 用冻结快照重算的池子指纹（与库存一致说明快照未被篡改）。 */
    private String recomputedPoolFingerprint;
    /** 全部套：种子/清单/指纹三项全符且快照自洽。 */
    private Boolean fair;
    private List<PaperCheck> papers = new ArrayList<>();
    private List<String> notes = new ArrayList<>();

    @Data
    public static class PaperCheck {
        private Integer paperIndex;
        private String paperNo;
        private Long storedSeed;
        private Long recomputedSeed;
        private String storedFingerprint;
        private String recomputedFingerprint;
        private Boolean seedMatch;
        private Boolean selectionMatch;
        private Boolean fingerprintMatch;
        /** 本套公平：三项全符。 */
        private Boolean fair;
        /** 清单中与当前素材池现状（哈希/在池状态）不一致的条目数；池子变动不影响历史，仅作提示。 */
        private Integer liveDriftCount;
    }
}
