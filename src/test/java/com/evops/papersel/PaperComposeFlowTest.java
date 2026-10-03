package com.evops.papersel;

import com.evops.common.BusinessException;
import com.evops.exam.entity.WorkspaceVersion;
import com.evops.exam.service.WorkspaceVersionService;
import com.evops.papersel.common.PaperSelConstants;
import com.evops.papersel.dto.ComposeRequest;
import com.evops.papersel.dto.MaterialDisableRequest;
import com.evops.papersel.dto.MaterialRequest;
import com.evops.papersel.dto.MaterialUpdateRequest;
import com.evops.papersel.dto.PaperSelAlgorithmRequest;
import com.evops.papersel.dto.PaperSelAlgorithmVersionRequest;
import com.evops.papersel.dto.PaperSelParams;
import com.evops.papersel.dto.QuotaItem;
import com.evops.papersel.entity.PaperMaterial;
import com.evops.papersel.entity.PaperMaterialChange;
import com.evops.papersel.entity.PaperSelAlgorithm;
import com.evops.papersel.entity.PaperSelAlgorithmVersion;
import com.evops.papersel.entity.PaperSelPaper;
import com.evops.papersel.entity.PaperSelRun;
import com.evops.papersel.service.ComposeService;
import com.evops.papersel.service.MaterialPoolService;
import com.evops.papersel.service.PaperSelAlgorithmService;
import com.evops.papersel.vo.ComposeView;
import com.evops.papersel.vo.ComposedPaperView;
import com.evops.papersel.vo.FairnessVerifyView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 教研组卷抽选端到端：
 * 素材池增删留痕 → 算法三段式 → 知识点+难度配比多套抽选（种子/指纹）→ 超阈自动重抽/耗尽拒收/池子不足提示
 * → 同种子可复现 → 事后复算验公平 → 池子增删不影响历史卷面 → 工作区快照回退。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaperComposeFlowTest {

    private static final String[] KPS = {"法规", "实务", "安全"};
    private static final String[] DIFFS = {"EASY", "MEDIUM", "HARD"};

    @Autowired
    private MaterialPoolService materialPoolService;
    @Autowired
    private PaperSelAlgorithmService algorithmService;
    @Autowired
    private ComposeService composeService;
    @Autowired
    private WorkspaceVersionService workspaceVersionService;

    // ---------- 辅助 ----------

    private void seedMaterials(String project, int perBucket) {
        int seq = 0;
        for (String kp : KPS) {
            for (String diff : DIFFS) {
                for (int i = 1; i <= perBucket; i++) {
                    seq++;
                    MaterialRequest req = new MaterialRequest();
                    req.setMaterialNo(project + "-M" + String.format("%03d", seq));
                    req.setProjectCode(project);
                    req.setKnowledgePoint(kp);
                    req.setDifficulty(diff);
                    req.setContent(project + " 题干 " + kp + "/" + diff + "/" + i);
                    req.setReferenceAnswer("答案 " + i);
                    materialPoolService.create(req);
                }
            }
        }
    }

    private List<QuotaItem> standardQuotas(int count) {
        List<QuotaItem> quotas = new ArrayList<>();
        for (String kp : KPS) {
            for (String diff : DIFFS) {
                QuotaItem q = new QuotaItem();
                q.setKnowledgePoint(kp);
                q.setDifficulty(diff);
                q.setCount(count);
                quotas.add(q);
            }
        }
        return quotas;
    }

    private PaperSelAlgorithmVersion publishAlgorithm(String code, String project,
                                                      double overlap, int retries) {
        PaperSelAlgorithmRequest ar = new PaperSelAlgorithmRequest();
        ar.setCode(code);
        ar.setName("抽选算法-" + code);
        ar.setProjectCode(project);
        PaperSelAlgorithm algorithm = algorithmService.createAlgorithm(ar);

        PaperSelParams params = new PaperSelParams();
        params.setStrategy(PaperSelConstants.STRATEGY_KP_DIFFICULTY_QUOTA);
        params.setMaxOverlapRate(overlap);
        params.setMaxRetries(retries);
        params.setCompareScope(PaperSelConstants.COMPARE_SCOPE_GROUP);
        PaperSelAlgorithmVersionRequest vr = new PaperSelAlgorithmVersionRequest();
        vr.setParams(params);
        PaperSelAlgorithmVersion draft = algorithmService.createDraftVersion(algorithm.getId(), vr);
        return algorithmService.publishVersion(draft.getId());
    }

    private ComposeRequest composeRequest(String project, int paperCount, long seed, List<QuotaItem> quotas) {
        ComposeRequest req = new ComposeRequest();
        req.setProjectCode(project);
        req.setPaperCount(paperCount);
        req.setGroupCode(project + "-GROUP");
        req.setRootSeed(seed);
        req.setQuotas(quotas);
        return req;
    }

    // ---------- 用例 ----------

    /** 主流程：配比抽题、多套不超阈、种子指纹落档、预检与执行一致、事后复算公平。 */
    @Test
    void composeMultiPapersThenVerifyFairness() {
        String project = "PMAIN";
        seedMaterials(project, 8);
        publishAlgorithm("ALG-" + project, project, 0.30d, 50);

        long seed = 123456789L;
        ComposeRequest req = composeRequest(project, 4, seed, standardQuotas(2));

        // 预检不落库
        ComposeView preview = composeService.preview(req);
        assertNull(preview.getRunId());
        assertEquals(4, preview.getPaperCount());
        assertEquals(18, preview.getPaperSize());
        assertEquals(4, preview.getPapers().size());

        // 执行与预检同种子同池子 → 卷面完全一致
        ComposeView executed = composeService.compose(req);
        assertNotNull(executed.getRunId());
        for (int i = 0; i < 4; i++) {
            ComposedPaperView p = preview.getPapers().get(i);
            ComposedPaperView e = executed.getPapers().get(i);
            assertEquals(p.getSeed(), e.getSeed());
            assertEquals(p.getPaperFingerprint(), e.getPaperFingerprint());
            assertEquals(p.getSelected(), e.getSelected());
            // 每套与此前各套重合率全部 ≤ 30%（即 18 道里最多 5 道重合）
            assertTrue(e.getOverlapMaxRate().doubleValue() <= 0.30d + 1e-9,
                    "第 " + i + " 套重合率超阈：" + e.getOverlapMaxRate());
            assertNotNull(e.getPaperNo());
        }
        // 第 0 套没有此前套可比对，最大重合率为 0
        assertEquals(0d, executed.getPapers().get(0).getOverlapMaxRate().doubleValue(), 1e-9);

        // 运行记录落档：版本号、根种子、池子指纹、卷面指纹
        PaperSelRun run = composeService.getRun(executed.getRunId());
        assertEquals("ALG-" + project + "-A1", run.getSelVersionNo());
        assertEquals(seed, run.getRootSeed());
        assertNotNull(run.getPoolFingerprint());
        assertEquals(64, run.getPoolFingerprint().length());
        List<PaperSelPaper> papers = composeService.listPapers(run.getId());
        assertEquals(4, papers.size());
        for (PaperSelPaper p : papers) {
            assertNotNull(p.getPaperFingerprint());
            assertEquals(64, p.getPaperFingerprint().length());
            assertNotNull(p.getSelected());
        }

        // 事后公平核验：种子/清单/卷面指纹逐套复算全符
        FairnessVerifyView verify = composeService.verifyFairness(run.getId());
        assertTrue(verify.getFair(), "核验未通过：" + verify.getNotes());
        assertEquals(run.getPoolFingerprint(), verify.getRecomputedPoolFingerprint());
        assertEquals(4, verify.getPapers().size());
        for (FairnessVerifyView.PaperCheck check : verify.getPapers()) {
            assertTrue(check.getSeedMatch());
            assertTrue(check.getSelectionMatch());
            assertTrue(check.getFingerprintMatch());
            assertTrue(check.getFair());
            assertEquals(0, check.getLiveDriftCount());
        }
    }

    /** 同根种子同池子同配比 → 两次组卷卷面指纹一致（抽取可复算）。 */
    @Test
    void sameRootSeedReproducesIdenticalPapers() {
        String project = "PREPRO";
        seedMaterials(project, 8);
        publishAlgorithm("ALG-" + project, project, 0.30d, 50);

        ComposeRequest req = composeRequest(project, 3, 999L, standardQuotas(2));
        ComposeView first = composeService.compose(req);
        ComposeView second = composeService.compose(req);
        assertNotEquals(first.getRunId(), second.getRunId());
        for (int i = 0; i < 3; i++) {
            assertEquals(first.getPapers().get(i).getSeed(), second.getPapers().get(i).getSeed());
            assertEquals(first.getPapers().get(i).getPaperFingerprint(),
                    second.getPapers().get(i).getPaperFingerprint());
        }
    }

    /** 池子不足：逐项报缺口，整批拒收不落库。 */
    @Test
    void insufficientPoolRejectedWithPerBucketShortage() {
        String project = "PSHORT";
        // 每档只建 1 道
        seedMaterials(project, 1);
        publishAlgorithm("ALG-" + project, project, 0.20d, 10);

        ComposeRequest req = composeRequest(project, 2, 7L, standardQuotas(2));
        BusinessException ex = assertThrows(BusinessException.class, () -> composeService.preview(req));
        assertEquals("POOL_INSUFFICIENT", ex.getCode());
        // 9 个档全部缺口 1
        assertTrue(ex.getMessage().contains("法规"));
        assertTrue(ex.getMessage().contains("HARD"));
        assertTrue(ex.getMessage().contains("缺口 1"));
        // 整批不落库
        assertTrue(composeService.listRuns(project, null).isEmpty());
    }

    /** 池子只够单套、套间必然全重合：重抽达上限整批拒收，不落库。 */
    @Test
    void overlapCannotBeReducedFailsAfterRetries() {
        String project = "PRETRY";
        // 每档恰好等于需求量 → 每档必被全部抽走，各套必然全同
        seedMaterials(project, 2);
        publishAlgorithm("ALG-" + project, project, 0.10d, 2);

        ComposeRequest req = composeRequest(project, 3, 13L, standardQuotas(2));
        BusinessException ex = assertThrows(BusinessException.class, () -> composeService.compose(req));
        assertEquals("OVERLAP_RETRY_EXHAUSTED", ex.getCode());
        assertTrue(ex.getMessage().contains("第 2 套"));
        assertTrue(composeService.listRuns(project, null).isEmpty());
    }

    /** 素材池增删改留台账；事后池子变动不影响历史卷面复算，漂移只作提示。 */
    @Test
    void poolChangesKeepHistoryAndAreAllLogged() {
        String project = "PDRIFT";
        seedMaterials(project, 8);
        publishAlgorithm("ALG-" + project, project, 0.30d, 50);

        ComposeRequest req = composeRequest(project, 3, 2026L, standardQuotas(2));
        ComposeView composed = composeService.compose(req);
        Long runId = composed.getRunId();
        Long firstMaterialId = composed.getPapers().get(0).getSelected().get(0).getMaterialId();

        // 组卷后修改一道入卷素材的题干（内容哈希变化）+ 下架另一道
        MaterialUpdateRequest update = new MaterialUpdateRequest();
        update.setContent("题干已被教研组修订，内容与组卷时不同");
        update.setReason("教研修订");
        materialPoolService.update(firstMaterialId, update);

        Long secondMaterialId = composed.getPapers().get(1).getSelected().get(0).getMaterialId();
        MaterialDisableRequest disable = new MaterialDisableRequest();
        disable.setReason("超纲下架");
        materialPoolService.disable(secondMaterialId, disable);
        // 恢复一次，凑齐 ENABLE 台账类型
        materialPoolService.enable(secondMaterialId, "复核后恢复");

        // 历史卷面依然可复算公平（复算只认冻结快照），但有漂移提示
        FairnessVerifyView verify = composeService.verifyFairness(runId);
        assertTrue(verify.getFair(), verify.getNotes().toString());
        int totalDrift = 0;
        for (FairnessVerifyView.PaperCheck c : verify.getPapers()) {
            totalDrift += c.getLiveDriftCount();
        }
        // 修改的那道在 3 套里出现的次数即漂移条数；恢复的素材回到在池且哈希未变，不计漂移
        assertTrue(totalDrift >= 1, "应检测到内容漂移");
        assertTrue(verify.getNotes().stream().anyMatch(n -> n.contains("素材相对当前池子已变动")));

        // 台账四类齐全（CREATE/UPDATE/DISABLE/ENABLE），只追加
        List<PaperMaterialChange> changes = materialPoolService.listChanges(firstMaterialId);
        assertTrue(changes.stream().anyMatch(c -> PaperSelConstants.CHANGE_CREATE.equals(c.getChangeType())));
        assertTrue(changes.stream().anyMatch(c -> PaperSelConstants.CHANGE_UPDATE.equals(c.getChangeType())));
        List<PaperMaterialChange> secondChanges = materialPoolService.listChanges(secondMaterialId);
        assertTrue(secondChanges.stream().anyMatch(c -> PaperSelConstants.CHANGE_DISABLE.equals(c.getChangeType())));
        assertTrue(secondChanges.stream().anyMatch(c -> PaperSelConstants.CHANGE_ENABLE.equals(c.getChangeType())));
        for (PaperMaterialChange c : changes) {
            assertNotNull(c.getAfterSnapshot());
            assertNotNull(c.getChangedAt());
        }
        // 下架的素材仍在库里（不物理删除）
        PaperMaterial reDisabled = materialPoolService.getMaterial(secondMaterialId);
        assertNotNull(reDisabled);
    }

    /** 算法升级：发布新版本只影响新组卷；旧卷面按冻结参数快照复算仍公平。 */
    @Test
    void algorithmVersionUpgradeDoesNotChangeHistory() {
        String project = "PVER";
        seedMaterials(project, 8);
        PaperSelAlgorithmVersion v1 = publishAlgorithm("ALG-" + project, project, 0.30d, 50);

        ComposeRequest req = composeRequest(project, 3, 88L, standardQuotas(2));
        ComposeView first = composeService.compose(req);
        assertEquals("ALG-" + project + "-A1", first.getSelVersionNo());

        // 建第二版并发布：旧 ACTIVE 自动 DISABLED（放宽阈值到 40%，口径变化只管新组卷）
        PaperSelParams params = new PaperSelParams();
        params.setMaxOverlapRate(0.40d);
        params.setMaxRetries(80);
        PaperSelAlgorithmVersionRequest vr = new PaperSelAlgorithmVersionRequest();
        vr.setParams(params);
        PaperSelAlgorithmVersion v2Draft = algorithmService.createDraftVersion(v1.getAlgorithmId(), vr);
        PaperSelAlgorithmVersion v2 = algorithmService.publishVersion(v2Draft.getId());
        assertEquals(PaperSelConstants.VERSION_DISABLED,
                algorithmService.getVersion(v1.getId()).getStatus());
        assertEquals(PaperSelConstants.VERSION_ACTIVE, v2.getStatus());

        // 新组卷挂新版本
        ComposeView second = composeService.compose(req);
        assertEquals("ALG-" + project + "-A2", second.getSelVersionNo());

        // 旧卷面按 A1 冻结口径复算仍公平
        FairnessVerifyView verify = composeService.verifyFairness(first.getRunId());
        assertTrue(verify.getFair(), verify.getNotes().toString());
        assertEquals("ALG-" + project + "-A1", verify.getSelVersionNo());

        // 停用版不可重新发布
        assertThrows(BusinessException.class, () -> algorithmService.publishVersion(v1.getId()));
    }

    /** 无生效算法版本时用内置默认口径，结果标 BUILTIN_DEFAULT，仍可复算。 */
    @Test
    void builtinDefaultParamsWhenNoActiveVersion() {
        String project = "PBUILTIN";
        seedMaterials(project, 8);
        // 不发布任何算法版本
        ComposeRequest req = composeRequest(project, 2, 5L, standardQuotas(2));
        ComposeView view = composeService.compose(req);
        assertEquals(ComposeService.BUILTIN_VERSION_NO, view.getSelVersionNo());
        FairnessVerifyView verify = composeService.verifyFairness(view.getRunId());
        assertTrue(verify.getFair(), verify.getNotes().toString());
    }

    /** 同一知识点+难度档出现重复配比项整批拒收。 */
    @Test
    void duplicatedQuotaRejected() {
        String project = "PDUP";
        seedMaterials(project, 8);
        List<QuotaItem> quotas = standardQuotas(2);
        QuotaItem dup = new QuotaItem();
        dup.setKnowledgePoint("法规");
        dup.setDifficulty("easy"); // 大小写不同但规范化后同档
        dup.setCount(1);
        quotas.add(dup);
        ComposeRequest req = composeRequest(project, 2, 1L, quotas);
        BusinessException ex = assertThrows(BusinessException.class, () -> composeService.preview(req));
        assertEquals("QUOTA_DUPLICATED", ex.getCode());
    }

    /** 工作区快照与回退：回退后组卷运行与卷面、素材算法一并回到快照时点。 */
    @Test
    void snapshotRollbackRestoresPaperSelTables() {
        String project = "PROLLBACK";
        seedMaterials(project, 8);
        publishAlgorithm("ALG-" + project, project, 0.30d, 50);

        WorkspaceVersion snapshot = workspaceVersionService.createSnapshot("组卷前快照", null);
        ComposeView composed = composeService.compose(
                composeRequest(project, 3, 77L, standardQuotas(2)));
        Long runId = composed.getRunId();
        assertNotNull(composeService.getRun(runId));
        assertEquals(3, composeService.listPapers(runId).size());

        workspaceVersionService.rollback(snapshot.getVersionNo(), "回退到组卷前");

        // 回退后组卷运行与卷面消失
        assertThrows(BusinessException.class, () -> composeService.getRun(runId));
        assertTrue(composeService.listRuns(project, null).isEmpty());
        // 素材与算法仍在（它们属于快照内容）
        assertFalse(materialPoolService.list(project, null, null, null).isEmpty());
        assertEquals(1, algorithmService.listAlgorithms(project).size());
    }
}
