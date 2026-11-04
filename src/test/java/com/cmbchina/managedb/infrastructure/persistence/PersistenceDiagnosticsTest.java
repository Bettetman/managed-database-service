package com.cmbchina.managedb.infrastructure.persistence;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cmbchina.managedb.common.exception.BizException;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.infrastructure.persistence.po.TableOperationPo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 诊断必须定位异常阶段，同时防止驱动消息和 JSON 内容进入日志。 */
class PersistenceDiagnosticsTest {
    private Logger logger;
    private ListAppender<ILoggingEvent> events;
    /** 捕获实际格式化的日志，而非只检查方法入参。 */
    @BeforeEach
    void capture(){
        logger=(Logger)LoggerFactory.getLogger(PersistenceDiagnostics.class);
        events=new ListAppender<>();events.start();logger.addAppender(events);
    }
    /** 移除当前测试的监听器，避免影响其他测试。 */
    @AfterEach
    void detach(){logger.detachAppender(events);events.stop();}
    /** 管理事务失败保留阶段与驱动代码，对外仍为原统一错误。 */
    @Test
    void repositoryReportsWrappedSqlFailureWithoutSensitiveMessage(){
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenThrow(new IllegalStateException("secret-jdbc-url",new SQLException("secret-password SELECT * FROM private_table","42S22",1054)));
        MybatisTableOperationRepository repository=new MybatisTableOperationRepository(null,null,null,null,null,manager,10);
        assertSame(ResourceErrorCode.INTERNAL_ERROR,assertThrows(BizException.class,()->repository.checkContext("12ab34cd56ef")).getErrorCode());
        String message=onlyMessage();assertTrue(message.contains("stage=repository.checkContext"));assertTrue(message.contains("rootType=java.sql.SQLException"));assertTrue(message.contains("sqlState=42S22"));assertTrue(message.contains("vendorCode=1054"));assertFalse(message.contains("secret"));assertFalse(message.contains("SELECT"));
    }
    /** 错误 JSON 只记录操作编号和解码阶段，不记录正文或解析异常文本。 */
    @Test
    void codecReportsOperationAndDecodeStage(){
        TableOperationPo row=new TableOperationPo();row.setId(101L);row.setExecutionJson("{secret-token-invalid-json");
        assertSame(ResourceErrorCode.INTERNAL_ERROR,assertThrows(BizException.class,()->new TableOperationCodec(new ObjectMapper()).restore(row)).getErrorCode());
        String message=onlyMessage();assertTrue(message.contains("stage=codec.readContent"));assertTrue(message.contains("operationId=101"));assertFalse(message.contains("secret-token"));
    }
    /** 即便驱动提供不合法 SQLState，也不能将文本注入日志。 */
    @Test
    void rejectsNonstandardSqlState(){
        PersistenceDiagnostics.failure("test.sqlState",null,new SQLException("secret","secret\npassword",1));
        String message=onlyMessage();assertTrue(message.contains("sqlState=INVALID_SQL_STATE"));assertFalse(message.contains("secret"));assertFalse(message.contains("\n"));
    }
    /** 只允许一条无 Throwable 附件的诊断，防止日志框架打印敏感堆栈。 */
    private String onlyMessage(){
        assertEquals(1,events.list.size());assertNull(events.list.get(0).getThrowableProxy());return events.list.get(0).getFormattedMessage();
    }
}
