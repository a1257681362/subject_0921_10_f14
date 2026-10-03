package com.evops.handbook.service;

import com.evops.handbook.entity.HandbookRelease;
import com.evops.handbook.entity.HandbookVersion;
import com.evops.handbook.entity.InvigilationHandbook;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

/**
 * 手册打印版渲染：放行时生成一次并冻结进 t_handbook_version.print_html，
 * 后续调整只影响新版本，历史打印版原样可下载。纯字符串渲染，不依赖请求上下文，
 * 调度器补发 / 归档与下载接口共用同一冻结结果。
 */
@Service
public class HandbookPrintService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 生成打印版 HTML（浏览器直接打印 / 另存 PDF）。 */
    public String renderPrintHtml(InvigilationHandbook handbook, HandbookVersion version, HandbookRelease release) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"/>");
        sb.append("<title>监考手册-").append(esc(handbook.getBatchCode())).append("</title><style>");
        sb.append("body{font-family:\"Microsoft YaHei\",\"PingFang SC\",\"SimSun\",sans-serif;color:#000;margin:0;padding:24px;}");
        sb.append(".toolbar{margin-bottom:16px;}.toolbar button{padding:8px 20px;font-size:14px;cursor:pointer;}");
        sb.append("h1{font-size:22px;margin:0 0 6px;text-align:center;letter-spacing:6px;}");
        sb.append(".sub{text-align:center;font-size:13px;color:#333;margin-bottom:14px;}");
        sb.append(".meta{font-size:13px;margin-bottom:10px;}.meta span{display:inline-block;margin-right:18px;}");
        sb.append(".block{margin:18px 0;}.block h2{font-size:16px;border-left:4px solid #000;padding-left:8px;margin:0 0 8px;}");
        sb.append(".kv{font-size:14px;margin:6px 0;}.kv b{display:inline-block;min-width:96px;}");
        sb.append(".post{border:1px solid #000;padding:12px 14px;font-size:14px;line-height:1.9;white-space:pre-wrap;}");
        sb.append(".seal{margin-top:36px;font-size:13px;}.seal span{display:inline-block;margin-right:48px;}");
        sb.append("@media print{.toolbar{display:none;}body{padding:0;}}");
        sb.append("</style></head><body>");
        sb.append("<div class=\"toolbar\"><button onclick=\"window.print()\">打印 / 另存为 PDF</button></div>");
        sb.append("<h1>监 考 手 册</h1>");
        sb.append("<div class=\"sub\">考试机构监考岗位工作手册（凭考点范围下载）</div>");

        sb.append("<div class=\"meta\">");
        sb.append("<span>发布批次：<b>").append(esc(release.getReleaseNo())).append("</b></span>");
        sb.append("<span>手册版本：<b>V").append(version.getVersionNo()).append("</b></span>");
        sb.append("<span>考试批次：<b>").append(esc(handbook.getBatchCode()))
                .append(handbook.getBatchName() == null ? "" : " " + esc(handbook.getBatchName())).append("</b></span>");
        sb.append("</div><div class=\"meta\">");
        sb.append("<span>考点：<b>").append(esc(handbook.getSiteName())).append("（")
                .append(esc(handbook.getSiteCode())).append("）</b></span>");
        if (handbook.getProjectCode() != null) {
            sb.append("<span>项目：<b>").append(esc(handbook.getProjectCode())).append("</b></span>");
        }
        sb.append("<span>生效时刻：<b>").append(fmt(release.getEffectiveAt())).append("</b></span>");
        sb.append("<span>经办人：<b>").append(esc(release.getOperator())).append("</b></span>");
        sb.append("</div>");

        sb.append("<div class=\"block\"><h2>一、岗位说明</h2>");
        sb.append("<div class=\"post\">").append(esc(version.getPostDescription())).append("</div></div>");

        sb.append("<div class=\"block\"><h2>二、签到地点</h2>");
        sb.append("<div class=\"kv\">").append(esc(version.getSignInLocation())).append("</div></div>");

        sb.append("<div class=\"block\"><h2>三、应急联系人</h2>");
        sb.append("<div class=\"kv\"><b>联系人：</b>").append(esc(version.getEmergencyContact())).append("</div>");
        sb.append("<div class=\"kv\"><b>联系电话：</b>").append(esc(version.getEmergencyPhone())).append("</div></div>");

        sb.append("<div class=\"seal\">");
        sb.append("<span>打印版生成时间：").append(fmt(version.getPrintGeneratedAt())).append("</span>");
        sb.append("<span>放行时间：").append(fmt(version.getReleasedAt())).append("</span>");
        if ("REISSUE".equals(release.getKind())) {
            sb.append("<span>补发时间：").append(fmt(release.getReleasedAt())).append("</span>");
            sb.append("<span>补发经办：").append(esc(release.getReissuedBy())).append("</span>");
        }
        sb.append("</div>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private String fmt(java.time.LocalDateTime time) {
        return time == null ? "" : time.format(DT);
    }

    private String esc(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
