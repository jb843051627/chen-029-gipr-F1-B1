package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TGiprArchiveRowMapper;
import com.fc.v2.model.auto.TGiprArchiveRow;
import com.fc.v2.service.ITGiprArchiveRowService;

/**
 * 历史用标卷宗转档核收行 Service业务层处理（batch-process 形状：整批提交）
 *
 * <p>口径定死：</p>
 * <ul>
 *   <li>空批次返回 0；行数超单批上限 500 返回 -1，且整批一行都不入（失败明细也不落）。</li>
 *   <li>同一批次码重复提交幂等：库里已有该批次的任何行，第二回不重复入库，
 *       直接回该批次已收下的行数。</li>
 *   <li>逐行校验：item_code 非空、qty 非空且为正。合法行置已收下入正库；
 *       非法行置已退回落失败明细，<b>保留原册行次</b>（从 1 起的原始下标，不重新编号）。
 *       合法与非法同批各落各的，互不连坐；返回成功（已收下）行数。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TGiprArchiveRowServiceImpl implements ITGiprArchiveRowService {

    private static final int MAX_ROWS = 500;
    private static final int STATUS_TODO = 0;
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TGiprArchiveRowMapper giprArchiveRowMapper;

    @Override
    public TGiprArchiveRow selectTGiprArchiveRowById(Long id) {
        return this.giprArchiveRowMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int submitBatch(String batchNo, List<TGiprArchiveRow> rows) {
        if (batchNo == null || batchNo.trim().isEmpty() || rows == null || rows.isEmpty()) {
            return 0;
        }
        if (rows.size() > MAX_ROWS) {
            // 超限整批不入库
            return -1;
        }
        // 幂等：该批次已经落过（成功或失败明细都算提交过），不再重复入库，回已收下行数
        Integer existed = this.giprArchiveRowMapper.selectCount(new QueryWrapper<TGiprArchiveRow>()
                .eq("batch_no", batchNo));
        if (existed != null && existed > 0) {
            return countByStatus(batchNo, STATUS_OK);
        }
        int ok = 0;
        for (int i = 0; i < rows.size(); i++) {
            TGiprArchiveRow r = rows.get(i);
            boolean valid = r != null
                    && r.getItemCode() != null && !r.getItemCode().trim().isEmpty()
                    && r.getQty() != null
                    && r.getQty().compareTo(java.math.BigDecimal.ZERO) > 0;
            r.setBatchNo(batchNo);
            // 保留原册行次：从 1 起的原始下标，失败行和成功行口径一致
            r.setRowNo(i + 1);
            r.setStatus(valid ? STATUS_OK : STATUS_FAIL);
            if (r.getDelFlag() == null) {
                r.setDelFlag(0);
            }
            this.giprArchiveRowMapper.insert(r);
            if (valid) {
                ok++;
            }
        }
        return ok;
    }

    @Override
    public List<TGiprArchiveRow> listErrors(String batchNo) {
        if (batchNo == null || batchNo.trim().isEmpty()) {
            return new java.util.ArrayList<TGiprArchiveRow>();
        }
        return this.giprArchiveRowMapper.selectList(new QueryWrapper<TGiprArchiveRow>()
                .eq("batch_no", batchNo)
                .eq("status", STATUS_FAIL)
                .eq("del_flag", 0)
                .orderByAsc("row_no", "id"));
    }

    private int countByStatus(String batchNo, int status) {
        Integer n = this.giprArchiveRowMapper.selectCount(new QueryWrapper<TGiprArchiveRow>()
                .eq("batch_no", batchNo).eq("status", status).eq("del_flag", 0));
        return n == null ? 0 : n;
    }
}
