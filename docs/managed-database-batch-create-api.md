# 通用批量 CREATE TABLE

`POST /managed-database/datasources/{datasourceKey}/table-operations/batch` 使用 JSON 和既有 Bearer 服务密钥；datasourceKey 为 12 位小写十六进制，从 URL 获取固定挂载。不得传入数据库位置或凭据。

请求仅允许 `mode` 和 `sqls`：mode 必须为字符串 `CREATE`，sqls 为有序非空字符串数组，每项是一条完整 CREATE TABLE。拒绝未知字段、类型转换、空 SQL、重复规范表名、多语句、无显式主键、跨 Schema 和 IF NOT EXISTS；不改写原 SQL。支持通用表结构，不内置权限表名或 Seed INSERT。

批次 1～50 条，每条沿用 `managed-database.sql.max-sql-bytes`（默认 65,536 UTF-8 bytes），总 SQL 不超过 196,608 bytes，同时受既有 max-check-tables/max-check-bytes 配置约束。JSON 转义后的操作内容还须不超过 262,144 bytes。空值、重复、数量及字节越界返回 INVALID_ARGUMENT；非法 SQL 沿用 SQL_UNSUPPORTED，无主键沿用 PRIMARY_KEY_REQUIRED。

```sh
curl -X POST "${SERVICE_URL}/managed-database/datasources/12ab34cd56ef/table-operations/batch" \
  -H "Authorization: Bearer ${SERVICE_KEY}" \
  -H 'Content-Type: application/json' \
  --data '{"mode":"CREATE","sqls":["CREATE TABLE `12ab34cd56ef_role` (`id` bigint NOT NULL, PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='"'"'角色表'"'"';","CREATE TABLE `12ab34cd56ef_resource` (`id` bigint NOT NULL, PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='"'"'资源表'"'"';"]}'
```

以上仅为联通测试结构；调用方应提交自己的完整原始 DDL，仅替换目标表名，不以示例覆盖业务字段。

成功受理返回 HTTP 202，响应为 `{"returnCode":"SUC0000","errorMsg":null,"body":{"operationId":"100256","status":"RUNNING"}}`。编号保持字符串；202 仅代表管理记录已保存、任务已调度。全批预检失败没有操作记录，也不会创建前面合法的表。受理阶段提交结果未知时仍可能已保存记录，不得把错误响应当成安全重试证明，应查询历史。

所有表先解析、核对目录与真实结构；只有本资源 CREATED 且结构一致的表可跳过 SQL，目录外同名表不得认领。允许显式 CREATE 补建已确认不存在的归属表。外键仅可引用自身或当前已建成的本资源表，拒绝引用同批次尚未创建的表，即使它排在前面。预检期间的受管操作变化在事务锁内通过操作计数及目录状态再次核对；执行前仍回读真实状态。

沿用查询路径：

```text
GET /managed-database/datasources/{datasourceKey}/table-operations/{operationId}
GET /managed-database/datasources/{datasourceKey}/table-operations
```

批次详情和历史列表分别放在响应的 `body` 中。批次详情 mode 为 CREATE_BATCH，tableName 为空字符串；tableNames 按输入顺序排列。executionResult.steps 每表一步，保留 sequence、status、startedAt、finishedAt、errorCode、errorSummary、verification，并新增派生 tableName。恢复与人工审计字段不变，原 SQL 不在详情中返回。内部持久化动作名为 CREATE_TABLE_BATCH，allowDataLoss=false。

四表批次的典型结果（表中为步骤状态摘要，实际响应保留全部时间与证据）：

| 场景 | 操作状态 | 步骤 1～4 | 资源占用与物理结果 |
| --- | --- | --- | --- |
| 全部成功 | SUCCEEDED | SUCCEEDED / SUCCEEDED / SUCCEEDED / SUCCEEDED | 释放占用，四表均回读确认 |
| 第三表明确失败 | FAILED | SUCCEEDED / SUCCEEDED / FAILED / PENDING | 释放占用，前两表保留，第四表不执行 |
| 第三表结果未知 | UNKNOWN | SUCCEEDED / SUCCEEDED / UNKNOWN / PENDING | 保留占用，第三表实际结果待人工核对，禁止重放 |

步骤证据包含 sqlExecuted、tableExists 和 differences；未知事实使用 null，不伪造不存在。每步先保存 RUNNING 才发送 SQL，回读后将步骤结果与对应表目录状态同事务保存。保存链路失败尽力登记 UNKNOWN；若管理库不可用，最后记录可能仍为 RUNNING，资源占用保留供人工处置。

管理事务只保证整批目录、操作和资源占用原子受理，不保证 MySQL 多条 DDL 原子回滚。失败后不删除已成功的表。明确失败且占用已释放后，可用新请求全量预检，一致表跳过 SQL，缺失表补建。UNKNOWN 或遗留 RUNNING 必须先按 [人工处置流程](operation-recovery-runbook.md) 核对处理，查询或重启不会自动执行。

只读复核仍为 `POST .../tables/check`，请求 `{"sqls":["CREATE TABLE ..."]}`，不新增 mode。单表 CREATE/REBUILD 与其原请求合同保持不变。无需管理表迁移：仓库 DDL 的 action_type 为 varchar(32)，内容与结果为 JSON。部署数据库是否与仓库 DDL 一致仍须部署方确认；处置工具的实库语法和锁竞争也需在非生产 MySQL 验证。
