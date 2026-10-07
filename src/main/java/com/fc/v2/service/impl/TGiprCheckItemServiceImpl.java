package com.fc.v2.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TGiprCheckItemMapper;
import com.fc.v2.model.auto.TGiprCheckItem;
import com.fc.v2.service.ITGiprCheckItemService;

/**
 * 年度核查与续展提醒条目 Service业务层处理（scheduling-job 形状：周期执行）
 *
 * <p>执行口径：</p>
 * <ul>
 *   <li>可处理条目 = 尚未处理（status 0 或未填）+ 到期（含提前量折算）；</li>
 *   <li>提前量 amount 为"至多许提前几日"，有效窗口 [2,5] 天；为空或越窗按 0 天
 *       提前折算到期，执行时置发不成；</li>
 *   <li>runOnce 逐条落库：发成的置已发出、发不成的置发不成，返回成功条数——
 *       按状态统计与真正发生的操作次数同一份账。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TGiprCheckItemServiceImpl implements ITGiprCheckItemService {

    private static final int WIN_FROM = 2;
    private static final int WIN_TO = 5;
    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAIL = 2;
    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

    @javax.annotation.Resource
    private TGiprCheckItemMapper giprCheckItemMapper;

    @Override
    public TGiprCheckItem selectTGiprCheckItemById(Long id) {
        return this.giprCheckItemMapper.selectById(id);
    }

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）：JDBC 的 serverTimezone 与本机
     * 时区不对称，直接把 java.util.Date 作参数会整体偏移，使"恰好到期"这类边界用例错判。
     */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    @Override
    public List<TGiprCheckItem> listDue(Date at) {
        List<TGiprCheckItem> all = this.giprCheckItemMapper.selectList(new QueryWrapper<TGiprCheckItem>());
        List<TGiprCheckItem> due = new java.util.ArrayList<TGiprCheckItem>();
        if (at == null) {
            return due;
        }
        String now = ts(at);
        for (TGiprCheckItem r : all) {
            if (r == null || r.getDueAt() == null || !isWaiting(r)) {
                continue;
            }
            // 到期线 = 应发日 - 有效提前量（提前量缺/越窗按 0 天，即到期当日才算到）
            long early = effectiveEarlyDays(r.getAmount()).longValue() * DAY_MILLIS;
            if (ts(new Date(r.getDueAt().getTime() - early)).compareTo(now) <= 0) {
                due.add(r);
            }
        }
        return due;
    }

    @Override
    public int runOnce(Date at) {
        List<TGiprCheckItem> due = listDue(at);
        if (due.isEmpty()) {
            return 0;
        }
        int ok = 0;
        for (TGiprCheckItem r : due) {
            if (r == null) {
                continue;
            }
            // 单条失败跳过继续：提前量不在窗口内的置发不成，其余置已发出
            if (isWindowValid(r.getAmount())) {
                r.setStatus(STATUS_DONE);
                ok++;
            } else {
                r.setStatus(STATUS_FAIL);
            }
            this.giprCheckItemMapper.updateById(r);
        }
        return ok;
    }

    /** 尚未处理：status 为 0（搁着）或未填 */
    private static boolean isWaiting(TGiprCheckItem r) {
        return r.getStatus() == null || r.getStatus() == STATUS_WAIT;
    }

    /** 提前量是否落在执行窗口 [WIN_FROM, WIN_TO] 天内 */
    private static boolean isWindowValid(java.math.BigDecimal amount) {
        return amount != null
                && amount.compareTo(java.math.BigDecimal.valueOf(WIN_FROM)) >= 0
                && amount.compareTo(java.math.BigDecimal.valueOf(WIN_TO)) <= 0;
    }

    /** 折算后的提前天数：窗口内照用，缺/越窗按 0 天 */
    private static java.math.BigDecimal effectiveEarlyDays(java.math.BigDecimal amount) {
        return isWindowValid(amount) ? amount : java.math.BigDecimal.ZERO;
    }
}
