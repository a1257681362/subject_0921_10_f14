package com.evops.invigilatefee.common;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/**
 * 视图身份解析：请求头 X-Self-Code 传监考人员编码时为“本人视图”（教师只能提交/查看本人凭证与报销）；
 * 不传则为财务全量视图。鉴权仍走 Shiro HTTP Basic，这里只决定数据可见范围与本人归属。
 */
public final class FeeSelfHolder {

    private FeeSelfHolder() {
    }

    /** 返回本人编码；为空表示财务全量视图。 */
    public static String currentSelfCode() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            String code = request.getHeader(InvigilateFeeConstants.SELF_HEADER);
            if (isBlank(code)) {
                code = request.getParameter("selfCode");
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
