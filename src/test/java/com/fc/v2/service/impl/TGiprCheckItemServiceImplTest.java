package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TGiprCheckItemMapper;
import com.fc.v2.model.auto.TGiprCheckItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 执行窗口 / 提前期 / 落库口径单测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TGiprCheckItemServiceImplTest {

    @Mock
    private TGiprCheckItemMapper mapper;
    @InjectMocks
    private TGiprCheckItemServiceImpl service;

    private final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final List<TGiprCheckItem> store = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store.clear();
        when(mapper.selectList(any())).thenAnswer(inv -> {
            List<TGiprCheckItem> live = new ArrayList<>();
            for (TGiprCheckItem r : store) {
                if ((r.getDelFlag() == null || r.getDelFlag() == 0)
                        && (r.getStatus() != null && r.getStatus() == 0)) {
                    live.add(r);
                }
            }
            return live;
        });
        when(mapper.update(isNull(), any(UpdateWrapper.class))).thenAnswer(inv -> {
            UpdateWrapper<TGiprCheckItem> w = inv.getArgument(1);
            // 简化模拟：把内存中 status=0 的行置为 set 里的目标状态
            for (TGiprCheckItem r : store) {
                if (r.getStatus() == 0) {
                    r.setStatus(1);
                }
            }
            return 1;
        });
    }

    private TGiprCheckItem item(Long id, String dueAt, String ahead, int status) throws Exception {
        TGiprCheckItem r = new TGiprCheckItem();
        r.setId(id);
        r.setDueAt(f.parse(dueAt));
        r.setAmount(ahead == null ? null : new BigDecimal(ahead));
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    @Test
    void nothing_due_outside_run_window() throws Exception {
        store.add(item(1L, "2026-10-07 00:00:00", "0", 0));
        // 上午 10 点不在 [02,05) 窗口
        assertEquals(0, service.listDue(f.parse("2026-10-07 10:00:00")).size());
        assertEquals(0, service.runOnce(f.parse("2026-10-07 10:00:00")));
        verify(mapper, never()).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void due_respects_lead_days_and_boundary() throws Exception {
        // 应发 10-10，允许提前 3 天 → 最早 10-07 00:00
        store.add(item(1L, "2026-10-10 00:00:00", "3", 0));
        assertEquals(0, service.listDue(f.parse("2026-10-06 03:00:00")).size());
        assertEquals(1, service.listDue(f.parse("2026-10-07 03:00:00")).size()); // 恰好到点算到期
    }

    @Test
    void already_sent_or_deleted_not_picked() throws Exception {
        store.add(item(1L, "2026-10-01 00:00:00", "0", 1));
        TGiprCheckItem d = item(2L, "2026-10-01 00:00:00", "0", 0);
        d.setDelFlag(1);
        store.add(d);
        assertEquals(0, service.listDue(f.parse("2026-10-07 03:00:00")).size());
    }

    @Test
    void run_once_marks_due_rows_done_and_returns_count() throws Exception {
        store.add(item(1L, "2026-10-01 00:00:00", "0", 0));
        store.add(item(2L, "2026-10-02 00:00:00", "0", 0));
        int n = service.runOnce(f.parse("2026-10-07 03:00:00"));
        assertEquals(2, n);
        verify(mapper, times(2)).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void null_time_safe() {
        assertEquals(0, service.listDue(null).size());
        assertEquals(0, service.runOnce(null));
    }
}
