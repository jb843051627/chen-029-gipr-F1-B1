# gipr 模块口径交接（chen-029）

本模块是一组"形状"练习：状态机、签批链、批处理、规则求值、定时任务。
口径全部定死在 Service 实现类的类注释里，改代码前先读注释。本文只给接手人一张地图。

## 1. 流转单 t_gipr_case_flow（状态机）

- 四关：`0申请受理 → 1材料初审 → 2标志核准 → 3备案发标`；推到 3 **即封尾**（status=2）。
- 挪关一律 **CAS 条件更新**（id＋动前 stage＋status=1＋del_flag=0）。
  同一关连点/并发两回，只有一发命中；**只有命中的那一发**往 `t_gipr_case_flow_log` 落一条痕。
- 封尾单**四锁**：不能推进、不能撤回、不能改 content、不能删。
- 删除走软删（del_flag=1），留痕随单保留，环节链不断。
- 撤回只退一格（不再一把清到 0）；在第 0 关撤回被拒。
- **封尾联动备案册**：按 biz_no=holder_no 把 `t_gipr_holder_book` 在册行置 1已核齐，只随封尾发生一次。
- 台账明细与统计同源：活单走 `liveRows()`（del_flag=0）；动作次数走留痕表，二者不许混算。
  - 各关在办单量 `countActiveByStage`
  - 各关真正发生的挪关次数 `countTransitions`（数留痕，不数单子）
- 老数据：`normalizeLegacy()` 批量归一卷入新流程；每次动作前 `repairLegacyRow` 就地归一。
  早年 stage/status 为空、或 stage=3 没封尾的单子，不补会在封尾关判错。
- 手动建档：`POST /giprCaseFlow/create`，落在第 0 关、在办，落一条 create 痕。

## 2. 签批单 t_gipr_cert_bill（签批链）

- 三关：`0县级初核 → 1市级核认 → 2省局核准`；省局算齐即点齐（status=1）。
- 算齐办法 signMode：`0一名即齐`（一票过）；`1两名须同判`（needCount 名**不同**签批人都赞成，默认2）。
  到票以 `t_gipr_cert_sign` 里本关 distinct approver 为准——**同一个人点两回凑不齐**。
- 同人同关同动作只认一票（留痕表唯一键 `(bill_id,node_no,approver,action)` 兜底），第二回幂等。
- 否决一票即压回本关（status=2），节点不动；已点齐/已压回三锁（签/否决/退回都拒）。
- 退回上一节点只在在签时可做，退一格且本关计票归零。

## 3. 转档核收行 t_gipr_archive_row（批处理）

- 空批返 0；超 500 行返 -1 且整批不入。
- 同 batchNo 重复提交幂等：库里已有该批次任何行，第二回不重插，回已收下（status=1）行数。
- 合法行（item_code 非空、qty 为正）落 1已收下；非法行落 2已退回，**保留原册行次**（从1起原始下标）。
  两类同批各落各的，互不连坐。

## 4. 产量贴线 t_gipr_yield_rule（规则求值）

- 可用：del_flag=0 且 status=0 且 at ∈ **[eff_start, eff_end)**（启用当日含，交班当日不含；end 空=长期有效）。
- 同代号多版本：取已启用版本里**启用日最新**者（并列 priority 大 → id 大）。
- 多规则：priority 降序、ruleCode 降序、id 降序。
- 分档：**等于上限取高一档**（input<th1→1，th1≤input<th2→2，…），越顶 4 档；输入越界/缺参返 0。
- `listAvailable / usable / countAvailable` 同一套筛选，计数直接取列表长度。

## 5. 核查提醒 t_gipr_check_item（定时任务）

- 执行窗口：每日凌晨 **[02:00, 05:00)**，窗口外 listDue 为空。
- 到期：at ≥ due_at − amount 日（amount=至多提前几日，空/非正按0），恰好到点算到期。
- 只捞 status=0 且未删；runOnce 逐条 CAS 置 1已发出，单条失败置 2 并继续，回真正发出条数。

## 6. 多语言词条 t_gipr_termbook（普通 CRUD）

- 列表统一 status=0 AND del_flag=0；分页归调用方 PageHelper，Service 不私自 startPage。
- lack_num = crt_num − hit_num，轧算并钳到 ≥0，不认手填。
- 删除一律软删；挂靠名录已移出（product status=1）不再收新词条。

## 验证

- 单测（无 DB，JUnit5 + Mockito）：`src/test/java/com/fc/v2/service/impl/`，34 条钉死上述口径。
- 跑：`mvn -o test -Dtest='com.fc.v2.service.impl.*Test'`
- 建表：`doc/schema/gipr.sql`（含两张新留痕表）。
