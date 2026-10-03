package com.evops.papersel.engine;

import com.evops.common.BusinessException;
import com.evops.papersel.dto.QuotaItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 组卷抽选引擎（纯函数式、可复现）：
 * 给定冻结素材池、知识点+难度档配比、抽选参数、根种子与套数，输出各套中选素材与套间重合率。
 *
 * 规则：
 * 1) 逐档抽：按配比顺序，对"知识点+难度档"分桶，用本套本试种子洗牌取前 count 道，
 *    随机源只有 java.util.Random(种子)，同池子同配比同种子必得同结果；
 * 2) 各套种子由根种子 + 套号 + 重试探次确定性推导，多套串行抽选（套 0、套 1…）；
 * 3) 每抽完一套与此前各套算重合率（重合素材数 / 单套题量），任一超过阈值即自动换种子重抽，
 *    重抽达上限仍不过则整批失败（调用方不落库）；
 * 4) 池子不足先于抽选逐项报缺口（哪个知识点哪个难度档要多少、池里有多少）。
 */
public final class PaperSelector {

    /** 种子派生黄金增量（与 SeatingMixer 同风格，64 位回绕加法）。 */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long GOLDEN2 = 0xD1B54A32D192ED03L;

    private PaperSelector() {
    }

    /** 冻结素材池条目（组卷时点在池素材的快照行；复算也用它）。 */
    public static class PoolItem implements Fingerprints.PoolHashEntry {
        public final Long id;
        public final String materialNo;
        public final String knowledgePoint;
        public final String difficulty;
        public final String contentHash;

        public PoolItem(Long id, String materialNo, String knowledgePoint,
                        String difficulty, String contentHash) {
            this.id = id;
            this.materialNo = materialNo;
            this.knowledgePoint = knowledgePoint;
            this.difficulty = difficulty;
            this.contentHash = contentHash;
        }

        @Override
        public long fpMaterialId() {
            return id;
        }

        @Override
        public String fpContentHash() {
            return contentHash;
        }
    }

    /** 抽选参数（版本冻结口径）。 */
    public static class Params {
        public final double maxOverlapRate;
        public final int maxRetries;

        public Params(double maxOverlapRate, int maxRetries) {
            this.maxOverlapRate = maxOverlapRate;
            this.maxRetries = maxRetries;
        }
    }

    /** 单套与另一套的重合情况。 */
    public static class Overlap {
        public final int paperIndex;
        public final int shared;
        public final double rate;

        public Overlap(int paperIndex, int shared, double rate) {
            this.paperIndex = paperIndex;
            this.shared = shared;
            this.rate = rate;
        }
    }

    /** 单套抽选结果。 */
    public static class Paper {
        public int index;
        public long seed;
        public List<PoolItem> selected = new ArrayList<>();
        public int retries;
        public final List<Overlap> overlaps = new ArrayList<>();
        public double overlapMaxRate;
    }

    /** 一次多套并行抽选结果。 */
    public static class Result {
        public long rootSeed;
        public int paperSize;
        public final List<Paper> papers = new ArrayList<>();
    }

    /** 池子缺口（供调用方逐项提示）。 */
    public static class Shortage {
        public final String knowledgePoint;
        public final String difficulty;
        public final int required;
        public final int available;

        public Shortage(String knowledgePoint, String difficulty, int required, int available) {
            this.knowledgePoint = knowledgePoint;
            this.difficulty = difficulty;
            this.required = required;
            this.available = available;
        }
    }

    /** 套号 + 重试探次 → 实际抽选种子（纯确定性，复算一致）。 */
    public static long deriveSeed(long rootSeed, int paperIndex, int retry) {
        return rootSeed + GOLDEN * (paperIndex + 1L) + GOLDEN2 * (retry + 1L);
    }

    /**
     * 池子充足性检查：返回逐档缺口；空列表表示充足。
     */
    public static List<Shortage> checkPool(List<PoolItem> pool, List<QuotaItem> quotas) {
        Map<String, Integer> available = new LinkedHashMap<>();
        for (PoolItem item : pool) {
            String key = bucketKey(item.knowledgePoint, item.difficulty);
            available.merge(key, 1, Integer::sum);
        }
        List<Shortage> shortages = new ArrayList<>();
        for (QuotaItem q : quotas) {
            String key = bucketKey(q.getKnowledgePoint(), q.getDifficulty());
            int have = available.getOrDefault(key, 0);
            if (have < q.getCount()) {
                shortages.add(new Shortage(q.getKnowledgePoint().trim(),
                        q.getDifficulty().trim().toUpperCase(), q.getCount(), have));
            }
        }
        return shortages;
    }

    /**
     * 执行多套并行抽选。池子不足抛 POOL_INSUFFICIENT；重抽上限内压不下重合率抛 OVERLAP_RETRY_EXHAUSTED。
     */
    public static Result compose(List<PoolItem> pool, List<QuotaItem> quotas,
                                 Params params, long rootSeed, int paperCount) {
        List<Shortage> shortages = checkPool(pool, quotas);
        if (!shortages.isEmpty()) {
            throw new BusinessException("POOL_INSUFFICIENT", buildShortageMessage(shortages));
        }

        int paperSize = 0;
        for (QuotaItem q : quotas) {
            paperSize += q.getCount();
        }

        // 分桶：同一 (知识点,难度档) 的在池素材（保持入参顺序，洗牌由随机种子负责）
        Map<String, List<PoolItem>> buckets = new LinkedHashMap<>();
        for (PoolItem item : pool) {
            buckets.computeIfAbsent(bucketKey(item.knowledgePoint, item.difficulty),
                    k -> new ArrayList<>()).add(item);
        }

        Result result = new Result();
        result.rootSeed = rootSeed;
        result.paperSize = paperSize;

        for (int i = 0; i < paperCount; i++) {
            Paper accepted = null;
            String lastDetail = "";
            for (int retry = 0; retry <= params.maxRetries; retry++) {
                long seed = deriveSeed(rootSeed, i, retry);
                List<PoolItem> selected = selectOnce(buckets, quotas, seed);
                List<Overlap> overlaps = new ArrayList<>();
                double maxRate = 0d;
                for (Paper earlier : result.papers) {
                    int shared = countShared(selected, earlier.selected);
                    double rate = paperSize == 0 ? 0d : (double) shared / paperSize;
                    overlaps.add(new Overlap(earlier.index, shared, round4(rate)));
                    maxRate = Math.max(maxRate, rate);
                }
                if (maxRate <= params.maxOverlapRate) {
                    Paper paper = new Paper();
                    paper.index = i;
                    paper.seed = seed;
                    paper.selected = selected;
                    paper.retries = retry;
                    paper.overlaps.addAll(overlaps);
                    paper.overlapMaxRate = round4(maxRate);
                    accepted = paper;
                    break;
                }
                Overlap worst = null;
                for (Overlap o : overlaps) {
                    if (worst == null || o.rate > worst.rate) {
                        worst = o;
                    }
                }
                lastDetail = String.format("第 %d 套第 %d 次试抽与第 %d 套重合率 %.2f%% 超阈值 %.2f%%",
                        i + 1, retry + 1, worst.paperIndex + 1,
                        worst.rate * 100, params.maxOverlapRate * 100);
            }
            if (accepted == null) {
                throw new BusinessException("OVERLAP_RETRY_EXHAUSTED", String.format(
                        "第 %d 套在 %d 次重抽内无法把套间重合率压到 %.2f%% 以下：%s；请扩素材池或放宽阈值",
                        i + 1, params.maxRetries, params.maxOverlapRate * 100, lastDetail));
            }
            result.papers.add(accepted);
        }
        return result;
    }

    /** 单次试抽：按配比顺序逐桶洗牌取数。随机源仅来自种子，可复现。 */
    public static List<PoolItem> selectOnce(Map<String, List<PoolItem>> buckets,
                                            List<QuotaItem> quotas, long seed) {
        Random random = new Random(seed);
        List<PoolItem> selected = new ArrayList<>();
        for (QuotaItem q : quotas) {
            List<PoolItem> bucket = buckets.get(bucketKey(q.getKnowledgePoint(), q.getDifficulty()));
            if (bucket == null || bucket.size() < q.getCount()) {
                // compose 已做总量预检；理论不可达，防御性抛错避免半套结果外流
                throw new BusinessException("POOL_INSUFFICIENT",
                        "抽选时发现池子不足：" + q.getKnowledgePoint() + "/" + q.getDifficulty());
            }
            List<PoolItem> shuffled = new ArrayList<>(bucket);
            Collections.shuffle(shuffled, random);
            for (int k = 0; k < q.getCount(); k++) {
                selected.add(shuffled.get(k));
            }
        }
        return selected;
    }

    private static int countShared(List<PoolItem> a, List<PoolItem> b) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (PoolItem item : a) {
            ids.add(item.id);
        }
        int shared = 0;
        for (PoolItem item : b) {
            if (ids.contains(item.id)) {
                shared++;
            }
        }
        return shared;
    }

    public static String buildShortageMessage(List<Shortage> shortages) {
        StringBuilder sb = new StringBuilder("素材池不足，无法按配比组卷：");
        for (int i = 0; i < shortages.size(); i++) {
            Shortage s = shortages.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(String.format("知识点[%s] 难度[%s] 需 %d 道，池内在池仅 %d 道（缺口 %d）",
                    s.knowledgePoint, s.difficulty, s.required, s.available,
                    s.required - s.available));
        }
        return sb.toString();
    }

    static String bucketKey(String knowledgePoint, String difficulty) {
        return knowledgePoint.trim() + "\0" + difficulty.trim().toUpperCase();
    }

    private static double round4(double v) {
        return Math.round(v * 10000d) / 10000d;
    }
}
