package com.cmbchina.managedb.infrastructure.persistence;

import com.cmbchina.managedb.domain.*;
import com.cmbchina.managedb.infrastructure.persistence.po.TableOperationPo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证新增批次与旧四种操作的独立序列化及恢复合同。 */
class TableOperationCodecTest {
    private final TableOperationCodec codec=new TableOperationCodec(new ObjectMapper());
    /** 恢复有序 SQL、跳过标记和部分失败证据，不重新生成步骤。 */
    @Test
    void restoresBatchWithOriginalOrderAndEvidence(){
        List<String> sqls=Arrays.asList("CREATE TABLE z(id bigint PRIMARY KEY)","CREATE TABLE a(id bigint PRIMARY KEY)");
        TableOperation op=TableOperation.startBatch(1L,"12ab34cd56ef",1L,Arrays.asList("z","a"),sqls,Arrays.asList(true,false));
        op.markRunning(0);op.complete(0,TableOperation.StepStatus.SUCCEEDED,null,Collections.singletonMap("sqlExecuted",false));
        op.complete(1,TableOperation.StepStatus.UNKNOWN,"EXECUTION_UNKNOWN",Collections.singletonMap("sqlExecuted",null));op.finish(TableOperation.Status.UNKNOWN,"EXECUTION_UNKNOWN");
        TableOperation restored=roundTrip(op);
        assertEquals(sqls,restored.getBatchSqls());assertEquals(op.getTableNames(),restored.getTableNames());assertEquals(Arrays.asList(true,false),restored.getBatchSatisfied());
        assertEquals(TableOperation.Status.UNKNOWN,restored.getStatus());assertNull(restored.getFinishedAt());assertEquals(op.getStartedAt(),restored.getStartedAt());assertEquals(TableOperation.StepStatus.SUCCEEDED,restored.getSteps().get(0).getStatus());
        assertTrue(codec.content(op).contains("\"allowDataLoss\":false"));
    }
    /** 历史动作仍使用原 SQL、清单、步骤数及数据丢失语义。 */
    @Test
    void preservesLegacyActions(){
        for(TableOperation.Mode mode:Arrays.asList(TableOperation.Mode.CREATE,TableOperation.Mode.REBUILD,TableOperation.Mode.DROP,TableOperation.Mode.RELEASE)){
            TableOperation op=(mode==TableOperation.Mode.CREATE||mode==TableOperation.Mode.REBUILD)?TableOperation.start(1L,"12ab34cd56ef",1L,"orders","CREATE TABLE orders(id bigint PRIMARY KEY)",mode,true):TableOperation.cleanup(1L,"12ab34cd56ef",1L,mode,Collections.singletonList("orders"));
            TableOperation restored=roundTrip(op);assertEquals(mode,restored.getMode());assertEquals(op.getCreateSql(),restored.getCreateSql());assertEquals(op.getTableNames(),restored.getTableNames());assertEquals(op.getSteps().size(),restored.getSteps().size());assertEquals(op.isExistingSatisfied(),restored.isExistingSatisfied());
        }
    }
    /** 实际经过持久化 JSON 与动作映射，不共享聚合实例。 */
    private TableOperation roundTrip(TableOperation op){
        TableOperationPo row=new TableOperationPo();row.setId(op.getId());row.setDatasourceKey(op.getDatasourceKey());row.setDatasourceId(op.getDatasourceId());row.setActionType(TableOperationCodec.actionType(op.getMode()));row.setStatus(op.getStatus().name());row.setCreatedAt(op.getCreatedAt());row.setExecutionJson(codec.content(op));row.setExecutionResult(codec.result(op));return codec.restore(row);
    }
}
