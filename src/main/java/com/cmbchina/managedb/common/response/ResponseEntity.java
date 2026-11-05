package com.cmbchina.managedb.common.response;

import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import com.cmbchina.managedb.common.exception.IErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.Collections;
import java.util.Map;

/** 非流式 JSON 接口使用的统一响应格式。 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ResponseEntity<T> {

    private String returnCode;
    private String errorMsg;
    private T body;

    /** 创建不携带业务数据的成功响应。 */
    public static ResponseEntity<Map<String, Object>> success() {
        return success(Collections.emptyMap());
    }

    /** 创建携带数据的成功响应。 */
    public static <T> ResponseEntity<T> success(T body) {
        ResponseEntity<T> response = new ResponseEntity<>();
        response.setReturnCode("SUC0000");
        response.setBody(body);
        return response;
    }

    /** 根据统一错误码创建失败响应。 */
    public static ResponseEntity<Map<String, Object>> failed(IErrorCode errorCode, Object... params) {
        return failed(errorCode.getErrorCodeStr(), errorCode.getErrorMessage(params));
    }

    /** 根据已脱敏的错误码与提示创建失败响应。 */
    public static ResponseEntity<Map<String, Object>> failed(String errorCode, String errorMsg) {
        ResponseEntity<Map<String, Object>> response = new ResponseEntity<>();
        response.setReturnCode(errorCode);
        response.setErrorMsg(errorMsg);
        response.setBody(Collections.emptyMap());
        return response;
    }
}
