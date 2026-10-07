package com.fc.v2.service.impl;

import java.math.BigDecimal;
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
 * <p>可用口径（列表/角标/旁路判定/版本定位四处同源，只此一份）：</p>
 * <ul>
 *   <li>del_flag=0 且在位（status 0 或未填）；</li>
 *   <li>生效窗口：eff_start 为空或 ≤ at，eff_end 为空或 &gt; at（交班当日不含）；</li>
 *   <li>排序：priority 降序（数值大的先说话），并列按 ruleCode 降序。</li>
 * </ul>
 *
 * <p>同一 ruleCode 多版并存时以哪一版为准（定死）：先生效窗口内，
 * 再 priority 大者优先，再 eff_start 晚者优先，再 id 大者优先——结果确定，不随查询计划漂。</p>
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
            return cb.compareTo(ca);
        }
    };

    /** 同码多版取舍：priority 降序 → eff_start 晚者优先 → id 大者优先 */
    private static final Comparator<TGiprYieldRule> VERSION_PICK = new Comparator<TGiprYieldRule>() {
        @Override
        public int compare(TGiprYieldRule a, TGiprYieldRule b) {
            int byPriority = PRIORITY_THEN_CODE_DESC.compare(a, b);
            if (byPriority != 0) {
                return byPriority;
            }
            String sa = a.getEffStart() == null ? "" : ts(a.getEffStart());
            String sb = b.getEffStart() == null ? "" : ts(b.getEffStart());
            int byStart = sb.compareTo(sa);
            if (byStart != 0) {
                return byStart;
            }
            long ia = a.getId() == null ? 0L : a.getId();
            long ib = b.getId() == null ? 0L : b.getId();
            return Long.compare(ib, ia);
        }
    };

    @javax.annotation.Resource
    private TGiprYieldRuleMapper giprYieldRuleMapper;

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）+ 字符串绑定：
     * JDBC 的 serverTimezone 与本机时区不对称，直接把 java.util.Date 作参数会整体偏移，
     * 使边界判定在"切换当日"这类用例上随机错档（实测踩到）。
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
        List<TGiprYieldRule> out = new java.util.ArrayList<TGiprYieldRule>();
        if (at == null) {
            return out;
        }
        for (TGiprYieldRule r : this.giprYieldRuleMapper.selectList(
                new QueryWrapper<TGiprYieldRule>().eq("del_flag", 0))) {
            if (isEffectiveAt(r, at)) {
                out.add(r);
            }
        }
        out.sort(PRIORITY_THEN_CODE_DESC);
        return out;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0) {
            // 输入越界（负值），与 evaluateTop 同一口径
            return 0;
        }
        TGiprYieldRule rule = findRule(ruleCode, at);
        return levelOf(rule, input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0) {
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
        return isEffectiveAt(this.giprYieldRuleMapper.selectById(id), at);
    }

    @Override
    public int countAvailable(Date at) {
        // 统计与明细同源：直接数同一份列表，不另起口径
        return listAvailable(at).size();
    }

    /** 唯一一份"at 时刻可用"判定：未删 + 在位 + 生效窗口内 */
    private static boolean isEffectiveAt(TGiprYieldRule r, Date at) {
        if (r == null || at == null) {
            return false;
        }
        if (r.getDelFlag() == null || r.getDelFlag() != 0) {
            return false;
        }
        if (r.getStatus() != null && r.getStatus() != 0) {
            return false;
        }
        String now = ts(at);
        if (r.getEffStart() != null && ts(r.getEffStart()).compareTo(now) > 0) {
            return false;
        }
        // 交班那一一日(不含)：eff_end 当天起即不可用
        return r.getEffEnd() == null || ts(r.getEffEnd()).compareTo(now) > 0;
    }

    /** 同码多版定位：窗口内 + VERSION_PICK 次序取首条；无生效版本返回 null */
    private TGiprYieldRule findRule(String ruleCode, Date at) {
        List<TGiprYieldRule> hit = new java.util.ArrayList<TGiprYieldRule>();
        for (TGiprYieldRule r : this.giprYieldRuleMapper.selectList(new QueryWrapper<TGiprYieldRule>()
                .eq("del_flag", 0).eq("rule_code", ruleCode))) {
            if (isEffectiveAt(r, at)) {
                hit.add(r);
            }
        }
        if (hit.isEmpty()) {
            return null;
        }
        hit.sort(VERSION_PICK);
        return hit.get(0);
    }

    /** 分档：阈值未维护的档跳过；等于上限取高一档；三档限值全缺视为不可用（0） */
    private int levelOf(TGiprYieldRule rule, BigDecimal input) {
        if (rule == null) {
            return 0;
        }
        if (rule.getTh1Max() == null && rule.getTh2Max() == null && rule.getTh3Max() == null) {
            return 0;
        }
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
