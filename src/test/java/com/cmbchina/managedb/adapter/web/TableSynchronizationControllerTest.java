package com.cmbchina.managedb.adapter.web;

import com.cmbchina.managedb.adapter.web.application.TableSynchronizationController;
import com.cmbchina.managedb.adapter.web.security.ServiceKeyFilter;
import com.cmbchina.managedb.application.*;
import com.cmbchina.managedb.application.view.*;
import com.cmbchina.managedb.common.exception.BaseExceptionHandler;
import com.cmbchina.managedb.common.exception.BizException;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Collections;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 固定普通 JSON、202 与启动检查合同，不启动服务器。 */
class TableSynchronizationControllerTest {
    private static final String BASE = "/managed-database/datasources/b0ddb23acc91";
    private TableOperationService operations;
    private TableReadinessService readiness;
    private MockMvc mvc;

    /** 装配真实鉴权和异常过滤，密钥仅在测试中使用。 */
    @BeforeEach
    void setUp() {
        operations = mock(TableOperationService.class); readiness = mock(TableReadinessService.class);
        ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc = MockMvcBuilders.standaloneSetup(new TableSynchronizationController(operations, readiness))
                .setControllerAdvice(new BaseExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new ServiceKeyFilter("test-only-key", mapper)).build();
    }

    /** 写接口返回受理编号而不是流式会话。 */
    @Test
    void acceptsPlainJsonAndReturns202() throws Exception {
        when(operations.submit("b0ddb23acc91", "CREATE", "CREATE TABLE orders(id bigint)", false))
                .thenReturn(new TableOperationReceipt(101L));
        mvc.perform(request("/table-operations", "{\"mode\":\"CREATE\",\"sql\":\"CREATE TABLE orders(id bigint)\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.operationId").value("101"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    /** 未同步仍是正常检查结果，HTTP 200。 */
    @Test
    void returnsReadyFalseAsCheckResult() throws Exception {
        when(readiness.check("b0ddb23acc91", Collections.emptyList())).thenReturn(new TableReadinessView(
                false, 1L, "OPERATION_IN_PROGRESS", "101", "RUNNING", Collections.emptyList(), Collections.emptyList()));
        mvc.perform(request("/tables/check", "{\"sqls\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(false));
    }

    /** 不接受额外挂载参数，不在鉴权前解析 SQL，也不回显内部异常。 */
    @Test
    void rejectsUnknownFieldsCredentialsAndBusyApplications() throws Exception {
        mvc.perform(request("/table-operations", "{\"mode\":\"CREATE\",\"datasourceId\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/tables/check").servletPath(BASE + "/tables/check")
                .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(operations, readiness);
        when(operations.submit("b0ddb23acc91", "CREATE", "sql", false)).thenThrow(new BizException(ResourceErrorCode.RESOURCE_BUSY));
        mvc.perform(request("/table-operations", "{\"mode\":\"CREATE\",\"sql\":\"sql\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_BUSY"));
    }

    /** 执行容量不足使用独立错误码和 503，与应用冲突的 409 区分。 */
    @Test
    void executionCapacityHasDistinctCodeAndStatus() throws Exception {
        when(operations.submit("b0ddb23acc91", "CREATE", "sql", false))
                .thenThrow(new BizException(ResourceErrorCode.EXECUTION_CAPACITY_EXHAUSTED));
        mvc.perform(request("/table-operations", "{\"mode\":\"CREATE\",\"sql\":\"sql\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EXECUTION_CAPACITY_EXHAUSTED"));
    }

    /** 缺少显式主键返回专用 400 错误，便于客户端定位建表定义问题。 */
    @Test
    void missingPrimaryKeyReturnsBadRequest() throws Exception {
        when(operations.submit("b0ddb23acc91", "CREATE", "sql", false))
                .thenThrow(new BizException(ResourceErrorCode.PRIMARY_KEY_REQUIRED));
        mvc.perform(request("/table-operations", "{\"mode\":\"CREATE\",\"sql\":\"sql\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRIMARY_KEY_REQUIRED"));
    }

    /** 路径类型及参数验证按标准 400 处理。 */
    @Test
    void rejectsInvalidOperationIdBeforeUseCase() throws Exception {
        mvc.perform(get(BASE + "/table-operations/not-a-number").servletPath(BASE + "/table-operations/not-a-number")
                .header("Authorization", "Bearer test-only-key"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(operations);
    }

    /** 设置实际 Servlet 路径并复用 JSON 和测试密钥。 */
    private static MockHttpServletRequestBuilder request(String suffix, String body) {
        return post(BASE + suffix).servletPath(BASE + suffix).header("Authorization", "Bearer test-only-key")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
