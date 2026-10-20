package com.cmbchina.managedb.adapter.web;

import com.cmbchina.managedb.adapter.web.application.DatasourceRegistrationController;
import com.cmbchina.managedb.adapter.web.security.ServiceKeyFilter;
import com.cmbchina.managedb.application.DatasourceRegistration;
import com.cmbchina.managedb.application.view.DatasourceResourceView;
import com.cmbchina.managedb.common.exception.BaseExceptionHandler;
import com.cmbchina.managedb.common.exception.BizException;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 普通 JSON 注册合同测试，不启动服务或连接数据库。 */
class DatasourceRegistrationControllerTest {
    private static final String PATH = "/managed-database/datasources";
    private DatasourceRegistration registration;
    private MockMvc mvc;

    /** 装配控制器、真实密钥过滤器和异常适配，使用测试专用临时密钥。 */
    @BeforeEach
    void setUp() {
        registration = mock(DatasourceRegistration.class);
        when(registration.register(null, "注册测试")).thenReturn(new DatasourceResourceView(
                DatasourceResource.register(new DatasourceKey("b0ddb23acc91"), 1L, null, "注册测试")));
        mvc = mockMvc("test-only-key");
    }

    /** 复用同一过滤链配置测试缺失服务配置的场景。 */
    private MockMvc mockMvc(String key) {
        return MockMvcBuilders.standaloneSetup(new DatasourceRegistrationController(registration))
                .setControllerAdvice(new BaseExceptionHandler())
                .addFilters(new ServiceKeyFilter(key, new ObjectMapper())).build();
    }

    /** 仅传名称也能注册，响应继续使用资源 Key，不把名称作为应用 Key 返回。 */
    @Test
    void returnsCreatedJson() throws Exception {
        mvc.perform(request("{\"appName\":\"注册测试\"}").header("Authorization", "Bearer test-only-key"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.datasourceKey").value("b0ddb23acc91"))
                .andExpect(jsonPath("$.appKey").doesNotExist())
                .andExpect(jsonPath("$.datasourceId").value(1))
                .andExpect(jsonPath("$.isDeleted").value(false))
                .andExpect(jsonPath("$.status").value("REGISTERED"));
    }

    /** 外部提交 datasourceKey、数组、null 或损坏 JSON 均不能触发注册。 */
    @Test
    void rejectsUnsupportedInputs() throws Exception {
        for (String body : new String[]{"{}", "{\"datasourceKey\":\"b0ddb23acc91\"}", "[]", "null", "{",
                "{\"appName\":123}", "{\"appName\":\" \"}",
                "{\"appName\":\"注册测试\",\"appKey\":false}",
                "{\"appName\":\"注册测试\",\"appKey\":\"\"}",
                "{\"appName\":\"注册测试\",\"datasourceId\":1}",
                "{\"appName\":\"" + String.join("", java.util.Collections.nCopies(256, "名")) + "\"}",
                "{\"appName\":\"注册测试\",\"appKey\":\"" + String.join("", java.util.Collections.nCopies(65, "k")) + "\"}"}) {
            mvc.perform(request(body).header("Authorization", "Bearer test-only-key"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        verifyNoInteractions(registration);
    }

    /** 可选正式应用 Key 原样传给用例，null 仍表示无关联 Key。 */
    @Test
    void acceptsOptionalApplicationKey() throws Exception {
        DatasourceResourceView result = new DatasourceResourceView(
                DatasourceResource.register(new DatasourceKey("b0ddb23acc91"), 1L, "studio-app-1", "注册测试"));
        when(registration.register("studio-app-1", "注册测试")).thenReturn(result);
        mvc.perform(request("{\"appName\":\"注册测试\",\"appKey\":\"studio-app-1\"}")
                .header("Authorization", "Bearer test-only-key")).andExpect(status().isCreated());
        verify(registration).register("studio-app-1", "注册测试");
        mvc.perform(request("{\"appName\":\"注册测试\",\"appKey\":null}")
                .header("Authorization", "Bearer test-only-key")).andExpect(status().isCreated());
        verify(registration).register(null, "注册测试");
    }

    /** 密钥失败在解析正文之前处理，不泄露正文或调用用例。 */
    @Test
    void rejectsMissingOrWrongCredentialsBeforeParsing() throws Exception {
        mvc.perform(request("{")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(request("{}").header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(registration);
    }

    /** 未配置密钥或用例发现必要配置缺失时返回 503。 */
    @Test
    void rejectsUnconfiguredService() throws Exception {
        mockMvc("").perform(request("{}"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(registration);
        when(registration.register(null, "注册测试")).thenThrow(new BizException(ResourceErrorCode.RESOURCE_SERVICE_NOT_CONFIGURED));
        mvc.perform(request("{\"appName\":\"注册测试\"}").header("Authorization", "Bearer test-only-key"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RESOURCE_SERVICE_NOT_CONFIGURED"));
        verify(registration).register(null, "注册测试");
    }

    /** 明确设置 Servlet 路径，使测试与真实容器路径判断一致。 */
    private static MockHttpServletRequestBuilder request(String body) {
        return post(PATH).servletPath(PATH).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
