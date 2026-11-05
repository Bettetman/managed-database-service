package com.cmbchina.managedb.common.response;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 在 JSON 序列化前统一包装控制器返回的业务数据。 */
@RestControllerAdvice(annotations = RestController.class)
public class JsonResponseAdvice implements ResponseBodyAdvice<Object> {

    /** 仅处理 Jackson 写出的 JSON；SSE 由 Spring 的流式返回值处理器直接写出。 */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    /** 已经统一包装的响应直接复用；保留原来的 HTTP 状态与响应头。 */
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ResponseEntity) {
            return body;
        }
        return body == null ? ResponseEntity.success() : ResponseEntity.success(body);
    }
}
