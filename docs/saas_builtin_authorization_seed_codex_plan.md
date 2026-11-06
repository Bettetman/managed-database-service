# Managed Database SaaS：Builtin 权限 Seed 批量写入实施方案（Codex）

> 适用仓库：`https://github.com/Bettetman/managed-database-service`，目标分支 `main`（实施前以本地最新代码为准）。  
> 依赖：XCodeAgent `dev_agent` 的 Builtin Schema Bootstrap 已成功创建四张权限表。  
> 本文是可交给 Codex 执行的改造任务，不代表代码已经实施。

## 0. 实施指令与边界

在 `main` 最新代码上**新增 SaaS 受控权限初始化批量 INSERT 能力**，供 XCodeAgent 在权限四表 Schema 就绪后调用。复用既有资源注册/归属校验、Bearer 鉴权、固定物理挂载、执行审计和 `active_operation_id` 互斥机制。

**不修改**现有 `POST /table-operations/batch` 的 CREATE 合同；不向客户端开放 SQL 字符串、JDBC 地址或密码；不修改通用表数据预览和现有运行时连接接口；不执行任何 DDL / DROP / UPDATE / DELETE。首期仅允许 `AUTHORIZATION_SEED_V1` 的四张权限表、约定列及 INSERT，未来通用写入能力另行设计。

当前代码可参考：

- `src/main/java/com/cmbchina/managedb/application/TableOperationService.java`
- `src/main/java/com/cmbchina/managedb/application/TableReadinessService.java`
- `src/main/java/com/cmbchina/managedb/domain/repository/TableOperationRepository.java`
- `src/main/java/com/cmbchina/managedb/infrastructure/persistence/MybatisTableOperationRepository.java`
- `src/main/java/com/cmbchina/managedb/infrastructure/persistence/TableOperationCodec.java`
- `src/main/java/com/cmbchina/managedb/domain/DatabaseSchemaPort.java`
- `src/main/resources/db/manual/managed-database-schema.sql`
- `docs/managed-database-batch-create-api.md`

**重要设计取舍**：现有 `TableOperationCodec` 的 action_type 仅支持 CREATE/REBUILD/DROP/RELEASE。不要直接将 INSERT 动作写入现有 `md_database_operation` 并导致原 `table-operations` 历史及详情读取失败。本次采用**独立 `md_data_operation` 表 + 复用资源级 active_operation_id**；与现有 DDL 操作互斥，但不修改 DDL 操作的 JSON 恢复合同。

## 1. SaaS 新增 HTTP 合同

统一路径前缀：`/managed-database/datasources/{datasourceKey}`，要求现有 Bearer 服务密钥，不提供给 Renderer 或 Java 业务应用。

| 方法 | 路径 | 语义 |
|---|---|---|
| POST | `/data-operations/batch` | 提交**一次**权限 Seed 批量 INSERT，成功受理 HTTP 202 |
| GET | `/data-operations/{operationId}` | 只读查询指定数据操作的终态和安全摘要 |
| GET | `/data-operations/by-key/{idempotencyKey}` | 响应丢失、客户端重启时按幂等键只读查找原操作；未找到返回明确 404，不自动提交 |

注意路由中 `by-key` 必须优先于泛型 `{operationId}` 注册/匹配。响应遵循 SaaS 现有 JSON 包装 `returnCode/errorMsg/body`，operationId 保持十进制**字符串**。

**提交请求示例（示例 key 为虚构值，不可直接用于生产）：**

```json
{
  "purpose": "AUTHORIZATION_SEED_V1",
  "idempotencyKey": "3f5c61dc4ef7b3a2e0158d9cb7a6239e35a7d24739c8d2e566317b906455d3c7",
  "tables": [
    {
      "tableName": "12ab34cd56ef_role",
      "rows": [{
        "role_id": "SYSTEM_ADMIN",
        "role_name": "系统管理员",
        "description": "内置权限系统管理员",
        "enable": 1,
        "is_system": 1,
        "is_deleted": 0,
        "created_by": "SYSTEM"
      }]
    },
    {
      "tableName": "12ab34cd56ef_resource",
      "rows": [{
        "key": "system_authorization_management",
        "name": "权限管理",
        "description": "管理角色、成员角色与角色资源关系。",
        "origin": "system",
        "type": "system",
        "target_resource_ref": "system:authorization_management"
      }]
    },
    {
      "tableName": "12ab34cd56ef_role_resource",
      "rows": [{
        "role_id": "SYSTEM_ADMIN",
        "resource_key": "system_authorization_management",
        "created_by": "SYSTEM"
      }]
    },
    {
      "tableName": "12ab34cd56ef_role_member",
      "rows": [{
        "role_id": "SYSTEM_ADMIN",
        "member_id": "admin-subject-001",
        "member_name": "admin-subject-001",
        "created_by": "SYSTEM"
      }]
    }
  ]
}
```

实际请求必须包含 manifest 中的**所有**资源点（并且恰好一条 `system_authorization_management`）；四个表必须各出现一次，顺序固定为 `role` → `resource` → `role_resource` → `role_member`，不得由服务补猜资源点或管理员。`id` 为自增列，不允许由请求传入；`created_at` 等由数据库默认值生成。

成功受理示例：

```json
{"returnCode":"SUC0000","errorMsg":null,"body":{"operationId":"100256","status":"RUNNING","purpose":"AUTHORIZATION_SEED_V1"}}
```

GET 终态示例（绝不返回 rows、Subject 清单、SQL、密码）：

```json
{
  "returnCode":"SUC0000","errorMsg":null,
  "body": {
    "operationId":"100256","purpose":"AUTHORIZATION_SEED_V1",
    "status":"SUCCEEDED","datasourceKey":"12ab34cd56ef",
    "requestFingerprint":"sha256:<64hex>",
    "tableNames":["12ab34cd56ef_role","12ab34cd56ef_resource","12ab34cd56ef_role_resource","12ab34cd56ef_role_member"],
    "createdCount":5,"existingCount":0,"verified":true
  }
}
```

`requestFingerprint` 由服务端根据**规范化的 purpose + datasourceKey + 四表有序数据**计算，客户端独立计算并对比；不是客户端可伪造的“已执行”证据。`status ∈ RUNNING/SUCCEEDED/FAILED/UNKNOWN`；`SUCCEEDED` 必须意味着**事务已提交且预期自然键已回读核验**，仅 202 / RUNNING 不得代表 Seed 成功。明确失败可返回安全错误码；不透传 SQL/JDBC 异常正文。

## 2. 严格输入约束和表归属

1. 严格 DTO 验证：拒绝未知字段、空字符串、无效类型、重复表、重复业务键、负数/越界数据、额外 SQL 字段；不做隐式类型转换。
2. `purpose` 只允许 `AUTHORIZATION_SEED_V1`；`idempotencyKey` 只允许 64 位小写十六进制，不能为空。
3. 四个物理表必须**精确等于** `{datasourceKey}_role`、`{datasourceKey}_resource`、`{datasourceKey}_role_resource`、`{datasourceKey}_role_member`，禁止指定 schema、带点表名、任意其他表或跨 datasourceKey 表。
4. 从 SaaS 权威注册绑定读取 `datasourceId`，并校验四张表均在 `md_datasource_table` 当前资源目录下、状态为 CREATED、真实存在；预检与执行前均检查资源级占用及必要的列、主键、唯一索引，缺失、冲突、结构错误 fail-closed。
5. `role.role_id` 必须是 `SYSTEM_ADMIN` 且唯一一行；`enable=1,is_system=1,is_deleted=0`。`role_resource` 只允许 `SYSTEM_ADMIN → system_authorization_management` 唯一一条；`role_member` 只允许 SYSTEM_ADMIN，成员不得重复，至少一人。
6. `resource` 仅允许 `origin=system/business`，`type=system/page/operation`；系统资源恰好一条且 `key=system_authorization_management`、`origin/type=system/system`、`target_resource_ref=system:authorization_management`；其他资源必须是有效的 page/operation 类型与 ref 格式。字段长度与四表实际 DDL 一致（role_id 32、resource.key 64、name 32、description 128、member_id/name 32、ref 255）。
7. 限制资源数、Subject 数、总行数和请求总字节数；建议初始上限 `resources<=500`、`admins<=100`、总 JSON `<=256 KiB`，超过明确拒绝，**不截断**。Codex 将其集中为可测试配置常量。
8. 仅做“首次系统权限 Seed”，不接受业务默认角色/业务授权数据。拒绝任何任意 UPDATE、DELETE、UPSERT 覆盖字段及 INSERT SQL 文本。

## 3. 幂等、并发、事务与状态机（重点）

### 3.1 新增管理表（需手工迁移）

在 `src/main/resources/db/manual/managed-database-schema.sql` **追加**第四张管理表 `md_data_operation`，再提供一个适用于现有三表部署的**独立增量 SQL**（例如 `src/main/resources/db/manual/202611_add_data_operation.sql`），禁止应用启动时自动建表/ALTER。字段至少包括：

- `id BIGINT PRIMARY KEY`（复用服务现有雪花 ID 生成器）
- `datasource_key`、`datasource_id`、`purpose`、`idempotency_key CHAR(64)`、`request_fingerprint CHAR(64)`
- `status`、`payload_json JSON`（仅内部执行使用，包含受限种子数据）、`result_json JSON`（安全计数及错误码）
- `created_at`、`updated_at`、可选完成时间
- `UNIQUE(datasource_key, idempotency_key)` + 状态和时间索引

管理表 `payload_json` 可能包含初始管理员 Subject ID：数据库权限应限制为 SaaS 服务自身可读；**任何外部 GET、日志、异常、AG-UI、前端状态均不得返回或输出其原文**。不可直接重用 `TableOperationCodec` 读取新 action_type；通过新 DataOperation 聚合与独立 Codec/Repository 实现。

### 3.2 受理阶段

1. 严格校验 DTO、表名与 schema；构造确定性 canonical payload，服务端计算 SHA-256。
2. 在同一**管理库短事务**中锁定当前 `md_datasource_resource`（与 CREATE/REBUILD 使用相同的资源行锁），查询 `md_data_operation` 中 `(datasourceKey,idempotencyKey)`：
   - 已存在且指纹一致：返回**原 operationId 与原状态**，不新建、不重放；
   - 已存在但内容不同：409 `IDEMPOTENCY_CONFLICT`；
   - 不存在但资源有 active_operation_id：409 `RESOURCE_BUSY`；
   - 不存在且未占用：保存 RUNNING 操作与 payload，并将 `active_operation_id` 原子设置为当前 operationId。
3. 提交管理事务后调度唯一工作任务；202 仅表示操作已受理。调度明确失败时写入 FAILED 并按原占用 ID 释放；是否已经开始执行无法证明时必须置 UNKNOWN/保留占用，不可重新调度。
4. 主动阻止由于 HTTP 重试、双击、服务多实例、进程重启导致第二次 INSERT；不能仅依赖内存锁或前端缓存。幂等查找的路径不得触发新写操作。

### 3.3 执行阶段

在**同一个业务挂载数据库连接和一个 InnoDB 事务**中执行四表的全部写入（不是每行一次提交）：

- 按顺序处理 `role` → `resource` → `role_resource` → `role_member`。
- 每行按数据库唯一自然键查询/锁定：`role.role_id`、`resource.key`、`role_resource(role_id, resource_key)`、`role_member(role_id, member_id)`。
- 不存在：用固定白名单列和 PreparedStatement INSERT；存在：**不 UPDATE、不 DELETE**，校验系统角色与固定系统资源不可变约束。业务资源如果 key 相同但 origin/type/ref 与本次目标明显冲突，拒绝并回滚，不静默覆盖。唯一键竞争时不可“INSERT IGNORE 后算成功”，须回读核验目标身份。
- 所有必需自然键在事务内可读并通过校验后提交；成功统计 createdCount/existingCount/verified，不回显数据行。
- 任何行失败：`ROLLBACK` 整批事务，持久化 FAILED 安全错误码，不允许部分成功被标记为 SUCCEEDED。
- **跨管理库与业务挂载无法组成单个原子事务**：如果业务库已 COMMIT，但写入管理库 SUCCEEDED 失败，保留 RUNNING/UNKNOWN，不自动再次执行。通过原操作查询或人工只读核对处理，严禁“异常就重试 INSERT”。

### 3.4 占用释放与恢复

- SUCCEEDED / 明确 FAILED：按**原 operationId** 条件释放 `active_operation_id`。
- UNKNOWN、无法判断是否已提交、进程中断：保留占用，需人工核对；不重试 DML。
- 新旧 DDL 操作必须共享同一资源占用，Seed 运行时不能并行 CREATE/REBUILD/DROP/RELEASE。已有 `table-operations` 的详情/历史仍只读取原表，不能因新增 Seed 记录反序列化失败。
- 按幂等键 GET 可在受理响应丢失后找到原记录；GET 不得调用执行器或提交 SQL。
- 因进程终止而残留的 RUNNING，不得启动自动补执行，须提供明确的人工核验、恢复/处置说明（保留审计）。

## 4. 推荐代码改动（命名可按仓库风格落地）

| 层次 | 改造任务 |
|---|---|
| adapter/web | 新增 `DataOperationController` 和严格请求/响应 DTO；沿用现有鉴权、异常转换与统一包装 |
| application | 新增 `AuthorizationSeedService`：校验、受理、唯一调度、事务执行、结果查询 |
| domain | 新增 `DataOperation`、`AuthorizationSeedPayload`、`DataOperationRepository`、`AuthorizationSeedPort`；领域层不依赖 JDBC/Spring/Jackson |
| infrastructure | 新增 `MybatisDataOperationRepository`、`DataOperationCodec`、受控 JDBC `AuthorizationSeedJdbcAdapter`；管理事务与业务事务分离 |
| bootstrap | 为新增 Service/Repository/Port 装配依赖，复用固定挂载、Executor 和服务级限额配置 |
| resources/db/manual | 追加新建管理表完整 DDL 与独立增量迁移 SQL |
| docs | 新增 `docs/managed-database-authorization-seed-api.md`，列明请求、响应、限制、恢复、迁移、curl |

先读取现有 HTTP 控制器、`TableTaskDispatcher`、管理行锁/数据库挂载适配实现，再决定应复用的基础组件；**不得重写既有批量 CREATE 或放宽白名单 SQL 解析器**。

## 5. 测试与验收

至少新增以下自动化测试（使用 Mock、内存数据库无法证明的 MySQL 行锁/事务需单独集成测试）：

1. 四张表+系统管理员+资源点+系统授权+管理员成员，一次提交全量落库；已有匹配自然键只跳过，不覆盖。
2. 单表不存在、目录外表、前缀不符、表结构冲突、系统角色/资源不变量不符合时拒绝且**零写入**。
3. 第四表 INSERT 失败时，前面三表 DML **全部回滚**；不出现部分 Seed 成功。
4. 同幂等键同内容重复调用：始终同一个 operationId；同键不同内容返回冲突；并发两个请求仅一次受理。
5. 原操作 RUNNING/UNKNOWN、数据表正在 CREATE/REBUILD、服务重启、网络超时及提交后管理库回写失败：不得自动二次提交。
6. 字段白名单、行数/字节限制、非法 role/resource/member 关系与 SQL 注入字符均受控；只允许值作为参数，不能插入 SQL 结构。
7. 所有 GET、日志、异常均不含密码、Subject 列表、行数据或执行 SQL。
8. 原 CREATE_BATCH、查询历史、表数据预览、runtime-connection 和清理流程原有用例全部通过。

**本地建议命令**（先确认测试工程及环境）：

```bash
mvn -B -ntp -DskipTests compile
mvn -B -ntp test
```

**真实 MySQL 集成验收**：先部署新管理表，再在受控测试挂载创建四张带前缀权限表，提交 Seed，按 operationId 查询 SUCCEEDED；读取物理库核对自然键与重复执行无增量；故障注入验证回滚与 UNKNOWN，不在生产库操作。

## 6. 完成定义（Codex 必须逐条给出验证结果）

- [ ] 新增 POST /data-operations/batch、GET /{operationId}、GET /by-key/{idempotencyKey}，使用现有 JSON 包装。
- [ ] 使用独立数据操作记录，提供完整/增量管理表 DDL，并说明手工部署步骤。
- [ ] 原 DDL 操作与 Seed 共用 datasource 资源互斥，占用与操作状态可跨重启恢复。
- [ ] 四表全部 INSERT 位于**同一业务 DB 事务**；仅插入缺失数据，不修改运行态现有数据。
- [ ] 收到 202 不被标记成功，只有 SUCCEEDED + verified 才代表落库成功。
- [ ] 同请求天然幂等，UNKNOWN 不自动重发，可按幂等键找回原操作。
- [ ] 不影响原批量 CREATE / 单表 / 元数据 / 预览 / 连接下发接口。
- [ ] 更新接口文档、迁移脚本、单测及真实 MySQL 联调记录。

**Codex 交付说明**：列出修改文件、实际部署 SQL、接口示例、测试命令及结果、未验证环境条件；禁止只改 README 而不实现或伪称已真实联调。
