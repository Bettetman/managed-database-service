package com.cmbchina.managedb.application;

import com.cmbchina.managedb.common.exception.BizException;
import com.cmbchina.managedb.domain.*;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用例不接受未确认请求，也不把用户确认直接变成释放动作。 */
class TableOperationRecoveryServiceTest {
    /** 无确认、空原因或无效编号时禁止进入持久化事务。 */
    @Test
    void rejectsUnconfirmedAndInvalidRequests(){
        TableOperationRepository repository=mock(TableOperationRepository.class);
        TableOperationRecoveryService service=new TableOperationRecoveryService(repository);
        assertThrows(BizException.class,()->service.requestAbandon("b0ddb23acc91",101L,"停止",false));
        assertThrows(BizException.class,()->service.requestAbandon("b0ddb23acc91",101L," ",true));
        assertThrows(BizException.class,()->service.requestAbandon("b0ddb23acc91",0L,"停止",true));
        verifyNoInteractions(repository);
    }

    /** UNKNOWN 申请保持未知事实，用例仅调用请求仓储方法。 */
    @Test
    void confirmedUnknownOnlyRecordsIntent(){
        String resource="b0ddb23acc91";
        TableOperationRepository repository=mock(TableOperationRepository.class);
        TableOperation op=TableOperation.start(101L,resource,1L,"orders","CREATE TABLE orders(id bigint)",TableOperation.Mode.CREATE,false);
        op.finish(TableOperation.Status.UNKNOWN,"EXECUTION_UNKNOWN");op.requestAbandon("停止");
        when(repository.requestAbandon(resource,101L,"停止")).thenReturn(op);
        assertEquals("MANUAL_REQUIRED",new TableOperationRecoveryService(repository)
                .requestAbandon(resource,101L,"停止",true).getExecutionResult().getRecoveryState());
        verify(repository).requestAbandon(resource,101L,"停止");verifyNoMoreInteractions(repository);
    }
}
