package com.fc.v2.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TGiprCaseFlowLogMapper;
import com.fc.v2.mapper.auto.TGiprCaseFlowMapper;
import com.fc.v2.mapper.auto.TGiprHolderBookMapper;
import com.fc.v2.model.auto.TGiprCaseFlow;
import com.fc.v2.model.auto.TGiprCaseFlowLog;
import com.fc.v2.model.auto.TGiprHolderBook;
import com.fc.v2.service.ITGiprCaseFlowService;
import com.fc.v2.shiro.util.ShiroUtils;

/**
 * 专用标志使用核准办件流转单 Service业务层处理（state-machine 形状：单据流转）
 *
 * <p>口径定死，任何一处改动都要同时动台账、留痕、统计三个出口：</p>
 * <ul>
 *   <li>关次四档：0申请受理 → 1材料初审 → 2标志核准 → 3备案发标；到 3 即封尾（status=2）。</li>
 *   <li>挪关一律走 CAS 条件更新（id＋动前关次＋在办＋未删），双击/并发只有一发命中，
 *       且只有命中的那一发落留痕——动作次数以留痕为准。</li>
 *   <li>封尾单四锁：不能推进、不能撤回、不能改记、不能删；删除走软删，留痕随单保留。</li>
 *   <li>台账明细与统计同源于 {@link #liveRows()}（del_flag=0），不另写口径。</li>
 *   <li>封尾（备案发标）与备案册联动：按件次把用标企业授权册置为已核齐，联动只随封尾发生一次。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TGiprCaseFlowServiceImpl implements ITGiprCaseFlowService {

    private static final int FIRST_STAGE = 0;
    private static final int MAX_STAGE = 3;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_TERMINAL = 2;

    private static final String ACTION_CREATE = "create";
    private static final String ACTION_ADVANCE = "advance";
    private static final String ACTION_ROLLBACK = "rollback";

    @javax.annotation.Resource
    private TGiprCaseFlowMapper giprCaseFlowMapper;

    @javax.annotation.Resource
    private TGiprCaseFlowLogMapper giprCaseFlowLogMapper;

    @javax.annotation.Resource
    private TGiprHolderBookMapper giprHolderBookMapper;

    @Override
    public TGiprCaseFlow selectTGiprCaseFlowById(Long id) {
        if (id == null) {
            return null;
        }
        return this.giprCaseFlowMapper.selectOne(new QueryWrapper<TGiprCaseFlow>()
                .eq("id", id).eq("del_flag", 0));
    }

    @Override
    public List<TGiprCaseFlow> selectTGiprCaseFlowList(QueryWrapper<TGiprCaseFlow> queryWrapper) {
        // 强制只看活单：软删单不得进台账，也不得进任何由台账派生的统计
        queryWrapper.eq("del_flag", 0);
        return this.giprCaseFlowMapper.selectList(queryWrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TGiprCaseFlow create(String bizNo, String content) {
        if (bizNo == null || bizNo.trim().isEmpty()) {
            return null;
        }
        // 同一件次只许有一条活单（已软删的件次可重建）
        Integer dup = this.giprCaseFlowMapper.selectCount(new QueryWrapper<TGiprCaseFlow>()
                .eq("biz_no", bizNo).eq("del_flag", 0));
        if (dup != null && dup > 0) {
            return null;
        }
        TGiprCaseFlow r = new TGiprCaseFlow();
        r.setBizNo(bizNo);
        r.setStage(FIRST_STAGE);
        r.setStatus(STATUS_ACTIVE);
        r.setContent(content);
        r.setLastAction(ACTION_CREATE);
        r.setDelFlag(0);
        this.giprCaseFlowMapper.insert(r);
        writeLog(r, null, FIRST_STAGE, ACTION_CREATE);
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TGiprCaseFlow advance(Long id, String remark) {
        TGiprCaseFlow r = selectTGiprCaseFlowById(id);
        if (r == null) {
            return null;
        }
        repairLegacyRow(r);
        // 已封尾锁死；走到头了不能再往前推一格
        if (STATUS_TERMINAL == nzStatus(r) || nzStage(r) >= MAX_STAGE) {
            return null;
        }
        int from = nzStage(r);
        int to = from + 1;
        int targetStatus = to >= MAX_STAGE ? STATUS_TERMINAL : STATUS_ACTIVE;
        // CAS：id＋动前关次＋在办＋未删同时成立才更新；同一关连点两回，第二回命中 0 行
        int rows = this.giprCaseFlowMapper.update(null, new UpdateWrapper<TGiprCaseFlow>()
                .eq("id", id)
                .eq("del_flag", 0)
                .eq("status", STATUS_ACTIVE)
                .eq("stage", from)
                .set("stage", to)
                .set("status", targetStatus)
                .set("last_action", ACTION_ADVANCE)
                .set("update_by", currentOperator())
                .set("update_time", new Date()));
        if (rows == 0) {
            return null;
        }
        writeLog(r, from, to, ACTION_ADVANCE);
        if (to >= MAX_STAGE) {
            // 封尾联动备案册：核准办件走到备案发标，用标企业授权册核齐
            sealHolderBook(r.getBizNo());
        }
        return selectTGiprCaseFlowById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TGiprCaseFlow rollback(Long id, String remark) {
        TGiprCaseFlow r = selectTGiprCaseFlowById(id);
        if (r == null) {
            return null;
        }
        repairLegacyRow(r);
        // 已封尾（办结/归档）锁死，退回来等于从头再走的口子不开
        if (STATUS_TERMINAL == nzStatus(r)) {
            return null;
        }
        int from = nzStage(r);
        if (from <= FIRST_STAGE) {
            // 已在申请受理关，无前一关可撤
            return null;
        }
        int to = from - 1;
        int rows = this.giprCaseFlowMapper.update(null, new UpdateWrapper<TGiprCaseFlow>()
                .eq("id", id)
                .eq("del_flag", 0)
                .eq("status", STATUS_ACTIVE)
                .eq("stage", from)
                .set("stage", to)
                .set("status", STATUS_ACTIVE)
                .set("last_action", ACTION_ROLLBACK)
                .set("update_by", currentOperator())
                .set("update_time", new Date()));
        if (rows == 0) {
            return null;
        }
        writeLog(r, from, to, ACTION_ROLLBACK);
        return selectTGiprCaseFlowById(id);
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TGiprCaseFlow r = selectTGiprCaseFlowById(id);
        if (r == null) {
            return false;
        }
        repairLegacyRow(r);
        // 已封尾的内容锁死，不能再编辑保存
        if (STATUS_TERMINAL == nzStatus(r)) {
            return false;
        }
        return this.giprCaseFlowMapper.update(null, new UpdateWrapper<TGiprCaseFlow>()
                .eq("id", id).eq("del_flag", 0).ne("status", STATUS_TERMINAL)
                .set("content", remark)
                .set("update_by", currentOperator())
                .set("update_time", new Date())) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Long id) {
        TGiprCaseFlow r = selectTGiprCaseFlowById(id);
        if (r == null) {
            return false;
        }
        repairLegacyRow(r);
        // 已封尾不得删除；其余软删，留痕保留，环节链不断
        if (STATUS_TERMINAL == nzStatus(r)) {
            return false;
        }
        int rows = this.giprCaseFlowMapper.update(null, new UpdateWrapper<TGiprCaseFlow>()
                .eq("id", id).eq("del_flag", 0).ne("status", STATUS_TERMINAL)
                .set("del_flag", 1)
                .set("update_by", currentOperator())
                .set("update_time", new Date()));
        return rows > 0;
    }

    @Override
    public List<TGiprCaseFlowLog> selectFlowLogs(Long flowId) {
        if (flowId == null) {
            return new ArrayList<TGiprCaseFlowLog>();
        }
        return this.giprCaseFlowLogMapper.selectList(new QueryWrapper<TGiprCaseFlowLog>()
                .eq("flow_id", flowId)
                .orderByAsc("create_time", "id"));
    }

    @Override
    public int countActiveByStage(Integer stage) {
        int target = stage == null ? FIRST_STAGE : stage;
        // 与台账明细同源：同一批活单（liveRows），在内存里按同一口径滤，不另写 SQL 口径
        int n = 0;
        for (TGiprCaseFlow r : liveRows()) {
            if (nzStatus(r) == STATUS_ACTIVE && nzStage(r) == target) {
                n++;
            }
        }
        return n;
    }

    @Override
    public int countTransitions(Integer toStage) {
        // 真正发生的挪关次数：只认真正落库的留痕（双击不成动的不生痕）
        QueryWrapper<TGiprCaseFlowLog> qw = new QueryWrapper<TGiprCaseFlowLog>()
                .ne("action", ACTION_CREATE);
        if (toStage != null) {
            qw.eq("to_stage", toStage);
        }
        return this.giprCaseFlowLogMapper.selectCount(qw);
    }

    @Override
    public int normalizeLegacy() {
        int fixed = 0;
        // 早年录入的老数据：stage/status 为空的补默认；stage 已到顶却没封尾的补封尾。
        // 新流程封尾只认 (stage=3 AND status=2)，不归一会在封尾关判错。
        fixed += this.giprCaseFlowMapper.update(null, new UpdateWrapper<TGiprCaseFlow>()
                .eq("del_flag", 0).isNull("stage")
                .set("stage", FIRST_STAGE).set("update_time", new Date()));
        fixed += this.giprCaseFlowMapper.update(null, new UpdateWrapper<TGiprCaseFlow>()
                .eq("del_flag", 0).isNull("status")
                .set("status", STATUS_ACTIVE).set("update_time", new Date()));
        fixed += this.giprCaseFlowMapper.update(null, new UpdateWrapper<TGiprCaseFlow>()
                .eq("del_flag", 0).eq("stage", MAX_STAGE).ne("status", STATUS_TERMINAL)
                .set("status", STATUS_TERMINAL).set("update_time", new Date()));
        return fixed;
    }

    /** 台账与统计的唯一数据源：未软删的全部单子 */
    private List<TGiprCaseFlow> liveRows() {
        return this.giprCaseFlowMapper.selectList(new QueryWrapper<TGiprCaseFlow>()
                .eq("del_flag", 0));
    }

    /** 动作前对单条老数据就地归一，保证早年的单子走新流程判定不错档 */
    private void repairLegacyRow(TGiprCaseFlow r) {
        boolean dirty = false;
        UpdateWrapper<TGiprCaseFlow> uw = new UpdateWrapper<TGiprCaseFlow>().eq("id", r.getId());
        if (r.getStage() == null) {
            uw.set("stage", FIRST_STAGE);
            r.setStage(FIRST_STAGE);
            dirty = true;
        }
        if (r.getStatus() == null) {
            uw.set("status", STATUS_ACTIVE);
            r.setStatus(STATUS_ACTIVE);
            dirty = true;
        }
        if (r.getStage() != null && r.getStage() >= MAX_STAGE
                && r.getStatus() != null && r.getStatus() != STATUS_TERMINAL) {
            uw.set("status", STATUS_TERMINAL);
            r.setStatus(STATUS_TERMINAL);
            dirty = true;
        }
        if (dirty) {
            uw.set("update_time", new Date());
            this.giprCaseFlowMapper.update(null, uw);
        }
    }

    /** 封尾联动：同件次的用标企业授权册置为已核齐（册面情形 1），只动在册未核齐行，天然幂等 */
    private void sealHolderBook(String bizNo) {
        if (bizNo == null) {
            return;
        }
        this.giprHolderBookMapper.update(null, new UpdateWrapper<TGiprHolderBook>()
                .eq("holder_no", bizNo)
                .eq("del_flag", 0)
                .ne("status", 1)
                .set("status", 1)
                .set("update_time", new Date()));
    }

    private void writeLog(TGiprCaseFlow r, Integer from, int to, String action) {
        TGiprCaseFlowLog log = new TGiprCaseFlowLog();
        log.setFlowId(r.getId());
        log.setBizNo(r.getBizNo());
        log.setFromStage(from);
        log.setToStage(to);
        log.setAction(action);
        log.setOperator(currentOperator());
        log.setCreateTime(new Date());
        this.giprCaseFlowLogMapper.insert(log);
    }

    private static int nzStage(TGiprCaseFlow r) {
        return r.getStage() == null ? FIRST_STAGE : r.getStage();
    }

    private static int nzStatus(TGiprCaseFlow r) {
        return r.getStatus() == null ? STATUS_ACTIVE : r.getStatus();
    }

    /** 定时任务/无会话上下文取不到操作人时兜底为 system，不留空操作人 */
    private static String currentOperator() {
        try {
            return ShiroUtils.getLoginName();
        } catch (RuntimeException e) {
            return "system";
        }
    }
}
