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
 * <p>口径定死：</p>
 * <ul>
 *   <li>列表只看在位未删（status=0 AND del_flag=0），并保留调用方传入的检索条件；
 *       分页由调用方（PageHelper startPage）统一管，Service 不再私自 startPage 把分页冲掉。</li>
 *   <li>还差条数 lack = 应收 crt − 已对齐 hit（轧出来，不留填数的格子），不为负，不以入参为准。</li>
 *   <li>删除一律软删（del_flag=1），环节链/公示历史不断。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TGiprTermBookServiceImpl extends ServiceImpl<TGiprTermBookMapper, TGiprTermBook> implements ITGiprTermBookService {

    private static final int STATUS_ON = 0;

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
        QueryWrapper<TGiprTermBook> wrapper;
        if (queryWrapper instanceof QueryWrapper) {
            wrapper = (QueryWrapper<TGiprTermBook>) queryWrapper;
        } else {
            wrapper = new QueryWrapper<TGiprTermBook>();
        }
        // 统一口径：在位未删；检索条件由调用方给，Service 不覆盖、不私自分页
        wrapper.eq("status", STATUS_ON).eq("del_flag", 0);
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTGiprTermBook(TGiprTermBook record) {
        if (record == null) {
            return 0;
        }

        TGiprProductBook refArch = giprProductBookMapper.selectOne(new QueryWrapper<TGiprProductBook>()
                .eq("id", record.getProdId()).eq("del_flag", 0));
        if (refArch == null) {
            return 0;
        }
        if (refArch.getStatus() != null && refArch.getStatus() == 1) {
            // 挂靠的名录已移出，不再收新词条
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

        if (record.getId() != null && StringUtils.isNotEmpty(record.getTermNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TGiprTermBook>()
                    .eq("term_no", record.getTermNo()).ne("id", record.getId()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }

        // 差额随应收/已对齐重新轧，覆盖入参里手填的值
        record.setLackNum(lackOf(record.getCrtNum(), record.getHitNum()));
        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TGiprTermBook>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    @Override
    public int deleteTGiprTermBookByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        if (idArr == null || idArr.length == 0) {
            return 0;
        }
        // 软删，不物理清掉
        return this.baseMapper.update(null, new UpdateWrapper<TGiprTermBook>()
                .in("id", Arrays.asList(idArr))
                .eq("del_flag", 0)
                .set("del_flag", 1)
                .set("update_time", new Date()));
    }

    @Override
    public int deleteTGiprTermBookById(Long id) {
        if (id == null) {
            return 0;
        }
        return this.baseMapper.update(null, new UpdateWrapper<TGiprTermBook>()
                .eq("id", id)
                .eq("del_flag", 0)
                .set("del_flag", 1)
                .set("update_time", new Date()));
    }

    /** 还差 = 应收 − 已对齐，钳到不小于 0 */
    private int lackOf(Integer crt, Integer hit) {
        int c = crt == null ? 0 : crt;
        int h = hit == null ? 0 : hit;
        return Math.max(0, c - h);
    }
}
