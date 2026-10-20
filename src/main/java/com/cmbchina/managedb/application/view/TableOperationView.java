package com.cmbchina.managedb.application.view;

import com.cmbchina.managedb.domain.TableOperation;
import com.cmbchina.managedb.domain.OperationAbandonRequest;
import lombok.Getter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

/** 操作结果投影不暴露可修改聚合或数据库内部主键类型。 */
@Getter
public final class TableOperationView {
    private final String operationId,mode,tableName,status;
    private final Instant createdAt;
    private final List<String> tableNames;
    private final Result executionResult;
    /** 从持久化恢复的领域记录创建详情。 */
    public TableOperationView(TableOperation op){tableNames=op.getTableNames();operationId=Long.toString(op.getId());mode=op.getMode().name();tableName=op.getTableName();status=op.getStatus().name();createdAt=op.getCreatedAt();executionResult=new Result(op);}
    /** 展示既有执行证据，步骤没有公开 setter。 */
    @Getter
    public static final class Result {
        private final Instant startedAt,finishedAt;
        private final String errorCode,errorSummary;
        private final List<TableOperation.Step> steps;
        private final OperationAbandonRequest abandonRequest;
        private final Map<String,Object> manualResolution;
        private final String recoveryState;
        /** 固定本次查询的历史结果。 */
        private Result(TableOperation op){
            startedAt=op.getStartedAt();finishedAt=op.getFinishedAt();errorCode=op.getErrorCode();errorSummary=op.getErrorSummary();steps=op.getSteps();abandonRequest=op.getAbandonRequest();
            // 数据库操作账号仅留管理审计，产品详情不返回内部账号。
            Map<String,Object> visible=new LinkedHashMap<>(op.getManualResolution());visible.remove("databaseActor");
            manualResolution=Collections.unmodifiableMap(visible);recoveryState=op.recoveryState();
        }
    }
}
