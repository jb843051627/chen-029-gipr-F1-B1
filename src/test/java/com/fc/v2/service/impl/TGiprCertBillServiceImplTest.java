package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.mapper.auto.TGiprCertBillMapper;
import com.fc.v2.mapper.auto.TGiprCertSignMapper;
import com.fc.v2.model.auto.TGiprCertBill;
import com.fc.v2.model.auto.TGiprCertSign;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.invocation.InvocationOnMock;

/**
 * 签批链计票口径单测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TGiprCertBillServiceImplTest {

    @Mock
    private TGiprCertBillMapper billMapper;
    @Mock
    private TGiprCertSignMapper signMapper;

    @InjectMocks
    private TGiprCertBillServiceImpl service;

    private TGiprCertBill bill;
    private final List<TGiprCertSign> signs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bill = new TGiprCertBill();
        bill.setId(7L);
        bill.setNodeNo(0);
        bill.setSignMode(0);
        bill.setNeedCount(2);
        bill.setSignCount(0);
        bill.setStatus(0);
        bill.setDelFlag(0);
        signs.clear();

        when(billMapper.selectOne(any())).thenReturn(bill);
        // selectCount 的 Wrapper 参数里带 bill_id/node_no/approver/action，按内存票计数
        when(signMapper.selectCount(any())).thenAnswer((InvocationOnMock inv) -> {
            com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> w = inv.getArgument(0);
            w.getSqlSegment(); // 先渲染 SQL，参数对才会填充
            java.util.Collection<Object> vals = w.getParamNameValuePairs().values();
            String approver = vals.stream().filter(v -> v instanceof String).map(Object::toString)
                    .filter(v -> !"approve".equals(v) && !"reject".equals(v)).findFirst().orElse(null);
            String action = vals.contains("approve") ? "approve" : vals.contains("reject") ? "reject" : null;
            long n = signs.stream().filter(s -> s.getApprover().equals(approver)
                    && s.getAction().equals(action)
                    && s.getNodeNo().equals(bill.getNodeNo())).count();
            return (int) n;
        });
        when(signMapper.selectList(any())).thenAnswer(inv -> {
            // distinct approver：按当前关赞成票去重
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            for (TGiprCertSign s : signs) {
                if (s.getNodeNo().equals(bill.getNodeNo()) && "approve".equals(s.getAction())) {
                    set.add(s.getApprover());
                }
            }
            List<TGiprCertSign> out = new ArrayList<>();
            for (String ap : set) {
                TGiprCertSign cs = new TGiprCertSign();
                cs.setApprover(ap);
                out.add(cs);
            }
            return out;
        });
        org.mockito.Mockito.doAnswer(inv -> {
            signs.add(inv.getArgument(0));
            return 1;
        }).when(signMapper).insert(any(TGiprCertSign.class));
        // CAS/更新命中：按 UpdateWrapper 的 set 列把值回写到内存 bill，模拟真实落库
        when(billMapper.update(isNull(), any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?> w = inv.getArgument(1);
            java.util.Map<String, Object> params = w.getParamNameValuePairs();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\w+)=#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}").matcher(w.getSqlSet());
            while (m.find()) {
                Object v = params.get(m.group(2));
                switch (m.group(1)) {
                    case "status":
                        bill.setStatus((Integer) v);
                        break;
                    case "node_no":
                        bill.setNodeNo((Integer) v);
                        break;
                    case "sign_count":
                        bill.setSignCount((Integer) v);
                        break;
                    default:
                        break;
                }
            }
            return 1;
        });
    }

    @Test
    void or_mode_one_vote_passes_node() {
        bill.setSignMode(0);
        TGiprCertBill r = service.approve(7L, "zhang", "ok");
        // 一票即进下一节点，计票归零
        assertEquals(1, r.getNodeNo());
        assertEquals(0, r.getSignCount());
        verify(signMapper, times(1)).insert(any(TGiprCertSign.class));
    }

    @Test
    void and_mode_needs_two_distinct_approvers_same_person_twice_not_enough() {
        bill.setSignMode(1);
        bill.setNeedCount(2);

        service.approve(7L, "zhang", "ok-1");
        // 同一人点第二回：幂等，不计票不落痕，仍不进关
        TGiprCertBill again = service.approve(7L, "zhang", "ok-2");
        assertEquals(0, again.getNodeNo());
        verify(signMapper, times(1)).insert(any(TGiprCertSign.class));

        // 第二个不同的人赞成才算齐进关
        TGiprCertBill passed = service.approve(7L, "li", "ok-3");
        assertEquals(1, passed.getNodeNo());
    }

    @Test
    void approve_at_final_node_passes_bill() {
        bill.setSignMode(0);
        bill.setNodeNo(2);
        TGiprCertBill r = service.approve(7L, "sheng", "ok");
        assertEquals(2, r.getNodeNo());
        assertEquals(1, r.getStatus());
    }

    @Test
    void reject_vetoes_and_terminal_locked() {
        TGiprCertBill r = service.reject(7L, "wang", "no");
        assertEquals(2, r.getStatus());

        bill.setStatus(2);
        assertNull(service.approve(7L, "wang", "change mind"));
        assertNull(service.reject(7L, "wang", "again"));
    }

    @Test
    void rollback_locked_for_terminal_and_moves_one_when_running() {
        bill.setStatus(1);
        assertNull(service.rollback(7L, "back"));

        bill.setStatus(0);
        bill.setNodeNo(2);
        TGiprCertBill r = service.rollback(7L, "back");
        assertEquals(1, r.getNodeNo());
        assertEquals(0, r.getSignCount());

        bill.setNodeNo(0);
        assertNull(service.rollback(7L, "back-too-far"));
    }

    @Test
    void blank_approver_rejected() {
        assertNull(service.approve(7L, "  ", "x"));
        verify(billMapper, never()).update(isNull(), any());
    }
}
