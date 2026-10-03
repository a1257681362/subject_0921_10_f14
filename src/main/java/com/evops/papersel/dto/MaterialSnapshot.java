package com.evops.papersel.dto;

import lombok.Data;

/**
 * 素材冻结快照行：组卷时点把在池素材固化进运行记录；历史卷面复算只认快照，不回表现值。
 */
@Data
public class MaterialSnapshot implements com.evops.papersel.engine.Fingerprints.PoolHashEntry {
    private Long materialId;
    private String materialNo;
    private String knowledgePoint;
    private String difficulty;
    private String contentHash;

    @Override
    public long fpMaterialId() {
        return materialId == null ? 0L : materialId;
    }

    @Override
    public String fpContentHash() {
        return contentHash;
    }
}
