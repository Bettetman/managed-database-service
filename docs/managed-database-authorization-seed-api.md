# SaaS 权限 Seed 接口

本接口由 XCodeAgent 后端在 Builtin Schema Bootstrap 完成后调用，使用现有 SaaS Bearer 服务密钥。Studio 产品侧通过自己的 AG-UI 展示确认、进度和结果。SaaS 的服务间合同使用 JSON 包装 `returnCode`、`errorMsg`、`body`。

## 部署

- 已有三张管理表的部署在**管理库**手工执行 [增量脚本](../src/main/resources/db/manual/202611_add_data_operation.sql)，再部署应用。新环境使用 [完整建表脚本](../src/main/resources/db/manual/managed-database-schema.sql)。两个脚本择一执行。
- `md_data_operation.payload_json` 包含 Subject ID。仅授权 SaaS 服务和受控审计人员访问该表，数据库语句审计、SQL 代理及链路采样按敏感数据处理。关闭记录绑定参数的日志和 APM 采集。
- 业务挂载沿用资源注册时固定的 datasourceId。四张目标表必须归属该资源、目录状态为 CREATED，且是 utf8mb4 的 InnoDB 基表，带自增整型 `id` 主键及下列自然键的完整唯一索引。自然键列必须为 NOT NULL。挂载账号需要 SELECT、INSERT、元数据可见性及检查触发器的权限。目标表禁止触发器。MySQL 对没有 TRIGGER 权限的账号隐藏触发器元数据，部署时须确认账号具备相应可见性，并核对四表实际无触发器。
- 平台管理操作共享数据库资源行锁。部署和人工恢复期间须控制直接访问 MySQL 的其他写入者。

## 请求和结果

资源前缀为 `/managed-database/datasources/{datasourceKey}`。

| 方法 | 路径 | 返回 |
| --- | --- | --- |
| POST | `/data-operations/batch` | 202，回执包含字符串 `operationId`、`status`、`purpose` |
| GET | `/data-operations/{operationId}` | 200，安全摘要 |
| GET | `/data-operations/by-key/{idempotencyKey}` | 200，原操作的安全摘要。不存在时返回 404 |

请求接受 UTF-8 JSON，最多 256 KiB，固定四张表及以下顺序。表名均由 `{datasourceKey}_` 加表后缀组成。

| 表后缀 | 自然键 | 请求列 |
| --- | --- | --- |
| `role` | `role_id` | `role_id, role_name, description, enable, is_system, is_deleted, created_by` |
| `resource` | `key` | `key, name, description, origin, type, target_resource_ref` |
| `role_resource` | `role_id, resource_key` | `role_id, resource_key, created_by` |
| `role_member` | `role_id, member_id` | `role_id, member_id, member_name, created_by` |

每列都必填。最多 500 个资源、100 个管理员、602 行。字段上限按 Unicode 字符计算。角色和成员标识及名称、`created_by` 均为 32 字符，资源键 64 字符，资源名称 32 字符，描述 128 字符，资源引用 255 字符。整数只接受 JSON 整数。数据库生成 `id` 和时间字段。

角色只有一行 `SYSTEM_ADMIN`，三个标志依次为 1、1、0。资源必须包含且仅包含一条 `system_authorization_management` 系统资源，`origin/type` 为 `system/system`，引用为 `system:authorization_management`。业务资源 `origin=business`，`type=page|operation`，引用以对应的 `page:` 或 `operation:` 开头，后跟由字母、数字、下划线和连字符组成的非空片段，片段可用点、冒号或斜杠连接。唯一的角色资源关系是 `SYSTEM_ADMIN` 授权系统资源。至少一个管理员成员，均属于 `SYSTEM_ADMIN`。所有请求中的 `created_by` 固定为 `SYSTEM`。

调用方从 manifest 提交全量资源点，并提供已确认的管理员 Subject。请求没有 manifest 对照字段，SaaS 校验四表内容和系统约束。manifest 完整性由 XCodeAgent 校验。业务资源引用格式及四表 DDL 应在接入时与 XCodeAgent 实际产物核对。

以下为虚构数据示例，`seed.json` 中的前缀应使用实际 datasourceKey。

```json
{
  "purpose": "AUTHORIZATION_SEED_V1",
  "idempotencyKey": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "tables": [
    {"tableName":"12ab34cd56ef_role","rows":[{"role_id":"SYSTEM_ADMIN","role_name":"管理员","description":"系统角色","enable":1,"is_system":1,"is_deleted":0,"created_by":"SYSTEM"}]},
    {"tableName":"12ab34cd56ef_resource","rows":[{"key":"system_authorization_management","name":"权限管理","description":"系统权限管理","origin":"system","type":"system","target_resource_ref":"system:authorization_management"},{"key":"home","name":"首页","description":"应用首页","origin":"business","type":"page","target_resource_ref":"page:home"}]},
    {"tableName":"12ab34cd56ef_role_resource","rows":[{"role_id":"SYSTEM_ADMIN","resource_key":"system_authorization_management","created_by":"SYSTEM"}]},
    {"tableName":"12ab34cd56ef_role_member","rows":[{"role_id":"SYSTEM_ADMIN","member_id":"fixture-subject","member_name":"Fixture Admin","created_by":"SYSTEM"}]}
  ]
}
```

```bash
base="${MANAGED_DB_BASE_URL}/managed-database/datasources/${DATASOURCE_KEY}/data-operations"
curl --silent --show-error --fail-with-body \
  -H "Authorization: Bearer ${MANAGED_DB_SERVICE_KEY}" \
  -H "Content-Type: application/json" --data-binary @seed.json "${base}/batch"
curl --silent --show-error --fail-with-body \
  -H "Authorization: Bearer ${MANAGED_DB_SERVICE_KEY}" "${base}/${OPERATION_ID}"
curl --silent --show-error --fail-with-body \
  -H "Authorization: Bearer ${MANAGED_DB_SERVICE_KEY}" "${base}/by-key/${IDEMPOTENCY_KEY}"
```

回执示例

```json
{"returnCode":"SUC0000","errorMsg":null,"body":{"operationId":"100256","status":"RUNNING","purpose":"AUTHORIZATION_SEED_V1"}}
```

GET 安全摘要包含 `operationId`、`purpose`、`status`、`datasourceKey`、`requestFingerprint`、`tableNames`、`createdCount`、`existingCount`、`verified`、`errorCode`。行值、Subject、SQL 和连接信息不返回。只有 `SUCCEEDED` 且 `verified=true` 表示事务内回读已通过且业务库提交已确认。`FAILED` 表示确定未提交。`UNKNOWN` 和中断残留的 `RUNNING` 保留占用。计数表示本次插入及回读确认的已有行。

同一个 64 位小写十六进制幂等键与相同指纹返回原操作。同键不同指纹返回 409 `IDEMPOTENCY_CONFLICT`。其他写操作占用时返回 409 `RESOURCE_BUSY`。归属或结构错误分别返回 `TABLE_BINDING_CONFLICT` 或 `STRUCTURE_MISMATCH`。请求超过字节上限返回 413。

## 指纹

服务端计算 `sha256:<小写十六进制>`，幂等键不参与计算。客户端按以下顺序向 SHA-256 输入字节。

1. 写入用途和 datasourceKey。
2. 按四表顺序，为每张表写入物理表名及四字节大端行数。
3. 按请求行顺序，对每行按列名 ASCII 升序写入列名和值。整数先转换成无前导零的十进制字符串。
4. 每次写入字符串时先写四字节大端 UTF-8 字节长度，再写 UTF-8 字节。保留原有效字符，无 Unicode 归一化。

JSON 属性顺序、转义拼写和排版空白不影响指纹。行顺序变化会改变指纹。

## 响应丢失和人工处置

1. 响应丢失时使用原幂等键 GET，或按原 operationId 查询。404 只表示本次未查到记录，调用方应核对原请求及当前系统状态。轮询不执行 DML。
2. 长时间 RUNNING 或 UNKNOWN 时，先暂停相关提交，确认承接受理的 SaaS 实例及业务库连接均已结束，阻止其他写入。在授权环境只读核对原记录、管理库占用、固定挂载上的自然键和不可变约束。避免将载荷、Subject 或数据库连接信息复制到日志、工单或前端。
3. 在运维审计中记录操作 ID、证据、操作者和处置时间。业务记录已经存在不足以证明原事务提交，也无法区分本次创建数与已有数。无法证明原执行结果时，保留 UNKNOWN 并转人工决策。确定仍有在途任务时保留占用。
4. 人工确认无在途写入、决定结束处置后，在一个短管理事务中先锁定 `md_datasource_resource` 原资源行，再锁定 `md_data_operation` 原操作。核对 datasourceId、原 operationId、当前占用和 RUNNING/UNKNOWN 状态。记录有证据支持的状态及安全结果，保留原载荷、幂等键和指纹。仅用 `datasource_key` 与原 `active_operation_id` 双条件释放，条件更新行数不等于一则回滚。若结果仍不明，原记录保留 UNKNOWN，人工释放占用需在受控审计中单独登记。处置流程禁止重放原操作。

## 联调记录

真实 MySQL 联调需要隔离测试环境和 XCodeAgent 的实际四表 DDL 与 manifest。自动化集成用例分为目标库四表事务测试和管理库互斥测试。测试库须可销毁，管理测试库须预先部署完整管理脚本。

- 目标库启用变量为 `MANAGED_DB_SEED_TEST_JDBC_URL`、`MANAGED_DB_SEED_TEST_USER`、`MANAGED_DB_SEED_TEST_PASSWORD`。用例创建及清理随机前缀 fixture。
- 管理库启用变量为 `MANAGED_DB_SEED_TEST_MANAGEMENT_JDBC_URL`、`MANAGED_DB_SEED_TEST_MANAGEMENT_USER`、`MANAGED_DB_SEED_TEST_MANAGEMENT_PASSWORD`。用例插入及清理自身的随机资源。
- 本次交付环境未提供上述测试库和实际 Bootstrap 产物，真实 MySQL 与 XCodeAgent 联调待验证。
