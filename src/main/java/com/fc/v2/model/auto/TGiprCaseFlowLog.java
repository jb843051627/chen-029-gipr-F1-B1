package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.Date;

/**
 * 流转单一关一记留痕对象 t_gipr_case_flow_log
 *
 * <p>口径定死：只有真正挪关成功的动作（CAS 条件更新命中一行）才准落一条；
 * 双击的第二次、终态单被拒的动作一律不落痕。</p>
 *
 * @author fuce
 * @date 2026-10-07
 */
@TableName("t_gipr_case_flow_log")
@ApiModel(value = "TGiprCaseFlowLog", description = "流转单一关一记留痕")
public class TGiprCaseFlowLog implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 流转单主键 */
    @TableField("flow_id")
    @ApiModelProperty(value = "流转单主键")
    private Long flowId;

    /** 办件件次（冗余，便于跨单查痕） */
    @TableField("biz_no")
    @ApiModelProperty(value = "办件件次")
    private String bizNo;

    /** 动前关次（新建为 -1） */
    @TableField("from_stage")
    @ApiModelProperty(value = "动前关次")
    private Integer fromStage;

    /** 动后关次 */
    @TableField("to_stage")
    @ApiModelProperty(value = "动后关次")
    private Integer toStage;

    /** 动作 create 受理建档 / advance 推进一档 / rollback 撤回一档 */
    @TableField("action")
    @ApiModelProperty(value = "动作 create/advance/rollback")
    private String action;

    /** 操作人 */
    @TableField("operator")
    @ApiModelProperty(value = "操作人")
    private String operator;

    /** 落痕时间 */
    @TableField("create_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "落痕时间")
    private Date createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getFlowId() {
        return flowId;
    }

    public void setFlowId(Long flowId) {
        this.flowId = flowId;
    }

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public Integer getFromStage() {
        return fromStage;
    }

    public void setFromStage(Integer fromStage) {
        this.fromStage = fromStage;
    }

    public Integer getToStage() {
        return toStage;
    }

    public void setToStage(Integer toStage) {
        this.toStage = toStage;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
