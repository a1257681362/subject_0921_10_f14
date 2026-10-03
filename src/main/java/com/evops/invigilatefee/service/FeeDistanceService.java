package com.evops.invigilatefee.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.exam.entity.ExamSite;
import com.evops.exam.mapper.ExamSiteMapper;
import com.evops.invigilatefee.common.InvigilateFeeConstants;
import com.evops.invigilatefee.dto.FeeDistanceRequest;
import com.evops.invigilatefee.dto.FeeRegionRequest;
import com.evops.invigilatefee.entity.FeeDistance;
import com.evops.invigilatefee.entity.FeeRegion;
import com.evops.invigilatefee.mapper.FeeDistanceMapper;
import com.evops.invigilatefee.mapper.FeeRegionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 跨区交通补贴主数据：出发区域 + 区域→考点距离。
 * 距离为金额计算的唯一距离口径；同对（区域, 考点）唯一；停用不物理删，历史申请保留其冻结公里数。
 */
@Service
public class FeeDistanceService {

    private final FeeRegionMapper regionMapper;
    private final FeeDistanceMapper distanceMapper;
    private final ExamSiteMapper siteMapper;

    public FeeDistanceService(FeeRegionMapper regionMapper,
                              FeeDistanceMapper distanceMapper,
                              ExamSiteMapper siteMapper) {
        this.regionMapper = regionMapper;
        this.distanceMapper = distanceMapper;
        this.siteMapper = siteMapper;
    }

    // ---------- 出发区域 ----------

    @Transactional
    public FeeRegion createRegion(FeeRegionRequest request) {
        FeeRegion region = new FeeRegion();
        region.setCode(request.getCode().trim());
        region.setName(request.getName().trim());
        region.setStatus(InvigilateFeeConstants.ENABLED);
        region.setRemark(request.getRemark());
        try {
            regionMapper.insert(region);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("DUPLICATE_FEE_REGION_CODE", "出发区域编码已存在：" + region.getCode());
        }
        return region;
    }

    @Transactional(readOnly = true)
    public List<FeeRegion> listRegions(String status) {
        QueryWrapper<FeeRegion> wrapper = new QueryWrapper<FeeRegion>().orderByAsc("code");
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq("status", status.trim());
        }
        return regionMapper.selectList(wrapper);
    }

    @Transactional(readOnly = true)
    public FeeRegion getRegion(Long id) {
        FeeRegion region = regionMapper.selectById(id);
        if (region == null) {
            throw new BusinessException("FEE_REGION_NOT_FOUND", "出发区域不存在：id=" + id);
        }
        return region;
    }

    /** 按编码取启用中的出发区域。 */
    @Transactional(readOnly = true)
    public FeeRegion requireEnabledRegion(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new BusinessException("FEE_REGION_CODE_REQUIRED", "出发区域编码不能为空");
        }
        FeeRegion region = regionMapper.selectOne(
                new QueryWrapper<FeeRegion>().eq("code", code.trim()));
        if (region == null) {
            throw new BusinessException("FEE_REGION_NOT_FOUND", "出发区域不存在：" + code.trim());
        }
        if (!InvigilateFeeConstants.ENABLED.equals(region.getStatus())) {
            throw new BusinessException("FEE_REGION_DISABLED", "出发区域已停用：" + region.getCode() + " " + region.getName());
        }
        return region;
    }

    // ---------- 区域→考点距离 ----------

    @Transactional
    public FeeDistance createDistance(FeeDistanceRequest request) {
        FeeRegion region = requireEnabledRegion(request.getRegionCode());
        ExamSite site = siteMapper.selectById(request.getSiteId());
        if (site == null) {
            throw new BusinessException("SITE_NOT_FOUND", "考点不存在：id=" + request.getSiteId());
        }
        BigDecimal km = request.getDistanceKm().setScale(2, BigDecimal.ROUND_HALF_UP);
        if (km.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("FEE_DISTANCE_INVALID", "距离必须大于 0");
        }

        FeeDistance distance = new FeeDistance();
        distance.setRegionId(region.getId());
        distance.setRegionCode(region.getCode());
        distance.setSiteId(site.getId());
        distance.setSiteCode(site.getCode());
        distance.setSiteName(site.getName());
        distance.setDistanceKm(km);
        distance.setStatus(InvigilateFeeConstants.ENABLED);
        distance.setRemark(request.getRemark());
        try {
            distanceMapper.insert(distance);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("DUPLICATE_FEE_DISTANCE",
                    "区域 " + region.getCode() + " 到考点 " + site.getCode() + " 的距离已存在，不能重复建档");
        }
        return distance;
    }

    @Transactional(readOnly = true)
    public List<FeeDistance> listDistances(String regionCode, Long siteId) {
        QueryWrapper<FeeDistance> wrapper = new QueryWrapper<FeeDistance>()
                .orderByAsc("region_code", "site_code");
        if (regionCode != null && !regionCode.trim().isEmpty()) {
            wrapper.eq("region_code", regionCode.trim());
        }
        if (siteId != null) {
            wrapper.eq("site_id", siteId);
        }
        return distanceMapper.selectList(wrapper);
    }

    /**
     * 取某区域到某考点的启用距离；无命中或已停用报错。这是金额计算的唯一距离来源，
     * 刻意不做“就近回落”，防止口径含糊。
     */
    @Transactional(readOnly = true)
    public FeeDistance requireDistance(FeeRegion region, Long siteId) {
        FeeDistance distance = distanceMapper.selectOne(new QueryWrapper<FeeDistance>()
                .eq("region_id", region.getId())
                .eq("site_id", siteId)
                .last("LIMIT 1"));
        if (distance == null) {
            throw new BusinessException("FEE_DISTANCE_NOT_CONFIGURED",
                    "区域 " + region.getCode() + " " + region.getName() + " 到考点 id=" + siteId
                            + " 尚未配置距离，请先建档");
        }
        if (!InvigilateFeeConstants.ENABLED.equals(distance.getStatus())) {
            throw new BusinessException("FEE_DISTANCE_DISABLED",
                    "区域 " + region.getCode() + " 到考点 " + distance.getSiteCode() + " 的距离已停用");
        }
        return distance;
    }
}
