package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.mapper.auto.TGiprYieldRuleMapper;
import com.fc.v2.model.auto.TGiprYieldRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 生效区间/版本取舍/分档/统计同源单测。
 */
@ExtendWith(MockitoExtension.class)
class TGiprYieldRuleServiceImplTest {

    @Mock
    private TGiprYieldRuleMapper mapper;
    @InjectMocks
    private TGiprYieldRuleServiceImpl service;

    private final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private List<TGiprYieldRule> rows;

    @BeforeEach
    void setUp() {
        rows = new ArrayList<>();
        when(mapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(rows));
    }

    private TGiprYieldRule rule(Long id, String code, int pri, String start, String end,
                                String th1, String th2, String th3) throws Exception {
        TGiprYieldRule r = new TGiprYieldRule();
        r.setId(id);
        r.setRuleCode(code);
        r.setPriority(pri);
        r.setStatus(0);
        r.setDelFlag(0);
        r.setEffStart(start == null ? null : f.parse(start));
        r.setEffEnd(end == null ? null : f.parse(end));
        r.setTh1Max(th1 == null ? null : new BigDecimal(th1));
        r.setTh2Max(th2 == null ? null : new BigDecimal(th2));
        r.setTh3Max(th3 == null ? null : new BigDecimal(th3));
        return r;
    }

    @Test
    void window_is_start_inclusive_end_exclusive() throws Exception {
        // R 在 [10-01, 10-10)；同代号旧版在更早
        rows.add(rule(1L, "R", 1, "2026-10-01 00:00:00", "2026-10-10 00:00:00", "10", "20", "30"));

        assertTrue(service.usable(1L, f.parse("2026-10-01 00:00:00")));   // 启用当日含
        assertTrue(service.usable(1L, f.parse("2026-10-09 23:59:59")));
        assertFalse(service.usable(1L, f.parse("2026-10-10 00:00:00")));  // 交班当日不含
        assertFalse(service.usable(1L, f.parse("2026-09-30 00:00:00")));
        // 统计与列表同源
        assertEquals(1, service.countAvailable(f.parse("2026-10-05 00:00:00")));
        assertEquals(0, service.countAvailable(f.parse("2026-10-10 00:00:00")));
    }

    @Test
    void newest_effective_version_wins() throws Exception {
        rows.add(rule(1L, "R", 1, "2026-01-01 00:00:00", "2026-12-31 00:00:00", "10", "20", "30"));
        rows.add(rule(2L, "R", 1, "2026-06-01 00:00:00", null, "99", "199", "299"));
        // 6 月后取新版（启用日新者优先）
        assertEquals(1, service.evaluate("R", new BigDecimal("50"), f.parse("2026-07-01 00:00:00")));
        // 新版阈值 99 起：50 落 1 档；旧版阈值 10 起：50 落 4 档。返回 1 证明选了新版
        assertEquals(4, service.evaluate("R", new BigDecimal("50"), f.parse("2026-05-01 00:00:00")));
    }

    @Test
    void boundary_equal_to_limit_takes_higher_tier() throws Exception {
        rows.add(rule(1L, "R", 1, "2026-01-01 00:00:00", null, "10", "20", "30"));
        assertEquals(1, service.evaluate("R", new BigDecimal("9.99"), f.parse("2026-05-01 00:00:00")));
        assertEquals(2, service.evaluate("R", new BigDecimal("10"), f.parse("2026-05-01 00:00:00")));
        assertEquals(3, service.evaluate("R", new BigDecimal("20"), f.parse("2026-05-01 00:00:00")));
        assertEquals(4, service.evaluate("R", new BigDecimal("30"), f.parse("2026-05-01 00:00:00")));
    }

    @Test
    void out_of_range_and_missing_returns_zero() throws Exception {
        rows.add(rule(1L, "R", 1, "2026-01-01 00:00:00", null, "10", "20", "30"));
        assertEquals(0, service.evaluate("R", new BigDecimal("-1"), f.parse("2026-05-01 00:00:00")));
        assertEquals(0, service.evaluate("R", new BigDecimal("101"), f.parse("2026-05-01 00:00:00")));
        assertEquals(0, service.evaluate("NONE", new BigDecimal("5"), f.parse("2026-05-01 00:00:00")));
        assertEquals(0, service.evaluate("R", new BigDecimal("5"), null));
    }

    @Test
    void evaluate_top_picks_highest_priority_and_sorts() throws Exception {
        TGiprYieldRule low = rule(1L, "A", 1, "2026-01-01 00:00:00", null, "10", "20", "30");
        TGiprYieldRule high = rule(2L, "B", 9, "2026-01-01 00:00:00", null, "99", "199", "299");
        rows.addAll(Arrays.asList(low, high));
        List<TGiprYieldRule> avail = service.listAvailable(f.parse("2026-05-01 00:00:00"));
        assertEquals(2L, avail.get(0).getId());
        // top 用高优先级规则：50 对其落 1 档
        assertEquals(1, service.evaluateTop(new BigDecimal("50"), f.parse("2026-05-01 00:00:00")));
    }

    @Test
    void deleted_or_offline_rules_excluded() throws Exception {
        TGiprYieldRule deleted = rule(1L, "D", 1, null, null, "10", "20", "30");
        deleted.setDelFlag(1);
        TGiprYieldRule offline = rule(2L, "O", 1, null, null, "10", "20", "30");
        offline.setStatus(1);
        rows.addAll(Arrays.asList(deleted, offline));
        assertEquals(0, service.listAvailable(f.parse("2026-05-01 00:00:00")).size());
        assertFalse(service.usable(1L, f.parse("2026-05-01 00:00:00")));
    }
}
