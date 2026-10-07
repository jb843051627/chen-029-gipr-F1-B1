package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.mapper.auto.TGiprArchiveRowMapper;
import com.fc.v2.model.auto.TGiprArchiveRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 整批提交幂等与失败明细口径单测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TGiprArchiveRowServiceImplTest {

    @Mock
    private TGiprArchiveRowMapper mapper;
    @InjectMocks
    private TGiprArchiveRowServiceImpl service;

    private final List<TGiprArchiveRow> store = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store.clear();
        when(mapper.selectCount(any())).thenAnswer(inv -> {
            Wrapper<?> w = inv.getArgument(0);
            String sql = w.getSqlSegment();
            // 幂等检查只按 batch_no（无 status 条件）；listErrors 计数带 status
            long n;
            if (sql.contains("status")) {
                n = store.stream().filter(r -> r.getStatus() == 1).count();
            } else {
                n = store.size();
            }
            return (int) n;
        });
        org.mockito.Mockito.doAnswer(inv -> {
            store.add(inv.getArgument(0));
            return 1;
        }).when(mapper).insert(any(TGiprArchiveRow.class));
        when(mapper.selectList(any())).thenAnswer(inv -> {
            List<TGiprArchiveRow> errs = new ArrayList<>();
            for (TGiprArchiveRow r : store) {
                if (r.getStatus() == 2) {
                    errs.add(r);
                }
            }
            return errs;
        });
    }

    private TGiprArchiveRow row(String code, String qty) {
        TGiprArchiveRow r = new TGiprArchiveRow();
        r.setItemCode(code);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        return r;
    }

    @Test
    void empty_batch_returns_zero_and_inserts_nothing() {
        assertEquals(0, service.submitBatch("B", new ArrayList<>()));
        verify(mapper, never()).insert(any(TGiprArchiveRow.class));
        assertEquals(0, service.submitBatch(" ", null));
    }

    @Test
    void over_limit_returns_minus_one_and_inserts_nothing() {
        List<TGiprArchiveRow> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            rows.add(row("C" + i, "1"));
        }
        assertEquals(-1, service.submitBatch("BIG", rows));
        verify(mapper, never()).insert(any(TGiprArchiveRow.class));
    }

    @Test
    void valid_and_invalid_rows_both_land_with_original_row_no() {
        List<TGiprArchiveRow> rows = new ArrayList<>();
        rows.add(row("C1", "3"));   // 行1 合法
        rows.add(row(null, "2"));  // 行2 非法
        rows.add(row("C3", "0"));  // 行3 非法（qty 非正）
        rows.add(row("C4", "5"));  // 行4 合法
        int ok = service.submitBatch("B1", rows);
        assertEquals(2, ok);
        assertEquals(4, store.size());
        assertEquals(1, store.get(0).getRowNo());
        assertEquals(2, store.get(1).getRowNo());
        assertEquals(2, store.get(1).getStatus());
        assertEquals(1, store.get(0).getStatus());
    }

    @Test
    void duplicate_batch_is_idempotent() {
        store.add(row("OLD", "9"));
        store.get(0).setStatus(1);
        store.get(0).setBatchNo("DUP");

        List<TGiprArchiveRow> rows = new ArrayList<>();
        rows.add(row("X", "1"));
        int ok = service.submitBatch("DUP", rows);
        assertEquals(1, ok);
        // 第二回一行都不再插
        verify(mapper, never()).insert(any(TGiprArchiveRow.class));
    }
}
