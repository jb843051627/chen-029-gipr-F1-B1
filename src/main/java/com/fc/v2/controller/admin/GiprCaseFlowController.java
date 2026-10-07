package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TGiprCaseFlow;
import com.fc.v2.model.auto.TGiprCaseFlowLog;
import com.fc.v2.service.ITGiprCaseFlowService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 专用标志使用核准办件流转单 Controller（state-machine 形状：流转入口）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Api(value = "专用标志使用核准办件流转单")
@Controller
@RequestMapping("/giprCaseFlow")
public class GiprCaseFlowController extends BaseController {

    private final String prefix = "admin/giprCaseFlow";

    @Autowired
    private ITGiprCaseFlowService giprCaseFlowService;

    @ApiOperation(value = "流转台账跳转", notes = "流转台账跳转")
    @GetMapping("/view")
    @RequiresPermissions("giprCaseFlow:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "专用标志使用核准办件流转单流转台账", action = "list")
    @ApiOperation(value = "流转台账", notes = "流转台账")
    @GetMapping("/list")
    @RequiresPermissions("giprCaseFlow:list")
    @ResponseBody
    public ResultTable list(TGiprCaseFlow record) {
        QueryWrapper<TGiprCaseFlow> queryWrapper = new QueryWrapper<TGiprCaseFlow>();
        if (record.getBizNo() != null && !record.getBizNo().trim().isEmpty()) {
            queryWrapper.eq("biz_no", record.getBizNo());
        }
        if (record.getStage() != null) {
            queryWrapper.eq("stage", record.getStage());
        }
        if (record.getStatus() != null) {
            queryWrapper.eq("status", record.getStatus());
        }
        queryWrapper.orderByDesc("create_time");
        startPage();
        com.github.pagehelper.PageInfo<TGiprCaseFlow> page =
                new com.github.pagehelper.PageInfo<TGiprCaseFlow>(giprCaseFlowService.selectTGiprCaseFlowList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "专用标志使用核准办件流转单建档", action = "create")
    @ApiOperation(value = "手动新建流转单", notes = "落在申请受理关，一关一记按常规填上")
    @PostMapping("/create")
    @RequiresPermissions("giprCaseFlow:create")
    @ResponseBody
    public AjaxResult create(String bizNo, String content) {
        return toAjax(giprCaseFlowService.create(bizNo, content) != null ? 1 : 0);
    }

    @Log(title = "专用标志使用核准办件流转单推进", action = "advance")
    @ApiOperation(value = "推进一档", notes = "已封尾/同关连点第二回被拒")
    @PostMapping("/advance")
    @RequiresPermissions("giprCaseFlow:advance")
    @ResponseBody
    public AjaxResult advance(Long id, String remark) {
        return toAjax(giprCaseFlowService.advance(id, remark) != null ? 1 : 0);
    }

    @Log(title = "专用标志使用核准办件流转单撤回", action = "rollback")
    @ApiOperation(value = "撤回一档", notes = "已封尾锁死不可撤")
    @PostMapping("/rollback")
    @RequiresPermissions("giprCaseFlow:rollback")
    @ResponseBody
    public AjaxResult rollback(Long id, String remark) {
        return toAjax(giprCaseFlowService.rollback(id, remark) != null ? 1 : 0);
    }

    @Log(title = "专用标志使用核准办件流转单改记", action = "edit")
    @ApiOperation(value = "修改一关一记", notes = "已封尾不得再改")
    @PostMapping("/editContent")
    @RequiresPermissions("giprCaseFlow:edit")
    @ResponseBody
    public AjaxResult editContent(Long id, String content) {
        return toAjax(giprCaseFlowService.updateContent(id, content) ? 1 : 0);
    }

    @Log(title = "专用标志使用核准办件流转单删除", action = "remove")
    @ApiOperation(value = "删除", notes = "已封尾不得删；未封尾软删，留痕保留")
    @DeleteMapping("/remove")
    @RequiresPermissions("giprCaseFlow:remove")
    @ResponseBody
    public AjaxResult remove(Long id) {
        return toAjax(giprCaseFlowService.remove(id) ? 1 : 0);
    }

    @ApiOperation(value = "一关一记留痕", notes = "只有真正挪关成功的动作才在里面，双击第二回不落痕")
    @GetMapping("/logs")
    @RequiresPermissions("giprCaseFlow:list")
    @ResponseBody
    public AjaxResult logs(Long id) {
        List<TGiprCaseFlowLog> logs = giprCaseFlowService.selectFlowLogs(id);
        return AjaxResult.successData(200, logs);
    }

    @ApiOperation(value = "环节统计", notes = "在办各关单量与各关真正发生的挪关次数，明细与台账同源")
    @GetMapping("/stats")
    @RequiresPermissions("giprCaseFlow:list")
    @ResponseBody
    public AjaxResult stats() {
        Map<String, Object> data = new HashMap<>();
        // 各关在办单量（明细同源）
        int[] activeByStage = new int[4];
        for (int s = 0; s <= 3; s++) {
            activeByStage[s] = giprCaseFlowService.countActiveByStage(s);
        }
        data.put("activeByStage", activeByStage);
        // 真正发生的挪关次数（留痕同源）：按落到的关次分桶
        int[] transitionsTo = new int[4];
        for (int s = 0; s <= 3; s++) {
            transitionsTo[s] = giprCaseFlowService.countTransitions(s);
        }
        data.put("transitionsTo", transitionsTo);
        return AjaxResult.successData(200, data);
    }
}
