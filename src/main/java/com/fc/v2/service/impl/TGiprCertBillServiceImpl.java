package com.fc.v2.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TGiprCertBillMapper;
import com.fc.v2.mapper.auto.TGiprCertSignMapper;
import com.fc.v2.model.auto.TGiprCertBill;
import com.fc.v2.model.auto.TGiprCertSign;
import com.fc.v2.service.ITGiprCertBillService;

/**
 * 专用标志使用授权签批单 Service业务层处理（approval-chain 形状：多阶段签批）
 *
 * <p>口径定死：</p>
 * <ul>
 *   <li>节点三关：0县级初核 → 1市级核认 → 2省局核准；本关算齐才进下一关，省局算齐即点齐（status=1）。</li>
 *   <li>算齐办法 signMode：0一名即齐（一票过）；1两名须同判（needCount 名<b>不同</b>签批人都赞成，默认2名）。
 *       到票人数以签票留痕里本关不同签批人数为准，同一个人点两回不凑数。</li>
 *   <li>同一签批人同一关同一动作只认一票：再点幂等返回当前单，不计票、不落第二条痕。</li>
 *   <li>否决一票即压回本关（status=2）；已点齐/已压回的终态单三锁（不能签、不能否决、不能退回）。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TGiprCertBillServiceImpl implements ITGiprCertBillService {

    private static final int MAX_NODE = 2;
    private static final int MODE_OR = 0;
    private static final int MODE_AND = 1;
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;

    private static final String ACT_APPROVE = "approve";
    private static final String ACT_REJECT = "reject";

    @javax.annotation.Resource
    private TGiprCertBillMapper giprCertBillMapper;

    @javax.annotation.Resource
    private TGiprCertSignMapper giprCertSignMapper;

    @Override
    public TGiprCertBill selectTGiprCertBillById(Long id) {
        if (id == null) {
            return null;
        }
        return this.giprCertBillMapper.selectOne(new QueryWrapper<TGiprCertBill>()
                .eq("id", id).eq("del_flag", 0));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TGiprCertBill approve(Long id, String approver, String comment) {
        TGiprCertBill r = loadLive(id, approver);
        if (r == null) {
            return null;
        }
        if (nzStatus(r) != STATUS_RUNNING) {
            // 已点齐/已压回锁死
            return null;
        }
        int node = nzNode(r);
        // 同人同关同动作只认一票：第二回幂等，原样返回
        if (hasVoted(id, node, approver, ACT_APPROVE)) {
            return r;
        }
        insertSign(id, node, approver, ACT_APPROVE, comment);

        int votes = countDistinctApprovers(id, node, ACT_APPROVE);
        int need = requiredVoters(r);
        if (votes < need) {
            // 本关尚未算齐：只更新已到人数，不进关
            updateBill(id, STATUS_RUNNING, node, votes);
            return selectTGiprCertBillById(id);
        }
        // 本关算齐
        if (node >= MAX_NODE) {
            updateBill(id, STATUS_PASS, node, votes);
        } else {
            // 进入下一节点，已到人数归零重新计
            updateBill(id, STATUS_RUNNING, node + 1, 0);
        }
        return selectTGiprCertBillById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TGiprCertBill reject(Long id, String approver, String comment) {
        TGiprCertBill r = loadLive(id, approver);
        if (r == null) {
            return null;
        }
        if (nzStatus(r) != STATUS_RUNNING) {
            return null;
        }
        int node = nzNode(r);
        if (hasVoted(id, node, approver, ACT_REJECT)) {
            // 同人重复否决幂等
            return r;
        }
        insertSign(id, node, approver, ACT_REJECT, comment);
        // 一票即压回本关，节点不动
        int rows = this.giprCertBillMapper.update(null, new UpdateWrapper<TGiprCertBill>()
                .eq("id", id).eq("del_flag", 0).eq("status", STATUS_RUNNING)
                .set("status", STATUS_VETO)
                .set("update_time", new Date()));
        return rows > 0 ? selectTGiprCertBillById(id) : null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TGiprCertBill rollback(Long id, String comment) {
        if (id == null) {
            return null;
        }
        TGiprCertBill r = selectTGiprCertBillById(id);
        if (r == null || nzStatus(r) != STATUS_RUNNING) {
            // 已点齐/已压回的终态单锁死，不能退回重走
            return null;
        }
        int node = nzNode(r);
        if (node <= 0) {
            return null;
        }
        int rows = this.giprCertBillMapper.update(null, new UpdateWrapper<TGiprCertBill>()
                .eq("id", id).eq("del_flag", 0).eq("status", STATUS_RUNNING).eq("node_no", node)
                .set("node_no", node - 1)
                .set("sign_count", 0)
                .set("status", STATUS_RUNNING)
                .set("update_time", new Date()));
        return rows > 0 ? selectTGiprCertBillById(id) : null;
    }

    @Override
    public List<TGiprCertSign> selectSigns(Long billId) {
        if (billId == null) {
            return new java.util.ArrayList<TGiprCertSign>();
        }
        return this.giprCertSignMapper.selectList(new QueryWrapper<TGiprCertSign>()
                .eq("bill_id", billId)
                .orderByAsc("node_no", "create_time", "id"));
    }

    private TGiprCertBill loadLive(Long id, String approver) {
        if (approver == null || approver.trim().isEmpty()) {
            return null;
        }
        return selectTGiprCertBillById(id);
    }

    /** 本关应到签批人数：OR 模式恒为 1；AND 模式取 needCount，缺省按 2 名 */
    private int requiredVoters(TGiprCertBill r) {
        int mode = r.getSignMode() == null ? MODE_OR : r.getSignMode();
        if (mode == MODE_AND) {
            return r.getNeedCount() != null && r.getNeedCount() > 0 ? r.getNeedCount() : 2;
        }
        return 1;
    }

    private boolean hasVoted(Long billId, int node, String approver, String action) {
        Integer n = this.giprCertSignMapper.selectCount(new QueryWrapper<TGiprCertSign>()
                .eq("bill_id", billId)
                .eq("node_no", node)
                .eq("approver", approver)
                .eq("action", action));
        return n != null && n > 0;
    }

    private int countDistinctApprovers(Long billId, int node, String action) {
        // 以留痕里本关不同签批人数为准：同一个人点两回只算一票。
        // 不用 selectCount+GROUP BY（3.4.x 的 selectCount 只取单值会丢分组），直接取 distinct 行再数。
        return this.giprCertSignMapper.selectList(new QueryWrapper<TGiprCertSign>()
                .select("DISTINCT approver")
                .eq("bill_id", billId)
                .eq("node_no", node)
                .eq("action", action)).size();
    }

    private void insertSign(Long billId, int node, String approver, String action, String comment) {
        TGiprCertSign sign = new TGiprCertSign();
        sign.setBillId(billId);
        sign.setNodeNo(node);
        sign.setApprover(approver);
        sign.setAction(action);
        sign.setComment(comment);
        sign.setCreateTime(new Date());
        this.giprCertSignMapper.insert(sign);
    }

    private void updateBill(Long id, int status, int node, int signCount) {
        this.giprCertBillMapper.update(null, new UpdateWrapper<TGiprCertBill>()
                .eq("id", id).eq("del_flag", 0)
                .set("status", status)
                .set("node_no", node)
                .set("sign_count", signCount)
                .set("update_time", new Date()));
    }

    private static int nzNode(TGiprCertBill r) {
        return r.getNodeNo() == null ? 0 : r.getNodeNo();
    }

    private static int nzStatus(TGiprCertBill r) {
        return r.getStatus() == null ? STATUS_RUNNING : r.getStatus();
    }
}
