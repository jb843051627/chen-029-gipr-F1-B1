package com.fc.v2.service.impl;

import org.springframework.stereotype.Service;

import com.fc.v2.mapper.auto.TGiprCertBillMapper;
import com.fc.v2.model.auto.TGiprCertBill;
import com.fc.v2.service.ITGiprCertBillService;

/**
 * 专用标志使用授权签批单 Service业务层处理（approval-chain 形状：多阶段签批）
 *
 * <p>签批口径：</p>
 * <ul>
 *   <li>关次 0..2（县级初核/市级核认/省局核准），一关算齐才进下一关；</li>
 *   <li>本关应签人数取 need_count，未填按算齐办法兜底：一名即齐=1、两名须同判=2；</li>
 *   <li>省局核准（第 2 关）签齐后才算已点齐（status=1）；否决置已压回（status=2）；</li>
 *   <li>只有在签（status=0）的单子收票/否决/退回；已点齐、已压回的一律拒——
 *       同一个核准动作点两回，第二回落不下，留痕不会多一条有效流转。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TGiprCertBillServiceImpl implements ITGiprCertBillService {

    private static final int MAX_NODE = 2;
    private static final int MODE_AND = 1;
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;

    @javax.annotation.Resource
    private TGiprCertBillMapper giprCertBillMapper;

    @Override
    public TGiprCertBill selectTGiprCertBillById(Long id) {
        return this.giprCertBillMapper.selectById(id);
    }

    @Override
    public TGiprCertBill approve(Long id, String approver, String comment) {
        TGiprCertBill r = this.giprCertBillMapper.selectById(id);
        if (r == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        if (r.getStatus() == null || r.getStatus() != STATUS_RUNNING) {
            // 已点齐/已压回不再收票（幂等：重试、双击不重复挪关）
            return null;
        }
        int signed = (r.getSignCount() == null ? 0 : r.getSignCount()) + 1;
        if (signed >= requiredOf(r)) {
            // 本关算齐：签到数清零，进下一关；省局核准签齐即全单点齐（关次封顶在 2）
            int node = (r.getNodeNo() == null ? 0 : r.getNodeNo()) + 1;
            r.setNodeNo(Integer.valueOf(Math.min(node, MAX_NODE)));
            r.setSignCount(Integer.valueOf(0));
            if (node > MAX_NODE) {
                r.setStatus(Integer.valueOf(STATUS_PASS));
            }
        } else {
            r.setSignCount(Integer.valueOf(signed));
        }
        this.giprCertBillMapper.updateById(r);
        return r;
    }

    @Override
    public TGiprCertBill reject(Long id, String approver, String comment) {
        TGiprCertBill r = this.giprCertBillMapper.selectById(id);
        if (r == null || r.getStatus() == null || r.getStatus() != STATUS_RUNNING) {
            return null;
        }
        r.setStatus(Integer.valueOf(STATUS_VETO));
        this.giprCertBillMapper.updateById(r);
        return r;
    }

    @Override
    public TGiprCertBill rollback(Long id, String comment) {
        TGiprCertBill r = this.giprCertBillMapper.selectById(id);
        if (r == null || r.getStatus() == null || r.getStatus() != STATUS_RUNNING) {
            return null;
        }
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        if (node <= 0) {
            // 已在首关，无可再退
            return null;
        }
        r.setNodeNo(Integer.valueOf(node - 1));
        r.setSignCount(Integer.valueOf(0));
        this.giprCertBillMapper.updateById(r);
        return r;
    }

    /** 本关应签人数：need_count 优先；未填按算齐办法兜底（两名须同判=2，一名即齐=1） */
    private static int requiredOf(TGiprCertBill r) {
        if (r.getNeedCount() != null && r.getNeedCount() > 0) {
            return r.getNeedCount();
        }
        return r.getSignMode() != null && r.getSignMode() == MODE_AND ? 2 : 1;
    }
}
