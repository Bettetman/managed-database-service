package com.cmbchina.managedb.common.exception;

/** 由应用服务和领域规则抛出的明确业务异常。 */
public class BizException extends AbstractBaseException {

    private static final long serialVersionUID = 1L;

    /** 通过业务错误码创建异常。 */
    public BizException(IErrorCode errorCode) {
        super(errorCode);
    }

    /** 通过业务错误码和非敏感参数创建异常。 */
    public BizException(IErrorCode errorCode, Object... params) {
        super(errorCode, params);
    }

    /** 保存内部原因，同时保留统一错误码。 */
    public BizException(IErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    /** 保存内部原因和非敏感提示参数。 */
    public BizException(IErrorCode errorCode, Throwable cause, Object... params) {
        super(errorCode, cause, params);
    }
}
