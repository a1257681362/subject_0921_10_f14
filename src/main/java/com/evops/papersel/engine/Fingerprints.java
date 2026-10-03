package com.evops.papersel.engine;

import com.evops.common.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;

/**
 * 指纹工具（SHA-256，十六进制小写）：
 * - 素材内容指纹：知识点|难度档|题干|参考答案，内容改动必然变指纹；
 * - 池子指纹：冻结快照内全部素材逐条摘要的汇总，池子增删改必然变指纹；
 * - 卷面指纹：版本口径 + 根/衍生种子 + 池子指纹 + 顺序中选素材，任一处被改动复算即不符。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException("FINGERPRINT_FAILED", "SHA-256 指纹计算失败：" + e.getMessage());
        }
    }

    /** 素材内容指纹：任一内容字段改动都会改变。 */
    public static String materialContentHash(String knowledgePoint, String difficulty,
                                             String content, String referenceAnswer) {
        return sha256(safe(knowledgePoint) + "|" + safe(difficulty) + "|"
                + safe(content) + "|" + safe(referenceAnswer));
    }

    /**
     * 池子指纹：对冻结快照按素材 id 排序，逐条摘要汇总。
     * 快照内容（增删素材、内容改动）任一变化都导致指纹变化；与入参顺序无关，保证复算稳定。
     */
    public static String poolFingerprint(List<? extends PoolHashEntry> entries) {
        List<? extends PoolHashEntry> ordered = new java.util.ArrayList<>(entries);
        ordered.sort(Comparator.comparingLong(PoolHashEntry::fpMaterialId));
        StringBuilder sb = new StringBuilder("POOL:");
        for (PoolHashEntry e : ordered) {
            sb.append(e.fpMaterialId()).append(':')
                    .append(safe(e.fpContentHash())).append(';');
        }
        return sha256(sb.toString());
    }

    /**
     * 卷面指纹：版本口径 + 根种子 + 衍生种子 + 池子指纹 + 顺序中选素材（编号:内容哈希）。
     * 卷面被调换、种子被改、池子快照被改，任一处都会让事后复算指纹对不上。
     */
    public static String paperFingerprint(String selVersionNo, long rootSeed, long seed,
                                          String poolFingerprint,
                                          List<? extends PoolHashEntry> selectedOrdered) {
        StringBuilder sb = new StringBuilder("PAPER:");
        sb.append(safe(selVersionNo)).append('|')
                .append(rootSeed).append('|')
                .append(seed).append('|')
                .append(safe(poolFingerprint)).append('|');
        for (PoolHashEntry e : selectedOrdered) {
            sb.append(e.fpMaterialId()).append(':')
                    .append(safe(e.fpContentHash())).append(';');
        }
        return sha256(sb.toString());
    }

    /** 指纹计算只关心素材 id 与内容哈希，引擎 PoolItem 与快照 DTO 都实现它。 */
    public interface PoolHashEntry {
        long fpMaterialId();
        String fpContentHash();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
