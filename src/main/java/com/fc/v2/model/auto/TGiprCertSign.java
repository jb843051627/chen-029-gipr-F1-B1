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
 * 签批单签票留痕对象 t_gipr_cert_sign
 *
 * <p>同一关同一签批人只认一票：连点两回不重复计票、不重复落痕。
 * “两名须同判”靠本表里同关两个不同签批人成立，一个人点两回凑不齐。</p>
 *
 * @author fuce
 * @date 2026-10-07
 */
@TableName("t_gipr_cert_sign")
@ApiModel(value = "TGiprCertSign", description = "签批单签票留痕")
public class TGiprCertSign implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 签批单主键 */
    @TableField("bill_id")
    @ApiModelProperty(value = "签批单主键")
    private Long billId;

    /** 签在哪一关 0县级初核/1市级核认/2省局核准 */
    @TableField("node_no")
    @ApiModelProperty(value = "签在哪一关")
    private Integer nodeNo;

    /** 签批人 */
    @TableField("approver")
    @ApiModelProperty(value = "签批人")
    private String approver;

    /** 动作 approve 赞成 / reject 否决 */
    @TableField("action")
    @ApiModelProperty(value = "动作 approve/reject")
    private String action;

    /** 签批意见 */
    @TableField("comment")
    @ApiModelProperty(value = "签批意见")
    private String comment;

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

    public Long getBillId() {
        return billId;
    }

    public void setBillId(Long billId) {
        this.billId = billId;
    }

    public Integer getNodeNo() {
        return nodeNo;
    }

    public void setNodeNo(Integer nodeNo) {
        this.nodeNo = nodeNo;
    }

    public String getApprover() {
        return approver;
    }

    public void setApprover(String approver) {
        this.approver = approver;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
