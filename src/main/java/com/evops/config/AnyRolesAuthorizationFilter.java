package com.evops.config;

import com.evops.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.filter.authz.AuthorizationFilter;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 任一角色满足即放行（OR 语义；Shiro 内置 roles[..] 为 AND）。
 * 用于封存记录窗口：system（内部管理）或 discipline_archiver（违纪档案授权岗）均可查；
 * 已认证但角色不符返回可读 JSON 403。
 */
public class AnyRolesAuthorizationFilter extends AuthorizationFilter {

    private final ObjectMapper objectMapper;

    public AnyRolesAuthorizationFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) {
        Subject subject = getSubject(request, response);
        String[] roles = (String[]) mappedValue;
        if (roles == null || roles.length == 0) {
            return true;
        }
        for (String role : roles) {
            if (subject.hasRole(role)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws IOException {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setStatus(403);
        httpResponse.setContentType("application/json;charset=UTF-8");
        httpResponse.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.fail("FORBIDDEN", "已认证但无权访问该资源（封存记录仅违纪档案授权岗可查）")));
        return false;
    }
}
