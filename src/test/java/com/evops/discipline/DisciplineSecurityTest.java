package com.evops.discipline;

import com.evops.discipline.common.DisciplineConstants;
import com.evops.discipline.dto.CaseDecisionRequest;
import com.evops.discipline.dto.CaseReportRequest;
import com.evops.discipline.entity.DisciplineCase;
import com.evops.discipline.service.DisciplineCaseService;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.ExamSite;
import com.evops.exam.entity.Registration;
import com.evops.exam.mapper.ExamBatchMapper;
import com.evops.exam.mapper.ExamSiteMapper;
import com.evops.exam.mapper.RegistrationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 封存档案授权边界：
 * - 违纪档案授权岗 archiver 只能访问封存窗口 /api/discipline/sealed/**；访问普通办案接口 403；
 * - 用人单位 employer 不能访问封存窗口（403）；
 * - 内部管理 bootstrap 两个窗口都可访问；未认证 401。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DisciplineSecurityTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private DisciplineCaseService caseService;
    @Autowired
    private ExamSiteMapper siteMapper;
    @Autowired
    private ExamBatchMapper batchMapper;
    @Autowired
    private RegistrationMapper registrationMapper;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Long seedSealedCase() {
        ExamSite site = new ExamSite();
        site.setCode("SDSEC");
        site.setName("封存安全考点");
        site.setStatus("ENABLED");
        siteMapper.insert(site);
        ExamBatch batch = new ExamBatch();
        batch.setCode("BSEC");
        batch.setName("封存安全批次");
        batch.setSiteId(site.getId());
        batch.setProjectCode("DEFAULT");
        batch.setStatus("CREATED");
        batchMapper.insert(batch);
        Registration reg = new Registration();
        reg.setRegNo("SEC001");
        reg.setName("测试学员");
        reg.setBatchId(batch.getId());
        reg.setStatus("REGISTERED");
        registrationMapper.insert(reg);

        CaseReportRequest report = new CaseReportRequest();
        report.setBatchId(batch.getId());
        report.setRegNo("SEC001");
        report.setViolationType("DISORDER");
        report.setDescription("扰乱考场秩序");
        CaseReportRequest.EvidenceItem e = new CaseReportRequest.EvidenceItem();
        e.setEvidenceType("STATEMENT");
        e.setTitle("情况说明");
        e.setContent("口头警告后仍大声喧哗。");
        report.setEvidences(new ArrayList<>(Collections.singletonList(e)));
        DisciplineCase c = caseService.report(report);

        CaseDecisionRequest decision = new CaseDecisionRequest();
        decision.setDecisionType("WARNING");
        decision.setDecisionContent("给予警告。");
        caseService.decide(c.getId(), decision);
        caseService.execute(c.getId(), null);
        caseService.seal(c.getId(), "安全测试归档");
        return c.getId();
    }

    @Test
    void sealedWindow_roleBoundaries() {
        Long caseId = seedSealedCase();
        String listPath = "/api/discipline/sealed/cases";
        String detailPath = "/api/discipline/sealed/cases/" + caseId;
        String adminPath = "/api/discipline/cases";

        // 未认证 → 401
        assertEquals(HttpStatus.UNAUTHORIZED,
                restTemplate.getForEntity(url(listPath), String.class).getStatusCode());

        // 档案岗 archiver：封存窗口可访问（列表含已封存案件，详情 200）
        ResponseEntity<String> archList = restTemplate.withBasicAuth("archiver", "archiver")
                .getForEntity(url(listPath), String.class);
        assertEquals(HttpStatus.OK, archList.getStatusCode());
        assertTrue(archList.getBody().contains(DisciplineConstants.CASE_SEALED));
        assertEquals(HttpStatus.OK, restTemplate.withBasicAuth("archiver", "archiver")
                .getForEntity(url(detailPath), String.class).getStatusCode());

        // 档案岗访问普通办案/管理接口 → 403
        assertEquals(HttpStatus.FORBIDDEN, restTemplate.withBasicAuth("archiver", "archiver")
                .getForEntity(url(adminPath), String.class).getStatusCode());

        // 用人单位 employer 访问封存窗口 → 403
        assertEquals(HttpStatus.FORBIDDEN, restTemplate.withBasicAuth("employer", "employer")
                .getForEntity(url(listPath), String.class).getStatusCode());

        // 内部管理 bootstrap：封存窗口与办案接口都可访问
        assertEquals(HttpStatus.OK, restTemplate.withBasicAuth("bootstrap", "bootstrap")
                .getForEntity(url(listPath), String.class).getStatusCode());
        assertEquals(HttpStatus.OK, restTemplate.withBasicAuth("bootstrap", "bootstrap")
                .getForEntity(url(adminPath), String.class).getStatusCode());
    }
}
