# DDD 工程协作约定

本工程是独立的应用数据库资源服务，采用单模块 Maven 工程，按 Java 包组织 DDD 分层，不属于无状态 Template Engine。

- 修改前读 `docs/managed-database-design.md`，只查看当前任务相关文件；不得自动扫描全项目。
- 沿用 Java 8、Spring Boot 2.7.2、MyBatis-Plus 3.5.2 和 Lombok 基线。
- domain 包的代码只依赖 JDK 和 common 中的纯 Java 异常契约，维护聚合、实体、值对象和仓储接口。禁止引入 Spring、MyBatis、Web、PO 或数据库连接对象。
- application 包的业务依赖只指向 domain 和 common 中的纯 Java 异常契约，处理用例和只读结果，不直接依赖 Mapper、PO、HTTP 或 infrastructure 实现。
- infrastructure 实现领域仓储和外部系统适配；Mapper、PO、XML 和聚合转换属于这里，不给领域对象增加持久化注解。
- adapter 处理协议、参数、已认证身份和结果映射，通过 application 用例访问业务；不得直接调用 Mapper 或 Repository。
- `Application` 是唯一启动入口，`bootstrap` 包是组合根，负责配置和依赖装配；运行配置放 `src/main/resources`。根 POM 统一管理依赖，整个工程生成一个可执行 Spring Boot JAR，不再拆 Maven 子模块。
- 当前只维护一个业务域，domain、application 下不再嵌套 resource 包；新增代码按已有职责目录放置，不为单一业务再加一层目录。
- 通用异常、错误码契约、HTTP 响应、分页和全局异常处理统一放根包 common 下；业务专用错误码仍归 domain。domain、application 不得引用 common 中的 Web 处理器、响应或分页类型。common 不依赖 domain、application 或 infrastructure。
- 聚合内部的状态和集合通过业务方法维护，不添加能绕过规则的 setter。固定前缀、表绑定唯一性和释放状态规则归 domain。
- 领域状态不是数据库执行证据。真实建表、授权和回收必须由后续应用用例确认、执行、回读，再更新领域与持久化状态。
- 所有手写方法、构造方法和复杂逻辑必须有中文注释。
- 当前仅开放 `/health`。资源模型、仓储契约和查询用例没有对外业务接口，真实仓储尚未实现；不得用空实现或假数据返回成功。
- 所有新增产品接口必须使用 AG-UI，确认、进度、结果、错误与状态具有完整生命周期。基础 HTTP 的 ResponseEntity 和异常处理不能代替 AG-UI。
- 只读当前合同，不新增历史迁移、兼容别名或双写，除非用户明确要求。
- 配置通过环境变量提供，不提交或记录真实密码、Token、密钥、完整请求体和数据库连接信息。
- 默认 local 模式不连接数据库；mysql 模式显式配置，禁止默认 root 账号、默认密码或自动建库建表。
- 默认仅监听本机。共享部署前必须完成认证、应用归属校验和数据库访问隔离。
- 验证命令最多执行一次，失败立即停止并返回错误，不自动循环修复或重跑。
- 文档修改不构建；代码修改按范围执行一次必要构建，不额外启动服务、自测或扩大检查范围。
