package com.fc.v2.service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TGiprCaseFlow;
import com.fc.v2.model.auto.TGiprCaseFlowLog;

/**
 * 专用标志使用核准办件流转单 Service接口（state-machine 形状：单据流转，不提供增删改查全套）
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITGiprCaseFlowService {

    /** 按主键查询单据（软删的查不到） */
    TGiprCaseFlow selectTGiprCaseFlowById(Long id);

    /** 列表查询（流转台账；只取未删，统计口径与此同源 {@link #countActiveByStage(Integer)}） */
    List<TGiprCaseFlow> selectTGiprCaseFlowList(QueryWrapper<TGiprCaseFlow> queryWrapper);

    /**
     * 手动新建一条流转单：落在申请受理关（stage=0）、在办（status=1），落一条 create 痕。
     * 件次为空被拒返回 null；已删件次允许重建。
     */
    TGiprCaseFlow create(String bizNo, String content);

    /**
     * 推进一档：仅在办单可推；stage 0→1→2→3，推到 3 即封尾（status=2）并联动备案册核齐。
     * 同一关并发/连点两回只成一回（CAS 条件更新），只有成的那回落一条 advance 痕。
     * 返回更新后的单据；被拒（不存在/已删/已封尾）返回 null。
     */
    TGiprCaseFlow advance(Long id, String remark);

    /**
     * 撤回一档：仅在办单可撤；stage 退一格但不低于 0，落一条 rollback 痕。
     * 已封尾（办结归档）的单子锁死，不得撤回；不存在/已删返回 null。
     */
    TGiprCaseFlow rollback(Long id, String remark);

    /** 修改一关一记：已封尾/已删不得再改，被拒返回 false */
    boolean updateContent(Long id, String remark);

    /** 删除单据：已封尾不得删除；未封尾的走软删（del_flag=1），留痕随单保留，被拒返回 false */
    boolean remove(Long id);

    /** 该单的一关一记留痕，按时间、关次升序；明细与动作次数同源，双击不成动不生痕 */
    List<TGiprCaseFlowLog> selectFlowLogs(Long flowId);

    /**
     * 在办单按当前关次计数（台账角标）。口径与 {@link #selectTGiprCaseFlowList} 同源：
     * 未删（del_flag=0）＋在办（status=1）；stage 为 null 的老数据归一到 0 后计入。
     */
    int countActiveByStage(Integer stage);

    /**
     * 真正发生的挪关次数（不含建档）：以一关一记留痕为准，双击不成动的不计。
     * toStage 非空时只数落到该关的动作；与"按流转环节做的统计"必须同此源，不得拿单子数充动作数。
     */
    int countTransitions(Integer toStage);

    /**
     * 早年录入的老数据归一：stage/status 为 null 的补成 0/1，stage 已到顶但 status 未封尾的补封尾。
     * 新流程的封尾判定只认 (stage=3 AND status=2)，不归一会在封尾关被误判。返回归一条数。
     */
    int normalizeLegacy();
}
