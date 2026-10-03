package com.evops.papersel.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.papersel.common.PaperSelConstants;
import com.evops.papersel.dto.ComposeRequest;
import com.evops.papersel.dto.MaterialSnapshot;
import com.evops.papersel.dto.PaperSelParams;
import com.evops.papersel.dto.QuotaItem;
import com.evops.papersel.engine.Fingerprints;
import com.evops.papersel.engine.PaperSelector;
import com.evops.papersel.entity.PaperMaterial;
import com.evops.papersel.entity.PaperSelAlgorithmVersion;
import com.evops.papersel.entity.PaperSelPaper;
import com.evops.papersel.entity.PaperSelRun;
import com.evops.papersel.mapper.PaperSelPaperMapper;
import com.evops.papersel.mapper.PaperSelRunMapper;
import com.evops.papersel.vo.ComposeView;
import com.evops.papersel.vo.ComposedPaperView;
import com.evops.papersel.vo.FairnessVerifyView;
import com.evops.papersel.vo.OverlapView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组卷运行服务：
 * - 两步：preview 预检（不落库）→ compose 执行（运行记录 + 各套卷面同一事务落库）；
 * - 组卷时点冻结在池素材快照与池子指纹，解析项目当前 ACTIVE 算法版本（无则内置默认）；
 * - 根种子显式优先，否则确定性生成；各套种子由根种子推导，抽选超重合阈值自动换种子重抽；
 * - 每套卷面存衍生种子、中选快照与卷面指纹；池子不足先逐项报缺口、整批不落库；
 * - verifyFairness 事后用冻结快照复算，逐套比种子/清单/卷面指纹验证公平。
 */
@Service
public class ComposeService {

    /** 无生效算法版本时，卷面记录的版本口径。 */
    public static final String BUILTIN_VERSION_NO = "BUILTIN_DEFAULT";

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private final MaterialPoolService materialPoolService;
    private final PaperSelAlgorithmService algorithmService;
    private final PaperSelRunMapper runMapper;
    private final PaperSelPaperMapper paperMapper;
    private final ObjectMapper objectMapper;

    public ComposeService(MaterialPoolService materialPoolService,
                          PaperSelAlgorithmService algorithmService,
                          PaperSelRunMapper runMapper,
                          PaperSelPaperMapper paperMapper,
                          ObjectMapper objectMapper) {
        this.materialPoolService = materialPoolService;
        this.algorithmService = algorithmService;
        this.runMapper = runMapper;
        this.paperMapper = paperMapper;
        this.objectMapper = objectMapper;
    }

    // ---------- 预检：不落库 ----------

    @Transactional(readOnly = true)
    public ComposeView preview(ComposeRequest request) {
        Plan plan = buildPlan(request);
        return toView(plan, null);
    }

    // ---------- 执行：运行记录 + 卷面同一事务落库 ----------

    @Transactional
    public ComposeView compose(ComposeRequest request) {
        Plan plan = buildPlan(request);

        PaperSelRun run = new PaperSelRun();
        String runNo = nextRunNo();
        run.setRunNo(runNo);
        run.setGroupCode(request.getGroupCode() == null || request.getGroupCode().trim().isEmpty()
                ? runNo : request.getGroupCode().trim());
        run.setProjectCode(plan.projectCode);
        run.setPaperCount(plan.result.papers.size());
        run.setPaperSize(plan.result.paperSize);
        run.setQuotas(writeJson(plan.quotas, "QUOTAS_SERIALIZE_FAILED", "配比"));
        run.setSelVersionId(plan.version == null ? null : plan.version.getId());
        run.setSelVersionNo(plan.selVersionNo);
        run.setParamsSnapshot(plan.paramsJson);
        run.setRootSeed(plan.rootSeed);
        run.setPoolSnapshot(writeJson(plan.poolSnapshots, "POOL_SNAPSHOT_SERIALIZE_FAILED", "池子快照"));
        run.setPoolFingerprint(plan.poolFingerprint);
        run.setMaxOverlapRate(BigDecimal.valueOf(plan.params.getMaxOverlapRate()));
        List<Integer> retries = new ArrayList<>();
        for (PaperSelector.Paper p : plan.result.papers) {
            retries.add(p.retries);
        }
        run.setRetrySummary(writeJson(retries, "RETRY_SUMMARY_SERIALIZE_FAILED", "重抽概况"));
        run.setWarnings(writeJson(plan.warnings, "WARNINGS_SERIALIZE_FAILED", "提示"));
        run.setStatus("COMPOSED");
        run.setOperator(OperatorHolder.current());
        run.setExecutedAt(LocalDateTime.now());
        runMapper.insert(run);

        LocalDateTime now = LocalDateTime.now();
        for (PaperSelector.Paper p : plan.result.papers) {
            PaperSelPaper paper = new PaperSelPaper();
            paper.setRunId(run.getId());
            paper.setPaperIndex(p.index);
            paper.setPaperNo(runNo + "-" + String.format("%02d", p.index + 1));
            paper.setSeed(p.seed);
            paper.setSelected(writeJson(plan.paperSnapshots.get(p.index),
                    "SELECTED_SERIALIZE_FAILED", "卷面中选清单"));
            paper.setPaperFingerprint(plan.paperFingerprints.get(p.index));
            paper.setOverlapMaxRate(BigDecimal.valueOf(p.overlapMaxRate));
            paper.setOverlapDetail(writeJson(toOverlapViews(p.overlaps),
                    "OVERLAP_SERIALIZE_FAILED", "重合明细"));
            paper.setRetries(p.retries);
            paper.setExecutedAt(now);
            paperMapper.insert(paper);
        }

        return toView(plan, run);
    }

    // ---------- 事后公平核验：用冻结快照复算 ----------

    @Transactional(readOnly = true)
    public FairnessVerifyView verifyFairness(Long runId) {
        PaperSelRun run = requireRun(runId);

        List<MaterialSnapshot> poolSnapshots = readList(run.getPoolSnapshot(),
                new TypeReference<List<MaterialSnapshot>>() {
                }, "POOL_SNAPSHOT_BROKEN", "池子快照");
        List<QuotaItem> quotas = readList(run.getQuotas(),
                new TypeReference<List<QuotaItem>>() {
                }, "QUOTAS_BROKEN", "配比");
        PaperSelParams params = readParams(run.getParamsSnapshot());

        FairnessVerifyView view = new FairnessVerifyView();
        view.setRunId(run.getId());
        view.setRunNo(run.getRunNo());
        view.setGroupCode(run.getGroupCode());
        view.setRootSeed(run.getRootSeed());
        view.setSelVersionNo(run.getSelVersionNo());
        view.setStoredPoolFingerprint(run.getPoolFingerprint());

        // 快照自洽：用冻结快照重算池子指纹，必须与库存指纹一致
        String recomputedPoolFp = Fingerprints.poolFingerprint(poolSnapshots);
        view.setRecomputedPoolFingerprint(recomputedPoolFp);
        boolean poolIntact = recomputedPoolFp.equals(run.getPoolFingerprint());
        if (!poolIntact) {
            view.getNotes().add("运行记录内池子快照与其指纹不一致，快照可能被篡改，复算结论不可信");
        }

        List<PaperSelPaper> storedPapers = paperMapper.selectList(new QueryWrapper<PaperSelPaper>()
                .eq("run_id", run.getId()).orderByAsc("paper_index"));
        if (storedPapers.size() != run.getPaperCount()) {
            view.getNotes().add("卷面数量与运行记录不一致：库内 " + storedPapers.size()
                    + " 套，记录 " + run.getPaperCount() + " 套");
        }

        // 当前素材池现状：只用于提示漂移，不参与公平判定（池子增删本就不该影响历史卷面）
        Map<Long, PaperMaterial> liveById = new LinkedHashMap<>();
        for (PaperMaterial m : materialPoolService.list(run.getProjectCode(), null, null, null)) {
            liveById.put(m.getId(), m);
        }

        boolean allFair = poolIntact && storedPapers.size() == run.getPaperCount();
        try {
            PaperSelector.Result recomputed = PaperSelector.compose(
                    toEngineItems(poolSnapshots), quotas,
                    new PaperSelector.Params(params.getMaxOverlapRate(), params.getMaxRetries()),
                    run.getRootSeed(), run.getPaperCount());

            for (PaperSelPaper stored : storedPapers) {
                FairnessVerifyView.PaperCheck check = new FairnessVerifyView.PaperCheck();
                check.setPaperIndex(stored.getPaperIndex());
                check.setPaperNo(stored.getPaperNo());
                check.setStoredSeed(stored.getSeed());
                check.setStoredFingerprint(stored.getPaperFingerprint());

                PaperSelector.Paper recomputedPaper = recomputed.papers.size() > stored.getPaperIndex()
                        ? recomputed.papers.get(stored.getPaperIndex()) : null;
                List<MaterialSnapshot> storedSelected = readList(stored.getSelected(),
                        new TypeReference<List<MaterialSnapshot>>() {
                        }, "SELECTED_BROKEN", "卷面中选清单");

                boolean seedMatch = recomputedPaper != null && recomputedPaper.seed == stored.getSeed();
                boolean selectionMatch = recomputedPaper != null
                        && sameSelection(storedSelected, recomputedPaper.selected);
                String recomputedFp = Fingerprints.paperFingerprint(run.getSelVersionNo(),
                        run.getRootSeed(), stored.getSeed(), run.getPoolFingerprint(), storedSelected);
                boolean fpMatch = recomputedFp.equals(stored.getPaperFingerprint());

                // 清单与当前池子现状的漂移（内容被改/已下架）：只提示，不算不公
                int drift = 0;
                for (MaterialSnapshot s : storedSelected) {
                    PaperMaterial live = liveById.get(s.getMaterialId());
                    if (live == null
                            || !live.getContentHash().equals(s.getContentHash())
                            || !PaperSelConstants.MATERIAL_ENABLED.equals(live.getStatus())) {
                        drift++;
                    }
                }
                check.setLiveDriftCount(drift);
                check.setRecomputedSeed(recomputedPaper == null ? null : recomputedPaper.seed);
                check.setRecomputedFingerprint(recomputedFp);
                check.setSeedMatch(seedMatch);
                check.setSelectionMatch(selectionMatch);
                check.setFingerprintMatch(fpMatch);
                boolean paperFair = seedMatch && selectionMatch && fpMatch;
                check.setFair(paperFair);
                if (!paperFair) {
                    allFair = false;
                }
                if (drift > 0) {
                    view.getNotes().add(String.format("第 %d 套有 %d 道素材相对当前池子已变动（下架或内容修改），不影响历史卷面复算",
                            stored.getPaperIndex() + 1, drift));
                }
                view.getPapers().add(check);
            }
        } catch (BusinessException e) {
            allFair = false;
            view.getNotes().add("冻结快照无法按原参数复算组卷：" + e.getMessage());
        }
        view.setFair(allFair);
        return view;
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public List<PaperSelRun> listRuns(String projectCode, String groupCode) {
        QueryWrapper<PaperSelRun> wrapper = new QueryWrapper<PaperSelRun>().orderByDesc("id");
        if (projectCode != null && !projectCode.trim().isEmpty()) {
            wrapper.eq("project_code", projectCode.trim().toUpperCase());
        }
        if (groupCode != null && !groupCode.trim().isEmpty()) {
            wrapper.eq("group_code", groupCode.trim());
        }
        List<PaperSelRun> runs = runMapper.selectList(wrapper);
        for (PaperSelRun run : runs) {
            // 列表不带快照大字段
            run.setPoolSnapshot(null);
            run.setQuotas(null);
            run.setParamsSnapshot(null);
        }
        return runs;
    }

    public PaperSelRun getRun(Long runId) {
        return requireRun(runId);
    }

    @Transactional(readOnly = true)
    public List<PaperSelPaper> listPapers(Long runId) {
        requireRun(runId);
        return paperMapper.selectList(new QueryWrapper<PaperSelPaper>()
                .eq("run_id", runId).orderByAsc("paper_index"));
    }

    public PaperSelPaper getPaper(Long paperId) {
        PaperSelPaper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new BusinessException("PAPER_NOT_FOUND", "卷面不存在：id=" + paperId);
        }
        return paper;
    }

    // ---------- 方案构建（预检/执行共用，保证两者一致） ----------

    private Plan buildPlan(ComposeRequest request) {
        String projectCode = normalizeProject(request.getProjectCode());
        List<QuotaItem> quotas = normalizeQuotas(request.getQuotas());

        // 冻结在池素材快照（池子增删改从此刻与本次组卷无关）
        List<PaperMaterial> materials = materialPoolService.listEnabled(projectCode);
        List<MaterialSnapshot> poolSnapshots = materialPoolService.toSnapshots(materials);
        String poolFingerprint = Fingerprints.poolFingerprint(poolSnapshots);

        // 解析生效算法版本；无生效版用内置默认口径
        PaperSelAlgorithmVersion version = algorithmService.findActiveVersion(projectCode);
        PaperSelParams params = version == null
                ? algorithmService.defaultParams()
                : algorithmService.readParams(version.getParams());
        String selVersionNo = version == null ? BUILTIN_VERSION_NO : version.getVersionNo();

        List<PaperSelector.PoolItem> engineItems = toEngineItems(poolSnapshots);

        // 池子不足先逐项报缺口（引擎也兜底，预检在此即可给出同样提示）
        List<PaperSelector.Shortage> shortages = PaperSelector.checkPool(engineItems, quotas);
        if (!shortages.isEmpty()) {
            throw new BusinessException("POOL_INSUFFICIENT",
                    PaperSelector.buildShortageMessage(shortages));
        }

        long rootSeed = resolveSeed(request.getRootSeed(), projectCode, request.getPaperCount(),
                quotas, poolFingerprint);

        PaperSelector.Result result = PaperSelector.compose(engineItems, quotas,
                new PaperSelector.Params(params.getMaxOverlapRate(), params.getMaxRetries()),
                rootSeed, request.getPaperCount());

        // 余量/重抽提示（不是错误，教研会可据此评估池子充裕度）
        List<String> warnings = buildWarnings(engineItems, quotas, result, params);

        Plan plan = new Plan();
        plan.projectCode = projectCode;
        plan.quotas = quotas;
        plan.poolSnapshots = poolSnapshots;
        plan.poolFingerprint = poolFingerprint;
        plan.version = version;
        plan.selVersionNo = selVersionNo;
        plan.params = params;
        plan.paramsJson = algorithmService.writeParams(params);
        plan.rootSeed = rootSeed;
        plan.result = result;
        plan.warnings = warnings;

        for (PaperSelector.Paper p : result.papers) {
            List<MaterialSnapshot> selected = new ArrayList<>();
            for (PaperSelector.PoolItem item : p.selected) {
                MaterialSnapshot s = new MaterialSnapshot();
                s.setMaterialId(item.id);
                s.setMaterialNo(item.materialNo);
                s.setKnowledgePoint(item.knowledgePoint);
                s.setDifficulty(item.difficulty);
                s.setContentHash(item.contentHash);
                selected.add(s);
            }
            plan.paperSnapshots.add(selected);
            plan.paperFingerprints.add(Fingerprints.paperFingerprint(
                    selVersionNo, rootSeed, p.seed, poolFingerprint, selected));
        }
        return plan;
    }

    /** 配比规范化：trim、难度大写、同一 (知识点,难度档) 不允许重复配比项。 */
    private List<QuotaItem> normalizeQuotas(List<QuotaItem> raw) {
        List<QuotaItem> quotas = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (QuotaItem q : raw) {
            if (q.getKnowledgePoint() == null || q.getKnowledgePoint().trim().isEmpty()) {
                throw new BusinessException("QUOTA_KP_REQUIRED", "配比知识点不能为空");
            }
            if (q.getDifficulty() == null || q.getDifficulty().trim().isEmpty()) {
                throw new BusinessException("QUOTA_DIFFICULTY_REQUIRED", "配比难度档不能为空");
            }
            if (q.getCount() == null || q.getCount() < 1) {
                throw new BusinessException("QUOTA_COUNT_INVALID", "配比数量必须 ≥ 1");
            }
            QuotaItem item = new QuotaItem();
            item.setKnowledgePoint(q.getKnowledgePoint().trim());
            item.setDifficulty(q.getDifficulty().trim().toUpperCase());
            item.setCount(q.getCount());
            String key = item.getKnowledgePoint() + "\0" + item.getDifficulty();
            if (!seen.add(key)) {
                throw new BusinessException("QUOTA_DUPLICATED",
                        "配比存在重复档位：" + item.getKnowledgePoint() + "/" + item.getDifficulty()
                                + "，请合并为一项");
            }
            quotas.add(item);
        }
        return quotas;
    }

    private List<String> buildWarnings(List<PaperSelector.PoolItem> pool, List<QuotaItem> quotas,
                                       PaperSelector.Result result, PaperSelParams params) {
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> available = new LinkedHashMap<>();
        for (PaperSelector.PoolItem item : pool) {
            String key = item.knowledgePoint.trim() + "\0" + item.difficulty.trim().toUpperCase();
            available.merge(key, 1, Integer::sum);
        }
        for (QuotaItem q : quotas) {
            String key = q.getKnowledgePoint() + "\0" + q.getDifficulty();
            int have = available.getOrDefault(key, 0);
            if (have <= q.getCount()) {
                warnings.add(String.format("知识点[%s] 难度[%s] 池子余量紧张：在池 %d 道，单套需 %d 道",
                        q.getKnowledgePoint(), q.getDifficulty(), have, q.getCount()));
            }
        }
        int maxRetry = 0;
        int retriedPapers = 0;
        for (PaperSelector.Paper p : result.papers) {
            maxRetry = Math.max(maxRetry, p.retries);
            if (p.retries > 0) {
                retriedPapers++;
            }
        }
        if (retriedPapers > 0) {
            warnings.add(String.format("共 %d 套触发自动重抽，单套最多重抽 %d 次（上限 %d）",
                    retriedPapers, maxRetry, params.getMaxRetries()));
        }
        return warnings;
    }

    private List<PaperSelector.PoolItem> toEngineItems(List<MaterialSnapshot> snapshots) {
        List<PaperSelector.PoolItem> items = new ArrayList<>();
        for (MaterialSnapshot s : snapshots) {
            items.add(new PaperSelector.PoolItem(s.getMaterialId(), s.getMaterialNo(),
                    s.getKnowledgePoint(), s.getDifficulty(), s.getContentHash()));
        }
        return items;
    }

    private boolean sameSelection(List<MaterialSnapshot> stored, List<PaperSelector.PoolItem> recomputed) {
        if (stored.size() != recomputed.size()) {
            return false;
        }
        for (int i = 0; i < stored.size(); i++) {
            MaterialSnapshot a = stored.get(i);
            PaperSelector.PoolItem b = recomputed.get(i);
            if (!a.getMaterialId().equals(b.id) || !strEq(a.getContentHash(), b.contentHash)) {
                return false;
            }
        }
        return true;
    }

    private static boolean strEq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private ComposeView toView(Plan plan, PaperSelRun run) {
        ComposeView view = new ComposeView();
        view.setProjectCode(plan.projectCode);
        view.setPaperCount(plan.result.papers.size());
        view.setPaperSize(plan.result.paperSize);
        view.setSelVersionNo(plan.selVersionNo);
        view.setRootSeed(plan.rootSeed);
        view.setPoolFingerprint(plan.poolFingerprint);
        view.setPoolSize(plan.poolSnapshots.size());
        view.setWarnings(plan.warnings);
        if (run != null) {
            view.setRunId(run.getId());
            view.setRunNo(run.getRunNo());
            view.setGroupCode(run.getGroupCode());
        }
        List<ComposedPaperView> papers = new ArrayList<>();
        for (PaperSelector.Paper p : plan.result.papers) {
            ComposedPaperView v = new ComposedPaperView();
            v.setPaperIndex(p.index);
            v.setSeed(p.seed);
            v.setSelected(plan.paperSnapshots.get(p.index));
            v.setPaperFingerprint(plan.paperFingerprints.get(p.index));
            v.setOverlapMaxRate(BigDecimal.valueOf(p.overlapMaxRate));
            v.setOverlapDetail(toOverlapViews(p.overlaps));
            v.setRetries(p.retries);
            if (run != null) {
                v.setPaperNo(run.getRunNo() + "-" + String.format("%02d", p.index + 1));
            }
            papers.add(v);
        }
        view.setPapers(papers);
        return view;
    }

    private List<OverlapView> toOverlapViews(List<PaperSelector.Overlap> overlaps) {
        List<OverlapView> views = new ArrayList<>();
        for (PaperSelector.Overlap o : overlaps) {
            OverlapView v = new OverlapView();
            v.setPaperIndex(o.paperIndex);
            v.setShared(o.shared);
            v.setRate(BigDecimal.valueOf(o.rate));
            views.add(v);
        }
        return views;
    }

    private PaperSelRun requireRun(Long runId) {
        if (runId == null) {
            throw new BusinessException("RUN_ID_REQUIRED", "组卷运行 id 不能为空");
        }
        PaperSelRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException("PAPER_SEL_RUN_NOT_FOUND", "组卷运行记录不存在：id=" + runId);
        }
        return run;
    }

    /**
     * 根种子：显式指定优先；否则按 项目|套数|配比|池子指纹|历史运行次数 取确定性种子。
     * 同池子同配比"预检→执行"结果一致；池子变动后默认种子随之变化；显式传种子可复现历史。
     */
    private long resolveSeed(Long requested, String projectCode, int paperCount,
                             List<QuotaItem> quotas, String poolFingerprint) {
        if (requested != null) {
            return requested;
        }
        Long runCount = runMapper.selectCount(
                new QueryWrapper<PaperSelRun>().eq("project_code", projectCode));
        StringBuilder basis = new StringBuilder(projectCode)
                .append('|').append(paperCount).append('|').append(poolFingerprint)
                .append('|').append(runCount == null ? 0 : runCount).append('|');
        for (QuotaItem q : quotas) {
            basis.append(q.getKnowledgePoint()).append(':')
                    .append(q.getDifficulty()).append('=').append(q.getCount()).append(';');
        }
        return fnv1a64(basis.toString());
    }

    private static long fnv1a64(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** 运行单号：ZJ + yyMMddHHmmss + 2 位当日序列。 */
    private String nextRunNo() {
        String prefix = "ZJ" + LocalDateTime.now().format(NO_FMT);
        List<PaperSelRun> samePrefix = runMapper.selectList(
                new QueryWrapper<PaperSelRun>().likeRight("run_no", prefix));
        int max = 0;
        for (PaperSelRun r : samePrefix) {
            String tail = r.getRunNo().substring(prefix.length());
            try {
                max = Math.max(max, Integer.parseInt(tail));
            } catch (NumberFormatException ignored) {
                // 非本规则编号忽略
            }
        }
        return prefix + String.format("%02d", max + 1);
    }

    private PaperSelParams readParams(String json) {
        try {
            PaperSelParams params = objectMapper.readValue(json, new TypeReference<PaperSelParams>() {
            });
            if (params == null) {
                throw new BusinessException("SEL_PARAMS_BROKEN", "算法参数快照为空或已损坏");
            }
            return params;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("SEL_PARAMS_BROKEN", "算法参数快照无法解析：" + e.getMessage());
        }
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type, String code, String label) {
        try {
            List<T> list = objectMapper.readValue(json, type);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            throw new BusinessException(code, label + "无法解析：" + e.getMessage());
        }
    }

    private String writeJson(Object value, String code, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(code, label + "序列化失败：" + e.getMessage());
        }
    }

    private String normalizeProject(String projectCode) {
        if (projectCode == null || projectCode.trim().isEmpty()) {
            return PaperSelConstants.PROJECT_DEFAULT;
        }
        return projectCode.trim().toUpperCase();
    }

    /** 组卷方案内部载体。 */
    private static class Plan {
        private String projectCode;
        private List<QuotaItem> quotas;
        private List<MaterialSnapshot> poolSnapshots;
        private String poolFingerprint;
        private PaperSelAlgorithmVersion version;
        private String selVersionNo;
        private PaperSelParams params;
        private String paramsJson;
        private long rootSeed;
        private PaperSelector.Result result;
        private List<String> warnings = new ArrayList<>();
        private final List<List<MaterialSnapshot>> paperSnapshots = new ArrayList<>();
        private final List<String> paperFingerprints = new ArrayList<>();
    }
}
