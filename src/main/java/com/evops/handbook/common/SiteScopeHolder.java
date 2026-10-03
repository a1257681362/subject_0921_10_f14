package com.evops.handbook.common;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/**
 * 考点范围解析：请求头 X-Site-Code 传考点编码时为“考点视图”，
 * 只可查看 / 下载本考点手册；不传为考务全量视图。鉴权仍走 Shiro HTTP Basic。
 */
public final class SiteScopeHolder {

    private SiteScopeHolder() {
    }

    /** 返回考点编码；为空表示考务全量视图。 */
    public static String currentSiteCode() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            String code = request.getHeader(HandbookConstants.SITE_SCOPE_HEADER);
            if (isBlank(code)) {
                code = request.getParameter("siteCode");
            }
            return isBlank(code) ? null : code.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
