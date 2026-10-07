package com.fc.v2.service.impl;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TGiprTermBookMapper;
import com.fc.v2.mapper.auto.TGiprProductBookMapper;
import com.fc.v2.model.auto.TGiprTermBook;
import com.fc.v2.model.auto.TGiprProductBook;
import com.fc.v2.service.ITGiprTermBookService;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 名录对外公示多语言词条册Service业务层处理
 *
 * <p>与名录底册联动（新增/修改同一把尺，别只改一处）：</p>
 * <ul>
 *   <li>挂靠的名录产品必须在册（已移出的那一段不再收新行、也不许改挂过去）；</li>
 *   <li>prod_no / prod_name 以名录底册为准回写，不留手填口径；</li>
 *   <li>lack_num 轧账口径：应收录 - 已对齐，负数按 0（新增/修改同一算法）。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TGiprTermBookServiceImpl extends ServiceImpl<TGiprTermBookMapper, TGiprTermBook> implements ITGiprTermBookService {

    @Autowired
    private TGiprProductBookMapper giprProductBookMapper;

    @Override
    public TGiprTermBook selectTGiprTermBookById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TGiprTermBook>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TGiprTermBook> selectTGiprTermBookList(Wrapper<TGiprTermBook> queryWrapper) {
        // 明细与分页统计同源：用调用方传入的条件，只统一兜未删标记；
        // 分页由调用方（controller startPage）负责，这里不再另起一页
        QueryWrapper<TGiprTermBook> wrapper = queryWrapper instanceof QueryWrapper
                ? (QueryWrapper<TGiprTermBook>) queryWrapper
                : new QueryWrapper<TGiprTermBook>();
        wrapper.eq("del_flag", 0);
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTGiprTermBook(TGiprTermBook record) {
        if (record == null) {
            return 0;
        }
        TGiprProductBook refArch = checkProductRef(record.getProdId());
        if (refArch == null) {
            return 0;
        }
        record.setProdNo(refArch.getProdNo());
        record.setProdName(refArch.getProdName());
        if (StringUtils.isNotEmpty(record.getTermNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TGiprTermBook>()
                    .eq("term_no", record.getTermNo()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }
        record.setLackNum(lackOf(record.getCrtNum(), record.getHitNum()));
        record.setDelFlag(0);
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTGiprTermBook(TGiprTermBook record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        TGiprTermBook db = this.baseMapper.selectOne(new QueryWrapper<TGiprTermBook>()
                .eq("id", record.getId()).eq("del_flag", 0));
        if (db == null) {
            return 0;
        }
        if (StringUtils.isNotEmpty(record.getTermNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TGiprTermBook>()
                    .eq("term_no", record.getTermNo()).ne("id", record.getId()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }
        if (record.getProdId() != null) {
            // 改挂名录：与新增同一把尺——必须在册，并以底册为准回写代号/全称
            TGiprProductBook refArch = checkProductRef(record.getProdId());
            if (refArch == null) {
                return 0;
            }
            record.setProdNo(refArch.getProdNo());
            record.setProdName(refArch.getProdName());
        }
        // 缺额按合并后的有效值重轧（没传就取库里现值），与新增同一算法
        Integer crt = record.getCrtNum() != null ? record.getCrtNum() : db.getCrtNum();
        Integer hit = record.getHitNum() != null ? record.getHitNum() : db.getHitNum();
        record.setLackNum(lackOf(crt, hit));

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TGiprTermBook>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    @Override
    public int deleteTGiprTermBookByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        return this.baseMapper.deleteBatchIds(Arrays.asList(idArr));
    }

    @Override
    public int deleteTGiprTermBookById(Long id) {
        return this.baseMapper.deleteById(id);
    }

    /** 名录联动校验：产品存在且未删、未移出（status=1 已移出的那一段不再收行）；通过返回底册行 */
    private TGiprProductBook checkProductRef(Integer prodId) {
        if (prodId == null) {
            return null;
        }
        TGiprProductBook refArch = giprProductBookMapper.selectOne(new QueryWrapper<TGiprProductBook>()
                .eq("id", prodId).eq("del_flag", 0));
        if (refArch == null) {
            return null;
        }
        if (refArch.getStatus() != null && refArch.getStatus() == 1) {
            return null;
        }
        return refArch;
    }

    /** 还差几条 = 应收录 - 已对齐，负数按 0（轧出来，不留填数的空位） */
    private static Integer lackOf(Integer crtNum, Integer hitNum) {
        int crt = crtNum == null ? 0 : crtNum;
        int hit = hitNum == null ? 0 : hitNum;
        return Math.max(crt - hit, 0);
    }
}
