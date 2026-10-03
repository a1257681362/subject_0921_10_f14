package com.evops.invigilatefee.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.exam.entity.ExamSite;
import com.evops.exam.mapper.ExamSiteMapper;
import com.evops.invigilatefee.common.InvigilateFeeConstants;
import com.evops.invigilatefee.dto.SiteDistanceRequest;
import com.evops.invigilatefee.entity.SiteDistance;
import com.evops.invigilatefee.mapper.SiteDistanceMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 考点路程主数据：维护出发地 → 考点的单程公里数，是按里程试算交通补贴的唯一口径来源。
 * 同一起讫唯一；停用后不再参与试算（历史报销已冻结里程，不受影响）。
 */
@Service
public class SiteDistanceService {

    private final SiteDistanceMapper distanceMapper;
    private final ExamSiteMapper siteMapper;

    public SiteDistanceService(SiteDistanceMapper distanceMapper, ExamSiteMapper siteMapper) {
        this.distanceMapper = distanceMapper;
        this.siteMapper = siteMapper;
    }

    @Transactional
    public SiteDistance create(SiteDistanceRequest request) {
        ExamSite site = requireSite(request.getSiteId());
        String originCode = request.getOriginCode().trim();
        SiteDistance existing = distanceMapper.selectOne(new QueryWrapper<SiteDistance>()
                .eq("origin_code", originCode)
                .eq("site_id", site.getId())
                .last("LIMIT 1"));
        if (existing != null) {
            // 同一起讫已存在：启用中拒绝重复建档；已停用则重新启用并更新里程，沿用原 id
            if (InvigilateFeeConstants.ENABLED.equals(existing.getStatus())) {
                throw new BusinessException("DUPLICATE_SITE_DISTANCE",
                        "出发地 " + originCode + " 到考点 " + site.getCode() + " 的路程已存在，不能重复建档");
            }
            existing.setOriginName(request.getOriginName().trim());
            existing.setSiteName(site.getName());
            existing.setDistanceKm(request.getDistanceKm());
            existing.setStatus(InvigilateFeeConstants.ENABLED);
            existing.setRemark(request.getRemark());
            distanceMapper.updateById(existing);
            return existing;
        }

        SiteDistance distance = new SiteDistance();
        distance.setOriginCode(originCode);
        distance.setOriginName(request.getOriginName().trim());
        distance.setSiteId(site.getId());
        distance.setSiteCode(site.getCode());
        distance.setSiteName(site.getName());
        distance.setDistanceKm(request.getDistanceKm());
        distance.setStatus(InvigilateFeeConstants.ENABLED);
        distance.setRemark(request.getRemark());
        try {
            distanceMapper.insert(distance);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("DUPLICATE_SITE_DISTANCE",
                    "出发地 " + originCode + " 到考点 " + site.getCode() + " 的路程已存在");
        }
        return distance;
    }

    /** 停用：停用后该起讫不再参与试算。 */
    @Transactional
    public SiteDistance disable(Long id) {
        SiteDistance distance = requireDistance(id);
        distance.setStatus(InvigilateFeeConstants.DISABLED);
        distanceMapper.updateById(distance);
        return distance;
    }

    @Transactional(readOnly = true)
    public List<SiteDistance> list(Long siteId, String originCode) {
        QueryWrapper<SiteDistance> wrapper = new QueryWrapper<SiteDistance>()
                .orderByAsc("site_id", "origin_code");
        if (siteId != null) {
            wrapper.eq("site_id", siteId);
        }
        if (originCode != null && !originCode.trim().isEmpty()) {
            wrapper.eq("origin_code", originCode.trim());
        }
        return distanceMapper.selectList(wrapper);
    }

    /** 取某出发地到某考点的启用路程，供试算（缺失即拒绝申请，先建主数据）。 */
    @Transactional(readOnly = true)
    public SiteDistance requireEnabled(String originCode, Long siteId) {
        if (originCode == null || originCode.trim().isEmpty()) {
            throw new BusinessException("ORIGIN_CODE_REQUIRED", "出发地编码不能为空");
        }
        SiteDistance distance = distanceMapper.selectOne(new QueryWrapper<SiteDistance>()
                .eq("origin_code", originCode.trim())
                .eq("site_id", siteId)
                .last("LIMIT 1"));
        if (distance == null) {
            throw new BusinessException("SITE_DISTANCE_NOT_FOUND",
                    "出发地 " + originCode.trim() + " 到考点 id=" + siteId + " 的路程未建档，请先维护考点里程");
        }
        if (!InvigilateFeeConstants.ENABLED.equals(distance.getStatus())) {
            throw new BusinessException("SITE_DISTANCE_DISABLED",
                    "出发地 " + distance.getOriginCode() + " 到考点 " + distance.getSiteCode()
                            + " 的路程已停用，不能用于试算");
        }
        return distance;
    }

    public SiteDistance requireDistance(Long id) {
        if (id == null) {
            throw new BusinessException("SITE_DISTANCE_ID_REQUIRED", "路程 id 不能为空");
        }
        SiteDistance distance = distanceMapper.selectById(id);
        if (distance == null) {
            throw new BusinessException("SITE_DISTANCE_NOT_FOUND", "考点路程不存在：id=" + id);
        }
        return distance;
    }

    private ExamSite requireSite(Long siteId) {
        if (siteId == null) {
            throw new BusinessException("SITE_ID_REQUIRED", "考点 id 不能为空");
        }
        ExamSite site = siteMapper.selectById(siteId);
        if (site == null) {
            throw new BusinessException("SITE_NOT_FOUND", "考点不存在：id=" + siteId);
        }
        return site;
    }
}
