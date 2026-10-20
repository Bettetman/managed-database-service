package com.cmbchina.managedb.common.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 普通 HTTP 错误结果，只包含固定错误码和脱敏提示。 */
@Getter
@AllArgsConstructor
public final class ErrorResponse {
    private final String code;
    private final String message;
}
