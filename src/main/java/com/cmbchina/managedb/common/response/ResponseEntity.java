package com.cmbchina.managedb.common.response;

import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import com.cmbchina.managedb.common.exception.IErrorCode;
import lombok.Getter;
import lombok.Setter;

/** 沿用模板的基础设施 HTTP 响应格式；后续 AG-UI 业务流不使用此包装。 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ResponseEntity<T> {

    private String returnCode;
    private String errorMsg;
    private T body;

    /** 创建不携带业务数据的成功响应。 */
    public static <T> ResponseEntity<T> success() {
        return success(null);
    }

    /** 创建携带数据的成功响应。 */
    public static <T> ResponseEntity<T> success(T body) {
        ResponseEntity<T> response = new ResponseEntity<>();
        response.setReturnCode("SUC0000");
        response.setBody(body);
        return response;
    }

    /** 根据统一错误码创建失败响应。 */
    public static <T> ResponseEntity<T> failed(IErrorCode errorCode, Object... params) {
        return failed(errorCode.getErrorCodeStr(), errorCode.getErrorMessage(params));
    }

    /** 根据已脱敏的错误码与提示创建失败响应。 */
    public static <T> ResponseEntity<T> failed(String errorCode, String errorMsg) {
        ResponseEntity<T> response = new ResponseEntity<>();
        response.setReturnCode(errorCode);
        response.setErrorMsg(errorMsg);
        return response;
    }
}
