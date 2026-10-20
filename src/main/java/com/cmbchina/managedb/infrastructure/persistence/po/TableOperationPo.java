package com.cmbchina.managedb.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

/** 管理表持久化行；保留记录由查询显式处理，不启用 TableLogic。 */
@Getter
@Setter
@TableName("md_database_operation")
public class TableOperationPo {
    @TableId(type=IdType.INPUT)
    private Long id;
    private String datasourceKey;
    private Long datasourceId;
    private String actionType;
    private String executionJson;
    private String status;
    private String executionResult;
    private java.time.Instant createdAt;
    @TableField("is_deleted")
    private Integer deleted;
}
