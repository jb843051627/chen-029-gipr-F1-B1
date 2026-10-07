package com.fc.v2.service;

import java.util.List;

import com.fc.v2.model.auto.TGiprCertBill;
import com.fc.v2.model.auto.TGiprCertSign;

/**
 * 专用标志使用授权签批单 Service接口（approval-chain 形状：多阶段签批，无增删改查入口）
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITGiprCertBillService {

    /** 按主键回查单据（软删的查不到） */
    TGiprCertBill selectTGiprCertBillById(Long id);

    /**
     * 签一票赞成：
     * 算齐办法 signMode：0一名即齐（一票过本关）；1两名须同判（needCount 名不同签批人都赞成才过本关，默认2名）。
     * 同一签批人在同一关只认一票，连点两回幂等返回当前单据、不重复计票、不落第二条痕。
     * 本关算齐后进入下一节点 signCount 归零；省局核准（node=2）算齐即点齐（status=1）。
     * 已点齐/已压回的单子锁死。被拒（不存在/已删/无签批人）返回 null。
     */
    TGiprCertBill approve(Long id, String approver, String comment);

    /**
     * 否决：一票即压回本关（status=2），节点不动；同一人重复否决幂等。
     * 已点齐/已压回的单子锁死返回 null。
     */
    TGiprCertBill reject(Long id, String approver, String comment);

    /**
     * 退回上一节点：仅在签（status=0）单据可退，退一格且本关已到人数归零；
     * 已点齐/已压回的终态单锁死；已在首关或单据不存在返回 null。
     */
    TGiprCertBill rollback(Long id, String comment);

    /** 该单据全部签票留痕，按节点、时间升序（动作次数以留痕为准） */
    List<TGiprCertSign> selectSigns(Long billId);
}
