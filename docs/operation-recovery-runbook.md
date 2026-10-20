# 未完成操作人工处置

适用当前 datasourceKey 模型及 CREATE_TABLE、REBUILD_TABLE、DROP_TABLE、RELEASE_DATASOURCE。仅处理 RUNNING / UNKNOWN 原操作，不重放 SQL、不恢复数据、不根据超时直接释放。

用户 recovery 只登记停止意图。无法由正常工作线程确认结束的操作，由运维执行本流程；最终结果仍通过原操作详情或 recovery 的 STATUS 展示，无须另建产品强制释放接口。

## 责任与执行前提

运维接收原 datasourceKey、operationId，并关联维护记录。先停止全部可能继续执行原操作的实例、旧进程及调度任务，阻止维护期间的新受管操作；原目标表的外部写入及结构变更也须隔离。原操作可能已获准但尚未发送下一条 SQL，不能只检查当前没有数据库语句就放行。

随后在原 datasourceId 对应的目标库核对原会话、语句及锁，必要时由目标库管理员终止原执行并等待结束。进程退出、客户端断连、取消或 KILL 请求已提交都不能单独证明服务器执行已结束。没有确定证据则保留占用，由运维继续排查，不能签署虚假的确认标记。

处置原因及核对证据仅填写非敏感摘要或维护记录编号，不填写密码、Token、服务密钥、数据库连接信息、SQL 原文或完整请求。数据库账号审计由管理库取得，产品详情会移除 databaseActor。

## 工具与调用边界

- [管理库巡检](../src/main/resources/db/manual/table-operation-inspect.sql)：查看原操作、当前占用、冻结表清单、步骤及目录；30 分钟只是排查条件。
- [原目标库核对](../src/main/resources/db/manual/table-operation-target-inspect.sql)：按冻结的精确表清单及原挂载大小写规则读取表存在性，不按前缀扫描。
- [管理库原子处置工具](../src/main/resources/db/manual/table-operation-abandon.sql)：安装 `md_abandon_database_operation`，不会自动调用。

工具要求 MySQL 8.0 和当前三张 InnoDB 管理表，不适用于其他表结构。过程使用 SQL SECURITY INVOKER；由受控运维账号安装及调用，业务服务账号不得获得该过程执行权限。安装需要 CREATE ROUTINE，调用需要 EXECUTE、三张管理表的 SELECT，以及目录 status、操作 status/execution_result、资源 active_operation_id 的 UPDATE 权限。

使用专用新连接，在预先选定的管理库安装和调用，安装与调用均不得位于外层事务中。过程自行 START TRANSACTION，不能嵌套调用。不要使用客户端 `--force` 忽略错误。已有同名过程时停止安装并核对原工具，禁止自动覆盖；例行初始化脚本不包含此过程。结束维护后可由运维明确执行 `DROP PROCEDURE md_abandon_database_operation` 撤下工具。

## 处置步骤

1. 管理库核对：按指定 datasourceKey、operationId 查看原挂载、动作、当前占用与 `execution_json.tableNames`。不要用 appKey、表名前缀或最新实体设计替代原操作冻结清单。
2. 完成上述停止执行与目标 SQL 结束核对，在维护记录中保留依据。
3. 在原目标库读取冻结清单的全部实际表。非空对象类型必须为 BASE TABLE；出现视图、无法读取或不明确结果时停止，不能签署为存在或不存在。读取只确认存在性，不表示结构符合设计。
4. 准备逐表结果，格式为 `[ {"tableName":"精确目录键","exists":true} ]`。exists 必须为 JSON 布尔值，不接受 0/1、字符串或 null。每项仅包含这两个字段。结果必须恰好覆盖原操作清单，无遗漏、额外项或重复；顺序不必一致。空资源 RELEASE 唯一合法结果为 `[]`，仍保留原操作的一个逻辑步骤。
5. 安装运维过程，在同一个维护窗口中调用预演，最后参数保持 NULL 或 0。只有返回 `DRY_RUN_ROLLED_BACK` 才表示全部校验和拟写入已通过并回滚；预演不产生持久化审计，也不释放占用。
6. 核对预演结果及当前维护隔离仍有效，然后使用同一组参数、最后参数 1 正式提交。任何 SQL 错误立即停止，事务全部回滚，不能绕过条件修改字段。
7. 用管理库只读查询确认原操作 FAILED、errorCode=MANUALLY_ABANDONED、审计存在，并确认原占用已释放。若提交响应丢失，先查原记录，不能猜测是否成功或重新执行目标 DDL。
8. 恢复服务后从原操作详情或 recovery STATUS 获取 `MANUALLY_ABANDONED`。重新检查当前全部 SQL，再按实际缺表或结构差异发起 CREATE / REBUILD。人工放弃清理操作后，仍可通过正常 cleanup 流程继续清理。

### 参数合同

| 顺序 | 参数 | 规则 |
| --- | --- | --- |
| 1 | datasourceKey | 原操作的 12 位资源标识 |
| 2 | operationId | 原操作 BIGINT 正整数，SQL 中使用精确整数，不经浮点转换 |
| 3 | datasourceId | 原固定挂载编号，1～255 |
| 4 | operator | 实际处置人，去空白后 1～128 字符 |
| 5 | reason | 处置原因，去空白后 1～500 字符 |
| 6 | evidence | 核对记录编号或非敏感依据，去空白后 1～1000 字符 |
| 7 | workersStopped | 全部相关执行任务已停止并阻止继续发 SQL，必须为 1 |
| 8 | sqlEnded | 原目标数据库执行已确认结束，必须为 1 |
| 9 | observedTables | 完整逐表存在性 JSON 数组 |
| 10 | commit | NULL / 0 预演回滚；仅明确传 1 才提交 |

以下参数默认不可执行，填写前必须完成核对。不要把填有真实运维信息的副本提交仓库：

```sql
SET @recovery_key = '';
SET @recovery_operation = 0;
SET @recovery_mount = 0;
SET @recovery_operator = '';
SET @recovery_reason = '';
SET @recovery_evidence = '';
SET @recovery_workers_stopped = 0;
SET @recovery_sql_ended = 0;
SET @recovery_observed = JSON_ARRAY();
CALL md_abandon_database_operation(
  @recovery_key, @recovery_operation, @recovery_mount,
  @recovery_operator, @recovery_reason, @recovery_evidence,
  @recovery_workers_stopped, @recovery_sql_ended, @recovery_observed,
  NULL
);
```

逐表结果示例（示例标识与状态不代表真实核对）：

```json
[
  {"tableName":"12ab34cd56ef_child","exists":false},
  {"tableName":"12ab34cd56ef_orders","exists":true}
]
```

## 事务与审计保证

工具按资源、表名固定顺序的目录、操作进行加锁，核对归属、挂载、有效性及当前占用。操作只允许 RUNNING / UNKNOWN，原清单和步骤必须符合当前动作；RELEASE 还核对全部保留目录与原清单一致。锁定操作后再次核对内容及结果快照，变化即回滚。

一个管理事务内完成：原操作记 FAILED / MANUALLY_ABANDONED、追加 `execution_result.manualResolution`、按真实存在性修正目录 CREATED / DROPPED、按 operationId 条件释放资源占用。任何错误或条件写入失败全部回滚；无物理 DROP / CREATE，不连接目标库执行 SQL。

原 SQL、tableNames、steps、步骤时间、startedAt、finishedAt 和 abandonRequest 保留；顶层错误码与摘要改为人工结束，原错误码与摘要转存审计。resolvedAt 是独立人工处置时间，不冒充 DDL 完成时间。审计包含处置人、内部数据库账号、原因、核对依据、原状态、动作、逐表核对结果以及修正前的目录状态，并受当前结果 256 KiB 上限保护。

工具不软删目录、不释放表名、不更新资源 REGISTERED。即使 RELEASE 的全部表实际已缺失，人工放弃也不直接宣告资源 RELEASED；后续正常清理流程才负责完成资源回收。

已人工结束的同一操作再次调用返回 `ALREADY_RESOLVED`，不改首次审计、不更新目录、不检查或释放后来操作的占用；后续资源已经 RELEASED 时也可返回原处置结果。重复调用仍需提供有效基础参数，但不要求重新核对历史表清单。对普通 SUCCEEDED / FAILED 操作、占用不匹配、清单损坏、软删或归属冲突，工具明确拒绝。

## 状态展示与验证边界

用户申请后的 RUNNING 显示 STOP_REQUESTED，UNKNOWN 显示 MANUAL_REQUIRED；人工处置后显示 MANUALLY_ABANDONED。RUN_FINISHED 只代表本次查询交互结束，必须读取业务状态。实际处置通过原操作查询对用户可见，内部数据库账号不会出现在产品详情。

服务不会自动安装或调用此工具，不通过定时任务、超时、重启或用户确认直接释放未知占用。SQL 中的两个确认标记是运维签署结果，不能替代原目标执行结束的证据。

仓库测试只验证人工结果合同与现有 Codec、详情状态及审计脱敏的兼容性，不连接实库，不验证存储过程语法、锁竞争和数据库回滚。部署人员须在其 MySQL 8.0 非生产环境中审阅并预演：四种动作、空 RELEASE、遗漏/重复/额外表、错误布尔类型、归属与占用冲突、默认回滚、正式提交、部分写入失败回滚、以及新操作占用期间重复调用。没有完成实库验证前，不把测试通过等同于工具已完成数据库联调。

MySQL 语法依据：[存储过程](https://dev.mysql.com/doc/refman/8.0/en/create-procedure.html)、[JSON 函数](https://dev.mysql.com/doc/refman/8.0/en/json-functions.html)、[SIGNAL](https://dev.mysql.com/doc/refman/8.0/en/signal.html)。
