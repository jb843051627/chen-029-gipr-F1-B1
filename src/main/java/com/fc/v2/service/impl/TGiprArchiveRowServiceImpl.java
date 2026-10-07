package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TGiprArchiveRowMapper;
import com.fc.v2.model.auto.TGiprArchiveRow;
import com.fc.v2.service.ITGiprArchiveRowService;

/**
 * 历史用标卷宗转档核收行 Service业务层处理（batch-process 形状：整批提交）
 *
 * <p>整批口径：</p>
 * <ul>
 *   <li>空批次返回 0；超过单批上限（500 行）返回 -1 且整批不入库；</li>
 *   <li>逐行校验：合法行置已收下入库，非法行置已退回归入失败明细（保留原始行号），
 *       返回成功行数——有坏行不拖死整批；</li>
 *   <li>幂等：同一批次码已落过库的，重复递交不再入库，
 *       直接返回该批已收下的行数（与首次递交同一口径）。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TGiprArchiveRowServiceImpl implements ITGiprArchiveRowService {

    private static final int MAX_ROWS = 500;
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TGiprArchiveRowMapper giprArchiveRowMapper;

    @Override
    public TGiprArchiveRow selectTGiprArchiveRowById(Long id) {
        return this.giprArchiveRowMapper.selectById(id);
    }

    @Override
    public synchronized int submitBatch(String batchNo, List<TGiprArchiveRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        if (rows.size() > MAX_ROWS) {
            return -1;
        }
        String no = (batchNo != null && !batchNo.trim().isEmpty())
                ? batchNo.trim()
                : (rows.get(0) == null ? null : rows.get(0).getBatchNo());
        if (no == null || no.trim().isEmpty()) {
            // 无批次码无法判重，整批拒收
            return 0;
        }
        Integer existed = this.giprArchiveRowMapper.selectCount(
                new QueryWrapper<TGiprArchiveRow>().eq("batch_no", no));
        if (existed != null && existed > 0) {
            // 同一批次重复递交：不重复入库，返回该批已收下的成功行数
            Integer ok = this.giprArchiveRowMapper.selectCount(new QueryWrapper<TGiprArchiveRow>()
                    .eq("batch_no", no).eq("status", STATUS_OK));
            return ok == null ? 0 : ok;
        }
        int ok = 0;
        int seq = 0;
        for (TGiprArchiveRow r : rows) {
            if (r == null) {
                continue;
            }
            seq++;
            if (r.getRowNo() == null) {
                // 保留原始行号：行里没带就按递交顺序补上
                r.setRowNo(Integer.valueOf(seq));
            }
            r.setBatchNo(no);
            if (r.getItemCode() == null || r.getItemCode().trim().isEmpty()
                    || r.getQty() == null
                    || r.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                r.setStatus(STATUS_FAIL);
            } else {
                r.setStatus(STATUS_OK);
                ok++;
            }
            this.giprArchiveRowMapper.insert(r);
        }
        return ok;
    }

    @Override
    public List<TGiprArchiveRow> listErrors(String batchNo) {
        return this.giprArchiveRowMapper.selectList(new QueryWrapper<TGiprArchiveRow>()
                .eq("batch_no", batchNo).eq("status", STATUS_FAIL).orderByAsc("row_no"));
    }
}
