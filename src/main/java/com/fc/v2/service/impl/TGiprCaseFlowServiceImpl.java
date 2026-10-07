package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TGiprCaseFlowMapper;
import com.fc.v2.model.auto.TGiprCaseFlow;
import com.fc.v2.service.ITGiprCaseFlowService;

/**
 * 专用标志使用核准办件流转单 Service业务层处理（state-machine 形状：单据流转）
 *
 * <p>流转口径（新旧库单同尺，早年录的单子也按此判定）：</p>
 * <ul>
 *   <li>关次 0..3（申请受理/材料初审/标志核准/备案发标），推进一次只挪一档；</li>
 *   <li>挪到第 3 关（备案发标）即封尾，status 置 2 已封住；</li>
 *   <li>已封住的单子：不得再推进、不得退回、不得改内容、不得删除；</li>
 *   <li>回退一次只退一档（到 0 关为止），不是打回从头再走。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TGiprCaseFlowServiceImpl implements ITGiprCaseFlowService {

    private static final int MAX_STAGE = 3;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_TERMINAL = 2;

    @javax.annotation.Resource
    private TGiprCaseFlowMapper giprCaseFlowMapper;

    @Override
    public TGiprCaseFlow selectTGiprCaseFlowById(Long id) {
        return this.giprCaseFlowMapper.selectById(id);
    }

    @Override
    public List<TGiprCaseFlow> selectTGiprCaseFlowList(QueryWrapper<TGiprCaseFlow> queryWrapper) {
        return this.giprCaseFlowMapper.selectList(queryWrapper);
    }

    @Override
    public TGiprCaseFlow advance(Long id, String remark) {
        TGiprCaseFlow r = this.giprCaseFlowMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        int next = st + 1;
        r.setStage(next);
        r.setStatus(next >= MAX_STAGE ? STATUS_TERMINAL : STATUS_ACTIVE);
        r.setLastAction(remark);
        this.giprCaseFlowMapper.updateById(r);
        return r;
    }

    @Override
    public TGiprCaseFlow rollback(Long id, String remark) {
        TGiprCaseFlow r = this.giprCaseFlowMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        if (st <= 0) {
            // 已在起关，无可再退
            return null;
        }
        r.setStage(st - 1);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        this.giprCaseFlowMapper.updateById(r);
        return r;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TGiprCaseFlow r = this.giprCaseFlowMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return false;
        }
        r.setContent(remark);
        return this.giprCaseFlowMapper.updateById(r) > 0;
    }

    @Override
    public boolean remove(Long id) {
        TGiprCaseFlow r = this.giprCaseFlowMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return false;
        }
        return this.giprCaseFlowMapper.deleteById(id) > 0;
    }

    /** 已封住（办结）判定：status=2，或关次已到末关（兼容早年库里封尾口径不同的旧单） */
    private static boolean isTerminal(TGiprCaseFlow r) {
        if (r.getStatus() != null && r.getStatus() == STATUS_TERMINAL) {
            return true;
        }
        return r.getStage() != null && r.getStage() >= MAX_STAGE;
    }

}
