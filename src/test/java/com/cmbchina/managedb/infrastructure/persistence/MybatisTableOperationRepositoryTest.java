package com.cmbchina.managedb.infrastructure.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cmbchina.managedb.common.exception.BizException;
import com.cmbchina.managedb.domain.*;
import com.cmbchina.managedb.infrastructure.persistence.mapper.*;
import com.cmbchina.managedb.infrastructure.persistence.po.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证三表受控事务、条件写及未知占用，测试不创建数据库连接。 */
class MybatisTableOperationRepositoryTest {
    private static final String APP = "b0ddb23acc91";
    private DatasourceRegistrationMapper resources;
    private TableBindingMapper tables;
    private TableOperationMapper operations;
    private PlatformTransactionManager transactions;
    private MybatisTableOperationRepository repository;
    private TableOperationCodec codec;
    private TableOperation op;
    private TableOperationPo stored;
    private DatasourceRegistrationPo application;

    /** 初始化 Plus 字段元数据和真实 TransactionTemplate 所需替身。 */
    @BeforeEach
    void setUp() {
        initialize(DatasourceRegistrationPo.class, DatasourceRegistrationMapper.class);
        initialize(TableBindingPo.class, TableBindingMapper.class);
        initialize(TableOperationPo.class, TableOperationMapper.class);
        resources = mock(DatasourceRegistrationMapper.class); tables = mock(TableBindingMapper.class);
        operations = mock(TableOperationMapper.class); transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        IdentifierGenerator ids = mock(IdentifierGenerator.class); when(ids.nextId(any())).thenReturn(102L);
        codec = new TableOperationCodec(new ObjectMapper());
        repository = new MybatisTableOperationRepository(resources, tables, operations, ids, codec, transactions, 10);
        application = new DatasourceRegistrationPo(); application.setDatasourceKey(APP); application.setDatasourceId(1L);
        application.setStatus("REGISTERED"); application.setDeleted(0);
        when(resources.lock(APP)).thenReturn(application);
        when(tables.insert(any())).thenReturn(1); when(tables.updateById(any())).thenReturn(1);
        when(operations.insert(any())).thenReturn(1); when(operations.update(any(), any())).thenReturn(1);
        when(resources.claim(APP, 101L)).thenReturn(1); when(resources.release(APP, 101L)).thenReturn(1);
        op = TableOperation.start(101L, APP, 1L, "orders", "CREATE TABLE orders(id bigint)", TableOperation.Mode.CREATE, false);
        stored = new TableOperationPo(); stored.setId(101L); stored.setDatasourceKey(APP); stored.setDatasourceId(1L);
        stored.setActionType("CREATE_TABLE"); stored.setStatus("RUNNING"); stored.setCreatedAt(op.getCreatedAt());
        stored.setExecutionJson(codec.content(op)); stored.setExecutionResult(codec.result(op));
    }

    /** 只注册映射信息，不启动 MyBatis 会话。 */
    private static void initialize(Class<?> po, Class<?> mapper) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(mapper.getName()); TableInfoHelper.initTableInfo(assistant, po);
    }

    /** 设置同一应用及目录占用，执行步骤写入前使用固定锁顺序。 */
    private void occupied() {
        application.setActiveOperationId(101L);
        TableBindingPo table = new TableBindingPo(); table.setId(102L); table.setDatasourceKey(APP);
        table.setDatasourceId(1L); table.setTableName("orders"); table.setStatus("REGISTERED"); table.setDeleted(0);
        when(tables.lock(1L, "orders")).thenReturn(table);
        when(operations.lock(APP, 101L)).thenReturn(stored);
    }

    /** 新目录、操作及应用认领只能一次提交。 */
    @Test
    void acceptsThreeRecordsInOneTransaction() {
        repository.begin(op, null);
        org.mockito.InOrder order = inOrder(resources, tables, operations, transactions);
        order.verify(resources).lock(APP); order.verify(tables).lock(1L, "orders");
        order.verify(tables).insert(any()); order.verify(operations).insert(any());
        order.verify(resources).claim(APP, 101L); order.verify(transactions).commit(any());
        verify(transactions, times(1)).getTransaction(any());
    }

    /** 位置唯一键冲突时全事务回滚，不能插入操作或认领应用。 */
    @Test
    void duplicateLocationRollsBackAcceptance() {
        when(tables.insert(any())).thenThrow(new DuplicateKeyException("位置冲突"));
        assertSame(ResourceErrorCode.TABLE_BINDING_CONFLICT, assertThrows(BizException.class,
                () -> repository.begin(op, null)).getErrorCode());
        verify(transactions).rollback(any()); verify(transactions, never()).commit(any());
        verify(operations, never()).insert(any()); verify(resources, never()).claim(anyString(), anyLong());
    }

    /** 认领条件未命中也要回滚先前的目录和操作写入。 */
    @Test
    void failedClaimRollsBackAllWrites() {
        when(resources.claim(APP, 101L)).thenReturn(0);
        assertThrows(BizException.class, () -> repository.begin(op, null));
        verify(transactions).rollback(any()); verify(transactions, never()).commit(any());
    }

    /** SQL 前保存同一步 RUNNING 不能二次成功，防止重复发送。 */
    @Test
    void rejectsDuplicateRunningStepWrite() {
        occupied(); op.markRunning(0); stored.setExecutionResult(codec.result(op));
        assertThrows(BizException.class, () -> repository.saveStep(op, null));
        verify(operations, never()).update(any(), any()); verify(transactions).rollback(any());
    }

    /** 步骤成功与目录 CREATED 必须在同一次事务中保存。 */
    @Test
    void commitsStepAndPhysicalStateTogether() {
        occupied(); op.markRunning(0); stored.setExecutionResult(codec.result(op));
        op.complete(0, TableOperation.StepStatus.SUCCEEDED, null, Collections.singletonMap("sqlExecuted", true));
        repository.saveStep(op, TableBinding.Status.CREATED);
        verify(tables).updateById(argThat(row -> "CREATED".equals(row.getStatus())));
        verify(operations).update(any(), any()); verify(transactions).commit(any());
    }

    /** UNKNOWN 结束只写记录，不释放资源占用。 */
    @Test
    void unknownNeverReleasesApplication() {
        occupied(); op.complete(0, TableOperation.StepStatus.UNKNOWN, "EXECUTION_UNKNOWN", Collections.emptyMap());
        op.finish(TableOperation.Status.UNKNOWN, "EXECUTION_UNKNOWN"); repository.finish(op);
        verify(resources, never()).release(anyString(), anyLong()); verify(transactions).commit(any());
    }

    /** 提交结果不确定只报脱敏系统错误，不被识别为可重放的位置冲突。 */
    @Test
    void uncertainCommitIsNotConflictOrRetry() {
        doThrow(new TransactionSystemException("提交结果未知")).when(transactions).commit(any());
        assertSame(ResourceErrorCode.INTERNAL_ERROR, assertThrows(BizException.class,
                () -> repository.begin(op, null)).getErrorCode());
        verify(operations, times(1)).insert(any());
    }
    /** 放弃先提交时拒绝新步骤准入，且不释放占用。 */
    @Test
    void abandonRequestBlocksStaleWorkerAdmission() {
        occupied();
        TableOperation request = codec.restore(stored); request.requestAbandon("停止此次建表");
        stored.setExecutionResult(codec.result(request));
        op.markRunning(0);
        assertSame(ResourceErrorCode.OPERATION_ABANDONED, assertThrows(BizException.class,
                () -> repository.saveStep(op, null)).getErrorCode());
        verify(operations, never()).update(any(), any());
        verify(resources, never()).release(anyString(), anyLong());
    }

    /** 已准入的步骤仍可保存结果，旧线程快照不得覆盖后来提交的放弃审计。 */
    @Test
    void admittedStepKeepsConcurrentAbandonRequest() {
        occupied(); op.markRunning(0); stored.setExecutionResult(codec.result(op));
        TableOperation request = codec.restore(stored); request.requestAbandon("停止后续步骤");
        stored.setExecutionResult(codec.result(request));
        op.complete(0, TableOperation.StepStatus.SUCCEEDED, null, Collections.emptyMap());
        repository.saveStep(op, TableBinding.Status.CREATED);
        verify(operations).update(argThat(row -> row.getExecutionResult().contains("停止后续步骤")), any());
        assertNotNull(op.getAbandonRequest());
    }

    /** UNKNOWN 申请只追加审计，不伪造确定结果或释放占用；重复申请保留首次原因。 */
    @Test
    void unknownAbandonRequestIsDurableAndIdempotent() {
        occupied(); op.complete(0, TableOperation.StepStatus.UNKNOWN, "EXECUTION_UNKNOWN", Collections.emptyMap());
        op.finish(TableOperation.Status.UNKNOWN, "EXECUTION_UNKNOWN"); stored.setStatus("UNKNOWN");
        stored.setExecutionResult(codec.result(op));
        TableOperation requested = repository.requestAbandon(APP, 101L, "接受已发生的变更");
        assertEquals("MANUAL_REQUIRED", requested.recoveryState());
        assertNull(requested.getFinishedAt());
        stored.setExecutionResult(codec.result(requested));
        TableOperation repeated = repository.requestAbandon(APP, 101L, "第二次原因");
        assertEquals("接受已发生的变更", repeated.getAbandonRequest().getReason());
        verify(operations, times(1)).update(any(), any());
        verify(resources, never()).release(anyString(), anyLong());
    }

    /** 原操作已结束时重复申请不影响当前新操作占用。 */
    @Test
    void terminalRequestNeverReleasesNewOperation() {
        occupied(); application.setActiveOperationId(999L); stored.setStatus("FAILED");
        assertEquals(TableOperation.Status.FAILED, repository.requestAbandon(APP, 101L, "重复请求").getStatus());
        verify(operations, never()).update(any(), any());
        verify(resources, never()).release(anyString(), anyLong());
    }

    /** 人工结束后的审计、未知步骤及原时间必须可被详情重新读取。 */
    @Test
    void restoresManualResolutionWithoutRewritingExecutionEvidence() throws Exception {
        op.complete(0, TableOperation.StepStatus.UNKNOWN, "EXECUTION_UNKNOWN", Collections.emptyMap());
        op.finish(TableOperation.Status.UNKNOWN, "EXECUTION_UNKNOWN");
        com.fasterxml.jackson.databind.node.ObjectNode result = (com.fasterxml.jackson.databind.node.ObjectNode)
                new ObjectMapper().readTree(codec.result(op));
        result.put("errorCode", "MANUALLY_ABANDONED");
        result.putObject("manualResolution").put("resolvedBy", "operator").put("previousStatus", "UNKNOWN");
        stored.setStatus("FAILED"); stored.setExecutionResult(result.toString());
        TableOperation restored = codec.restore(stored);
        assertEquals("MANUALLY_ABANDONED", restored.recoveryState());
        assertEquals(TableOperation.StepStatus.UNKNOWN, restored.getSteps().get(0).getStatus());
        assertNull(restored.getFinishedAt());
        assertTrue(codec.result(restored).contains("manualResolution"));
    }

    /** 四目录与一个操作在同一事务认领，目录按固定名称顺序加锁。 */
    @Test
    void batchClaimsFourTablesInOneTransaction(){
        TableOperation batch=batchOperation();repository.beginBatch(batch,Arrays.asList(null,null,null,null),0);
        verify(tables,times(4)).insert(any());verify(operations,times(1)).insert(argThat(row->"CREATE_TABLE_BATCH".equals(row.getActionType())));verify(resources,times(1)).claim(APP,101L);verify(transactions,times(1)).commit(any());
        org.mockito.InOrder order=inOrder(tables);for(String name:new TreeSet<>(batch.getTableNames()))order.verify(tables).lock(1L,name);
    }
    /** 预检后发生的操作和位置竞争必须回滚整批受理。 */
    @Test
    void batchRejectsConcurrentChangeAndRollsBack(){
        when(operations.selectCount(any())).thenReturn(1L);
        assertThrows(BizException.class,()->repository.beginBatch(batchOperation(),Arrays.asList(null,null,null,null),0));
        verify(transactions).rollback(any());verify(tables,never()).insert(any());verify(resources,never()).claim(anyString(),anyLong());
    }
    /** 第三目录插入冲突时不得提交前两目录或受理记录。 */
    @Test
    void batchLocationConflictRollsBackAllRegistrations(){
        when(tables.insert(any())).thenReturn(1,1).thenThrow(new DuplicateKeyException("占用"));
        assertThrows(BizException.class,()->repository.beginBatch(batchOperation(),Arrays.asList(null,null,null,null),0));
        verify(transactions).rollback(any());verify(transactions,never()).commit(any());verify(operations,never()).insert(any());
    }
    /** 批次步骤只更新对应目录，不把单表投影的空表名作为更新目标。 */
    @Test
    void batchStepUpdatesExactTable(){
        TableOperation batch=batchOperation();application.setActiveOperationId(101L);
        for(int i=0;i<batch.getTableNames().size();i++){
            TableBindingPo row=new TableBindingPo();row.setId(201L+i);row.setDatasourceKey(APP);row.setDatasourceId(1L);row.setTableName(batch.getTableNames().get(i));row.setStatus("REGISTERED");row.setDeleted(0);when(tables.lock(1L,row.getTableName())).thenReturn(row);
        }
        batch.markRunning(0);batch.complete(0,TableOperation.StepStatus.SUCCEEDED,null,Collections.emptyMap());batch.markRunning(1);
        stored.setActionType("CREATE_TABLE_BATCH");stored.setExecutionJson(codec.content(batch));stored.setExecutionResult(codec.result(batch));when(operations.lock(APP,101L)).thenReturn(stored);
        batch.complete(1,TableOperation.StepStatus.SUCCEEDED,null,Collections.singletonMap("tableExists",true));repository.saveStep(batch,TableBinding.Status.CREATED);
        verify(tables).updateById(argThat(row->Long.valueOf(202L).equals(row.getId())&&"CREATED".equals(row.getStatus())));verify(transactions).commit(any());
    }
    /** JSON 转义后超过存储上限时，在任何受理事务和目录写入前拒绝。 */
    @Test
    void batchChecksEncodedContentSizeBeforeTransaction(){
        String huge=String.join("",Collections.nCopies(140000,"\\"));
        TableOperation batch=TableOperation.startBatch(101L,APP,1L,Collections.singletonList("orders"),Collections.singletonList(huge),Collections.singletonList(false));
        assertSame(ResourceErrorCode.INVALID_ARGUMENT,assertThrows(BizException.class,()->repository.beginBatch(batch,Collections.singletonList(null),0)).getErrorCode());
        verifyNoInteractions(transactions);verify(tables,never()).insert(any());
    }
    /** 固定输入顺序与加锁顺序不同，便于发现步骤映射错误。 */
    private TableOperation batchOperation(){
        List<String> names=Arrays.asList("z_role","a_resource","m_binding","b_user");List<String> sqls=new ArrayList<>();for(String name:names)sqls.add("CREATE TABLE "+name+"(id bigint PRIMARY KEY)");
        return TableOperation.startBatch(101L,APP,1L,names,sqls,Arrays.asList(false,false,false,false));
    }

}
