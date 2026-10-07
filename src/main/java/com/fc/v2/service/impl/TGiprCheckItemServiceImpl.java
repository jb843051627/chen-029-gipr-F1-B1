package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TGiprCheckItemMapper;
import com.fc.v2.model.auto.TGiprCheckItem;
import com.fc.v2.service.ITGiprCheckItemService;

/**
 * 年度核查与续展提醒条目 Service业务层处理（scheduling-job 形状：周期执行）
 *
 * <p>口径定死：</p>
 * <ul>
 *   <li>执行窗口：每日凌晨 02:00（含）至 05:00（不含）这一段才处理，窗口外 listDue 一律为空。</li>
 *   <li>到期：at 已到"最早可发时刻"＝due_at − amount 日（amount 为至多许提前几日，空或非正按 0）。
 *       恰好到点（at 等于最早可发时刻）算到期。</li>
 *   <li>只取搁着未办（status=0）且未删（del_flag=0）的条目；已发出/发不成的不再捞。</li>
 *   <li>runOnce 对每条落库置已发出（status=1），单条失败跳过继续，返回真正发出的条数。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TGiprCheckItemServiceImpl implements ITGiprCheckItemService {

    /** 夜间执行窗口：凌晨 [02:00, 05:00) */
    private static final int WIN_FROM_HOUR = 2;
    private static final int WIN_TO_HOUR = 5;
    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TGiprCheckItemMapper giprCheckItemMapper;

    @Override
    public TGiprCheckItem selectTGiprCheckItemById(Long id) {
        if (id == null) {
            return null;
        }
        return this.giprCheckItemMapper.selectOne(new QueryWrapper<TGiprCheckItem>()
                .eq("id", id).eq("del_flag", 0));
    }

    @Override
    public List<TGiprCheckItem> listDue(Date at) {
        List<TGiprCheckItem> due = new java.util.ArrayList<TGiprCheckItem>();
        if (at == null || !inRunWindow(at)) {
            return due;
        }
        List<TGiprCheckItem> all = this.giprCheckItemMapper.selectList(new QueryWrapper<TGiprCheckItem>()
                .eq("del_flag", 0).eq("status", STATUS_WAIT));
        for (TGiprCheckItem r : all) {
            if (r.getDueAt() != null && !at.before(earliestSendAt(r))) {
                due.add(r);
            }
        }
        return due;
    }

    @Override
    public int runOnce(Date at) {
        List<TGiprCheckItem> due = listDue(at);
        int ok = 0;
        for (TGiprCheckItem r : due) {
            try {
                int rows = this.giprCheckItemMapper.update(null, new UpdateWrapper<TGiprCheckItem>()
                        .eq("id", r.getId())
                        .eq("del_flag", 0)
                        .eq("status", STATUS_WAIT)
                        .set("status", STATUS_DONE)
                        .set("update_time", new Date()));
                if (rows > 0) {
                    ok++;
                }
            } catch (RuntimeException ex) {
                // 单条发不成：落发不成标记后继续，不连坐整批
                this.giprCheckItemMapper.update(null, new UpdateWrapper<TGiprCheckItem>()
                        .eq("id", r.getId()).eq("status", STATUS_WAIT)
                        .set("status", STATUS_FAIL)
                        .set("update_time", new Date()));
            }
        }
        return ok;
    }

    /** 最早可发时刻 = 该发日 - 至多提前日数 */
    private Date earliestSendAt(TGiprCheckItem r) {
        int ahead = 0;
        if (r.getAmount() != null) {
            int v = r.getAmount().intValue();
            if (v > 0) {
                ahead = v;
            }
        }
        Calendar c = Calendar.getInstance();
        c.setTime(r.getDueAt());
        c.add(Calendar.DAY_OF_MONTH, -ahead);
        return c.getTime();
    }

    private static boolean inRunWindow(Date at) {
        Calendar c = Calendar.getInstance();
        c.setTime(at);
        int h = c.get(Calendar.HOUR_OF_DAY);
        return h >= WIN_FROM_HOUR && h < WIN_TO_HOUR;
    }
}
