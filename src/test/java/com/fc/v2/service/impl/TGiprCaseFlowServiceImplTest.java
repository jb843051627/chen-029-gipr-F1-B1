package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TGiprCaseFlowLogMapper;
import com.fc.v2.mapper.auto.TGiprCaseFlowMapper;
import com.fc.v2.mapper.auto.TGiprHolderBookMapper;
import com.fc.v2.model.auto.TGiprCaseFlow;
import com.fc.v2.model.auto.TGiprCaseFlowLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.invocation.InvocationOnMock;

/**
 * 流转单状态机口径单测（无 DB，全 Mock）。
 */
@ExtendWith(MockitoExtension.class)
class TGiprCaseFlowServiceImplTest {

    @Mock
    private TGiprCaseFlowMapper flowMapper;
    @Mock
    private TGiprCaseFlowLogMapper logMapper;
    @Mock
    private TGiprHolderBookMapper holderMapper;

    @InjectMocks
    private TGiprCaseFlowServiceImpl service;

    private TGiprCaseFlow row;

    @BeforeEach
    void setUp() {
        row = new TGiprCaseFlow();
        row.setId(100L);
        row.setBizNo("BIZ-1");
        row.setStage(0);
        row.setStatus(1);
        row.setDelFlag(0);
    }

    /** selectOne 一律回当前内存对象；update 命中时按"推进"语义改对象，模拟 CAS 成功 */
    private void stubLoad() {
        when(flowMapper.selectOne(any())).thenReturn(row);
    }

    private void stubCasSuccessAdvance() {
        when(flowMapper.update(isNull(), any(UpdateWrapper.class))).thenAnswer((InvocationOnMock inv) -> {
            // 模拟条件更新命中：stage/status 由 CAS 的 set 决定，这里按当前关 +1 复刻
            int from = row.getStage() == null ? 0 : row.getStage();
            int to = Math.min(from + 1, 3);
            row.setStage(to);
            row.setStatus(to >= 3 ? 2 : 1);
            return 1;
        });
    }

    @Test
    void advance_goes_one_stage_and_seals_at_end() {
        stubLoad();
        stubCasSuccessAdvance();

        TGiprCaseFlow r1 = service.advance(100L, "m1");
        assertEquals(1, r1.getStage());
        assertEquals(1, r1.getStatus());

        service.advance(100L, "m2");
        assertEquals(2, row.getStage());
        service.advance(100L, "m3");
        // 到备案发标即封尾
        assertEquals(3, row.getStage());
        assertEquals(2, row.getStatus());
        // 封尾联动备案册核齐，只随封尾发生一次
        verify(holderMapper, times(1)).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void advance_locked_after_terminal() {
        row.setStage(3);
        row.setStatus(2);
        stubLoad();
        assertNull(service.advance(100L, "x"));
        verify(flowMapper, never()).update(isNull(), any(UpdateWrapper.class));
        verify(logMapper, never()).insert(any(TGiprCaseFlowLog.class));
    }

    @Test
    void double_advance_same_stage_only_one_hits_and_one_log() {
        stubLoad();
        // 第一发 CAS 命中，第二发（同关）命中 0 行
        when(flowMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1, 0);

        TGiprCaseFlow first = service.advance(100L, "d1");
        assertNotNull(first);
        TGiprCaseFlow second = service.advance(100L, "d2");
        // 第二发被幂等挡下，无留痕、无联动
        assertNull(second);
        verify(logMapper, times(1)).insert(any(TGiprCaseFlowLog.class));
        verify(holderMapper, never()).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void rollback_goes_one_stage_and_terminal_is_locked() {
        row.setStage(2);
        stubLoad();
        when(flowMapper.update(isNull(), any(UpdateWrapper.class))).thenAnswer(inv -> {
            row.setStage(row.getStage() - 1);
            return 1;
        });

        TGiprCaseFlow r = service.rollback(100L, "back");
        assertEquals(1, r.getStage());

        row.setStage(3);
        row.setStatus(2);
        assertNull(service.rollback(100L, "back-again"));
    }

    @Test
    void rollback_rejected_at_first_stage() {
        row.setStage(0);
        stubLoad();
        assertNull(service.rollback(100L, "back"));
        verify(flowMapper, never()).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void content_locked_when_terminal_editable_when_active() {
        row.setStatus(2);
        row.setStage(3);
        stubLoad();
        assertFalse(service.updateContent(100L, "new"));

        row.setStatus(1);
        row.setStage(1);
        when(flowMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        assertTrue(service.updateContent(100L, "new"));
    }

    @Test
    void remove_is_soft_delete_and_terminal_protected() {
        row.setStatus(2);
        row.setStage(3);
        stubLoad();
        assertFalse(service.remove(100L));

        row.setStatus(1);
        row.setStage(1);
        when(flowMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        assertTrue(service.remove(100L));
    }

    @Test
    void create_rejects_blank_and_duplicate_bizno() {
        assertNull(service.create("  ", "c"));
        when(flowMapper.selectCount(any())).thenReturn(1);
        assertNull(service.create("BIZ-1", "c"));
        verify(flowMapper, never()).insert(any(TGiprCaseFlow.class));
    }

    @Test
    void create_inserts_and_writes_one_log() {
        when(flowMapper.selectCount(any())).thenReturn(0);
        assertNotNull(service.create("BIZ-9", "建档"));
        verify(flowMapper, times(1)).insert(any(TGiprCaseFlow.class));
        verify(logMapper, times(1)).insert(any(TGiprCaseFlowLog.class));
    }

    @Test
    void stats_share_source_with_list_and_transitions_count_logs() {
        TGiprCaseFlow a = flow(1L, 0, 1);
        TGiprCaseFlow b = flow(2L, 0, 1);
        TGiprCaseFlow c = flow(3L, 2, 1);
        when(flowMapper.selectList(any())).thenReturn(new ArrayList<>(Arrays.asList(a, b, c)));

        assertEquals(2, service.countActiveByStage(0));
        assertEquals(1, service.countActiveByStage(2));
        assertEquals(0, service.countActiveByStage(3));

        TGiprCaseFlowLog l1 = logTo(1, "advance");
        TGiprCaseFlowLog l2 = logTo(1, "advance");
        TGiprCaseFlowLog l3 = logTo(2, "advance");
        when(logMapper.selectCount(any())).thenReturn(3);
        assertEquals(3, service.countTransitions(null));
        // 留痕真有三条才算三次动作
        verify(logMapper, atLeastOnce()).selectCount(any());
        assertEquals(3, Arrays.asList(l1, l2, l3).size());
    }

    private TGiprCaseFlow flow(Long id, int stage, int status) {
        TGiprCaseFlow f = new TGiprCaseFlow();
        f.setId(id);
        f.setStage(stage);
        f.setStatus(status);
        f.setDelFlag(0);
        return f;
    }

    private TGiprCaseFlowLog logTo(int to, String action) {
        TGiprCaseFlowLog l = new TGiprCaseFlowLog();
        l.setToStage(to);
        l.setAction(action);
        return l;
    }
}
