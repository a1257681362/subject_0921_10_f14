package com.evops.invigilatefee;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 交通补贴接口的 Shiro 角色边界（走 /api/** 默认链 authcBasic + roles[system]）：
 * 未认证 401；用人单位 employer 403；内部管理 bootstrap 200。
 * 本人 / 财务的数据可见范围由 X-Self-Code 在服务层判定（见 InvigilateFeeFlowTest）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InvigilateFeeSecurityTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void reimbursementEndpoints_roleBoundaries() {
        String path = "/api/travel-reimbursements";

        assertEquals(HttpStatus.UNAUTHORIZED,
                restTemplate.getForEntity(url(path), String.class).getStatusCode());

        assertEquals(HttpStatus.FORBIDDEN, restTemplate.withBasicAuth("employer", "employer")
                .getForEntity(url(path), String.class).getStatusCode());

        ResponseEntity<String> ok = restTemplate.withBasicAuth("bootstrap", "bootstrap")
                .getForEntity(url(path), String.class);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
    }

    @Test
    void ruleMasterDataEndpoints_requireSystemRole() {
        String path = "/api/travel-allowance-rules";

        assertEquals(HttpStatus.UNAUTHORIZED,
                restTemplate.getForEntity(url(path), String.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, restTemplate.withBasicAuth("archiver", "archiver")
                .getForEntity(url(path), String.class).getStatusCode());
        assertEquals(HttpStatus.OK, restTemplate.withBasicAuth("bootstrap", "bootstrap")
                .getForEntity(url(path), String.class).getStatusCode());
    }
}
