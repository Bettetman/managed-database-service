package com.cmbchina.managedb.common.exception;

import com.cmbchina.managedb.common.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import javax.validation.ConstraintViolationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

/** 普通 JSON 接口的统一异常适配，不依赖业务域枚举或返回原始异常正文。 */
@Slf4j
@RestControllerAdvice
public class BaseExceptionHandler {
    /** 依错误契约映射真实 HTTP 状态，不用 HTTP 200 包装业务失败。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ErrorResponse> exceptionHandle(BizException exception) {
        String code = exception.getErrorCode().getErrorCodeStr();
        HttpStatus status;
        switch (code) {
            case "SQL_UNSUPPORTED":
            case "PRIMARY_KEY_REQUIRED":
            case "INVALID_ARGUMENT":
            case "INVALID_RESOURCE_ARGUMENT": status = HttpStatus.BAD_REQUEST; break;
            case "TABLE_BINDING_CONFLICT":
            case "STRUCTURE_MISMATCH":
            case "RESOURCE_BUSY": status = HttpStatus.CONFLICT; break;
            case "RESOURCE_NOT_FOUND": status = HttpStatus.NOT_FOUND; break;
            case "RESOURCE_SERVICE_NOT_CONFIGURED":
            case "EXECUTION_CAPACITY_EXHAUSTED":
            case "RUNTIME_CONNECTION_NOT_CONFIGURED":
            case "DATABASE_UNAVAILABLE": status = HttpStatus.SERVICE_UNAVAILABLE; break;
            default: status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        log.warn("业务失败，错误码={}", code);
        return ResponseEntity.status(status).body(new ErrorResponse(code, exception.getMessage()));
    }

    /** 参数异常不回显请求正文，防止连接配置等内容被公开。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> validationExceptionHandle(Exception exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_ARGUMENT", "请求参数无效"));
    }

    /** 保持方法不支持的标准状态，避免被统一未知异常误判成服务器故障。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ErrorResponse("INVALID_ARGUMENT", "请求方法不支持"));
    }

    /** 保持媒体类型不支持的标准状态。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorResponse("INVALID_ARGUMENT", "请求媒体类型不支持"));
    }

    /** 未分类错误不推断注册未提交，不记录可能含凭据的堆栈。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> internalError(Exception exception) {
        log.error("内部异常，类型={}", exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "操作结果未确认，请勿自动重试"));
    }
}
