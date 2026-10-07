package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TGiprYieldRuleMapper;
import com.fc.v2.model.auto.TGiprYieldRule;
import com.fc.v2.service.ITGiprYieldRuleService;

/**
 * 核定产量与特色指标换算贴线档 Service业务层处理（rule-eval 形状）
 *
 * <p>口径定死，两个口子（列表/计数、定位/可用性）同一套筛选，任何一处改口径两处一起动：</p>
 * <ul>
 *   <li>在位可用：del_flag=0 且 status=0（在位）且 at 落在生效区间 [eff_start, eff_end)——
 *       启用当日算生效，交班当日不算（eff_end 不含）；eff_end 为空视为长期有效。</li>
 *   <li>同代号多版本：取 at 时刻已启用版本里启用日最新的一版（启用日并列取交班次序大的，
 *       再并列取主键大的），取舍唯一确定。</li>
 *   <li>多规则排序：priority 降序，并列 ruleCode 降序。</li>
 *   <li>分档：等于某档上限取<b>高一档</b>（input &lt; th1 为1档，th1 ≤ input &lt; th2 为2档，依此）；
 *       越过封顶为4档。参数缺失/输入越界（不在 [0,100]）一律 0。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TGiprYieldRuleServiceImpl implements ITGiprYieldRuleService {

    /** 优先级降序；并列按 ruleCode 降序（确定性） */
    private static final Comparator<TGiprYieldRule> PRIORITY_THEN_CODE_DESC = new Comparator<TGiprYieldRule>() {
        @Override
        public int compare(TGiprYieldRule a, TGiprYieldRule b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            int c = cb.compareTo(ca);
            if (c != 0) {
                return c;
            }
            Long ia = a.getId() == null ? 0L : a.getId();
            Long ib = b.getId() == null ? 0L : b.getId();
            return ib.compareTo(ia);
        }
    };

    /** 同代号版本取舍：启用日新的优先，并列交班次序（priority）大的优先，再并列主键大的优先 */
    private static final Comparator<TGiprYieldRule> VERSION_PICK = new Comparator<TGiprYieldRule>() {
        @Override
        public int compare(TGiprYieldRule a, TGiprYieldRule b) {
            int c = compareEffStart(a.getEffStart(), b.getEffStart());
            if (c != 0) {
                return c;
            }
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            Long ia = a.getId() == null ? 0L : a.getId();
            Long ib = b.getId() == null ? 0L : b.getId();
            return ib.compareTo(ia);
        }
    };

    @javax.annotation.Resource
    private TGiprYieldRuleMapper giprYieldRuleMapper;

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）：JDBC 的 serverTimezone 与本机
     * 时区不对称，直接把 java.util.Date 作参数会整体偏移，使边界判定在"切换当日"这类用例上随机错档。
     */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    @Override
    public TGiprYieldRule selectTGiprYieldRuleById(Long id) {
        return this.giprYieldRuleMapper.selectById(id);
    }

    @Override
    public List<TGiprYieldRule> listAvailable(Date at) {
        if (at == null) {
            return new java.util.ArrayList<TGiprYieldRule>();
        }
        // 在位行先在库里滤，生效区间在内存按墙钟串滤（避开时区偏移）；
        // 在位/未删条件内存里再过一道，保证口径集中在本类、不依赖调用方给的 SQL
        List<TGiprYieldRule> rows = this.giprYieldRuleMapper.selectList(
                new QueryWrapper<TGiprYieldRule>().eq("del_flag", 0).eq("status", 0));
        List<TGiprYieldRule> avail = new java.util.ArrayList<TGiprYieldRule>();
        for (TGiprYieldRule r : rows) {
            if (live(r) && effectiveAt(r, at)) {
                avail.add(r);
            }
        }
        Collections.sort(avail, PRIORITY_THEN_CODE_DESC);
        return avail;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        if (!inInputRange(input)) {
            return 0;
        }
        TGiprYieldRule rule = findRule(ruleCode, at);
        if (rule == null) {
            return 0;
        }
        return levelOf(rule, input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null || !inInputRange(input)) {
            return 0;
        }
        List<TGiprYieldRule> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        return levelOf(avail.get(0), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        // 口径与列表/定位完全一致：同一份在位＋生效判定，不另写"非空即可用"
        for (TGiprYieldRule r : listAvailable(at)) {
            if (id.equals(r.getId())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int countAvailable(Date at) {
        // 角标/看板口径直接取列表长度，与列表同源，杜绝两个数字对不上账
        return listAvailable(at).size();
    }

    private TGiprYieldRule findRule(String ruleCode, Date at) {
        List<TGiprYieldRule> hit = this.giprYieldRuleMapper.selectList(new QueryWrapper<TGiprYieldRule>()
                .eq("del_flag", 0).eq("status", 0).eq("rule_code", ruleCode));
        TGiprYieldRule picked = null;
        for (TGiprYieldRule r : hit) {
            if (!ruleCode.equals(r.getRuleCode()) || !live(r) || !effectiveAt(r, at)) {
                continue;
            }
            if (picked == null || VERSION_PICK.compare(r, picked) > 0) {
                picked = r;
            }
        }
        return picked;
    }

    /** 在位未删：status=0（在位）且 del_flag=0 */
    private static boolean live(TGiprYieldRule r) {
        return (r.getDelFlag() == null || r.getDelFlag() == 0)
                && (r.getStatus() == null || r.getStatus() == 0);
    }

    /** 生效区间 [eff_start, eff_end)：启用当日含，交班当日不含；两端皆空视为长期有效 */
    private static boolean effectiveAt(TGiprYieldRule r, Date at) {
        String now = ts(at);
        if (r.getEffStart() != null && now.compareTo(ts(r.getEffStart())) < 0) {
            return false;
        }
        if (r.getEffEnd() != null && now.compareTo(ts(r.getEffEnd())) >= 0) {
            return false;
        }
        return true;
    }

    /** 启用日比较：空启用日视为最早（兜底老数据排在最后） */
    private static int compareEffStart(Date a, Date b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        return ts(a).compareTo(ts(b));
    }

    private static boolean inInputRange(BigDecimal input) {
        return input.compareTo(BigDecimal.ZERO) >= 0
                && input.compareTo(new BigDecimal("100")) <= 0;
    }

    /** 分档：等于上限取高一档。阈值缺档视为该档无上限，继续向下判 */
    private int levelOf(TGiprYieldRule rule, BigDecimal input) {
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) < 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) < 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) < 0) {
            return 3;
        }
        return 4;
    }
}
