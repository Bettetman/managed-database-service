package com.cmbchina.managedb.common.exception;

import java.io.Serializable;
import java.text.MessageFormat;

/** 统一错误码约定，与现有后端模板保持一致。 */
public interface IErrorCode extends Serializable {

    /** 返回可展示给调用方的固定提示模板。 */
    String getErrorMessage();

    /** 返回用于客户端识别的错误码。 */
    default String getErrorCodeStr() {
        return this.toString();
    }

    /** 使用非敏感参数格式化错误提示。 */
    default String getErrorMessage(Object... params) {
        return MessageFormat.format(getErrorMessage(), params);
    }
}
