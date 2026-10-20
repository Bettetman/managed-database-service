# 内置数据库 SaaS 服务设计

更新日期：2026-10-19。

## 服务定位与分工

本服务为 DevAgent Studio 的本地调试提供共享 MySQL 资源。应用代码仍在本地启动；SaaS 负责资源注册、固定挂载、实体表运维和调试数据查看，不部署用户生成的应用。

Studio 保存 application.json、实体设计和当前建表 SQL，负责用户确认、页面展示及启动编排。SaaS 保存资源归属、表目录与操作记录，通过固定挂载读取真实数据库。实体 ID、期望结构和模型版本不再复制一套到 SaaS。

domain 维护领域规则和端口，application 编排用例，infrastructure 实现 MyBatis/JDBC，adapter 处理协议，bootstrap 装配依赖。领域不依赖 Spring、MyBatis、Web 或数据库连接对象；Controller 不直接操作 Mapper。

## 资源标识与管理表

| 标识 | 含义 |
| --- | --- |
| datasourceKey | SaaS 生成的逻辑资源标识，也是业务表前缀，固定为 12 位小写十六进制字符 |
| datasourceId | 预配置 MySQL/Schema 的物理挂载编号，一次分配后固定 |
| appKey / appName | Studio 应用关联信息，不代替资源标识，也不作为注册幂等键 |

当前 Studio 注册只传 appName；SaaS 接受可选 appKey。应用改名、重启和同一应用的新分支复用已有资源，不再次注册。名称不唯一，不能靠 appName 查重或推测资源身份。

datasourceKey 通过时间戳与随机串计算 SHA-256 后截取生成，由数据库唯一键兜底。明确编码冲突最多重试 3 次；数据库失败或提交结果未知不进入此重试。上游重复 POST 仍可能创建多份资源。

挂载由 devagent-studio.list 提供，Schema 从 db-name 读取，不在管理表重复保存。新资源按 CREATED 表数、有效资源数、datasourceId 数值依次选择可用挂载。扩容只影响后续新资源，已有资源不重新分配。

只维护三张管理表，唯一 DDL 来源为 [建表脚本](../src/main/resources/db/manual/managed-database-schema.sql)：

| 表 | 职责与状态 |
| --- | --- |
| md_datasource_resource | datasourceKey、app_key/app_name、固定挂载和当前写操作占用；REGISTERED / RELEASED |
| md_datasource_table | 精确表归属及最近确认的物理状态；REGISTERED / CREATED / DROPPED |
| md_database_operation | 一次 CREATE、REBUILD、DROP 或 RELEASE 的执行内容、逐步证据和终态；RUNNING / SUCCEEDED / FAILED / UNKNOWN |

三表通过 datasource_key 关联，表位置按 datasource_id + table_name 唯一。目录外的表不自动认领，保留记录不释放唯一占用。CREATED 只表示最近确认建成，不能代替当前结构检查。

datasource_id 使用 TINYINT UNSIGNED，部署使用 1～255 的编号，Java 使用 long/Long，接口使用数字类型。主键、操作 ID 和 active_operation_id 保持 BIGINT。管理表使用 InnoDB、utf8mb4、utf8mb4_bin。

## Studio 主流程

```mermaid
flowchart LR
  A[新建应用] --> B[注册资源并保存绑定]
  B --> C[设计实体与生成 SQL]
  C --> D[确认建表或重建]
  D --> E[读取表和字段设计 API]
  E --> F[启动前检查全部实体]
  F --> G[获取连接并注入环境变量]
  G --> H[本地应用调试]
  H --> I[查看表数据及操作历史]
```

注册成功后，Studio 将 datasourceKey 和 datasourceId 保存到唯一 application.json 的 managedDatabase 中。注册只建立资源绑定，不创建用户表或数据库账号。

实体真实表名由 Studio 确定为 `datasourceKey_业务表名`。建表与业务 SQL 都使用这个完整名称，总长不超过 64；可以数字开头，SQL 中应使用反引号。超长直接拒绝，不截断。平台生成的业务表使用 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='中文表说明'`。SaaS 解析并执行符合入口规则的原 SQL，不额外添加审计字段。

建表成功后，Studio 从 SaaS 读取当前资源已建成的表清单和真实字段，供数据源展示及 API 字段映射使用。结果不是单纯从实体设计推导，也不以表名前缀扫描替代精确目录归属。

启动时，Studio 提交全部当前实体 CREATE SQL，SaaS 检查真实结构。通过后再获取固定挂载的专用连接，由 Studio 注入本地 Java 启动环境，效果与用户提供数据库环境变量配置一致。业务 SQL 经 JDBC 直接访问目标数据库，不由 SaaS 转发。

## 接口清单

以下资源路径前缀统一为 `/managed-database/datasources/{datasourceKey}`。注册、建表、结构检查、元数据和连接接口使用 JSON，数据查看、清理和操作恢复入口使用 AG-UI；Studio 产品交互通过自身后端 AG-UI，不由 Renderer 直接调用 SaaS 或持有服务密钥。

| 方法与路径 | 用途 |
| --- | --- |
| GET /health | 进程健康，不检查业务表结构 |
| POST /managed-database/datasources | 注册资源，成功返回 HTTP 201 |
| POST {前缀}/table-operations | CREATE 或 REBUILD，受理返回 HTTP 202 和 operationId |
| GET {前缀}/table-operations/{operationId} | 查询原操作详情 |
| GET {前缀}/table-operations | 查询最近操作历史 |
| POST {前缀}/tables/check | 提交当前完整 SQL 清单，返回本次结构检查结果 |
| GET {前缀}/tables | 查询当前资源已建成的真实表 |
| GET {前缀}/tables/{tableName}/columns | 查询真实字段与表注释 |
| POST {前缀}/tables/{tableName}/data/run | AG-UI 分页查询表数据 |
| GET {前缀}/runtime-connection | 后端专用取密接口，返回固定挂载连接 |
| POST {前缀}/cleanup/run | AG-UI 单表删除或整份资源回收 |
| POST {前缀}/table-operations/{operationId}/recovery | AG-UI 申请停止后续执行及查询处置状态 |

注册请求示例：

```json
{"appName":"员工请假系统"}
```

appName 必填，1～255 个字符；appKey 可选，提供时为 1～64 个字符。拒绝错误类型、未知字段及首尾空白。成功响应：

```json
{
  "datasourceKey": "12ab34cd56ef",
  "datasourceId": 2,
  "status": "REGISTERED",
  "isDeleted": false
}
```

AG-UI 入口采用 RunAgentInput，业务参数放 forwardedProps，返回完整生命周期、结构化结果或错误，以及状态快照。HTTP 200 或 RUN_FINISHED 不等于业务执行成功。

## 建表、重建与结构检查

CREATE 只创建缺失表。已有归属表且结构一致时可以返回目标已满足；结构不同则提示重建，不自动 ALTER 或 DROP。目录外同名表或其他资源占用的表明确拒绝。

REBUILD 必须明确授权数据丢失。Studio 先停止相关调试，再确认提交；SaaS 校验新 SQL 后删表、回读、保存 DROPPED，再建表、回读、保存 CREATED。两步不能整体回滚。删表成功而建表失败时保留实际结果，修正后可以显式 CREATE；有外部引用时不级联处理或关闭外键检查。

执行前用短管理事务锁定资源、核对目录、保存 RUNNING 操作并认领 active_operation_id。DDL 在事务外执行，每步保存准入及回读证据；确定结束才释放占用。同一资源的建表、重建和清理互斥，不使用长事务包住 DDL。

结构检查比较当前 SQL 的列、类型、可空、默认值、自增、主键、索引、外键和显式表选项，不按文本相等判断。ready 是本次只读结果，不落为永久同步状态。缺表、结构不一致、归属冲突、占用或读取失败都会阻止启动；历史操作成功不能替代此检查。

结果未知时保留 UNKNOWN 与占用，不自动重放 SQL。申请 recovery 只能阻止尚未准入的后续步骤，不能证明在途 SQL 已结束，也不会恢复数据。人工处理应先停止相关实例，核对 SQL 已结束和全部实际表状态，再在同一管理事务内保存审计、修正目录与原操作，并仅释放原操作的占用。完整流程见 [未完成操作人工处置](operation-recovery-runbook.md)，管理库受控工具见 [人工结束操作 SQL](../src/main/resources/db/manual/table-operation-abandon.sql)。工具覆盖四种动作及空资源 RELEASE，默认预演回滚，正式提交必须显式确认；原 SQL、步骤和执行时间保留，人工审计使用独立 resolvedAt。人工放弃 RELEASE 只结束原操作、释放原占用，资源仍为 REGISTERED；后续正常清理才完成资源回收。重复处置不改原审计、不释放后来操作的占用。

## 元数据、调试数据和连接

表与字段接口先检查资源有效、目录归属和 CREATED 状态，再回读真实数据库。查询前后核对受管操作是否变化；有占用或变化时拒绝本次结果，不能把读取失败当成空表。

数据预览只提供当前资源单表的分页读取，不接受任意 SQL。条件结构已预留 query.filter.conditions，当前只接受空条件。后续可扩展字段筛选，不改为客户端自由拼接 SQL。读取使用固定挂载、只读连接、主键排序和参数化 LIMIT/OFFSET，并限制页大小、字段数及结果体大小，不执行全表 COUNT。

runtime-connection 返回 datasourceKey、datasourceId、host、port、database、username、password、sslMode，并设置 Cache-Control: no-store。仅有效且无占用的资源可获取连接；不在普通结构检查响应中公开凭据。

连接位置复用 devagent-studio.list，调试账号来自 managed-database.runtime.list。账号必须独立于建表账号，缺配置明确失败，不回退管理密码。远程服务使用 HTTPS，数据库 TLS 按部署配置；密码不落应用文件或日志。

## 资源清理与边界

cleanup/run 支持 PREVIEW、CONFIRM、STATUS。scope=TABLE 删除单表；scope=DATASOURCE 回收全部已登记归属表，全部成功后将资源标为 RELEASED。确认必须携带预览指纹、confirmed=true、allowDataLoss=true，期间目录发生变化需要重新预览。

清理只使用精确目录，不按前缀批量扫表，不删除 Schema 或共享账号。按真实外键关系先删子表，逐表保存回读结果；部分完成保留证据，UNKNOWN 保留占用。已释放资源不能继续建表、读取活动元数据或取连接，但可查询原操作。当前未接入 Studio 应用删除流程。

表前缀解决共享 Schema 内的命名冲突；共用调试账号不提供数据库权限级应用隔离。服务没有跨机器运行租约，启动检查不阻止其他机器未来修改结构。破坏性操作前由调用方安排停止调试。
