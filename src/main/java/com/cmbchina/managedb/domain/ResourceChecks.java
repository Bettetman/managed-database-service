package com.cmbchina.managedb.domain;

import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import com.cmbchina.managedb.common.exception.BizException;

/** 资源域基础参数校验，供聚合和值对象共享。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ResourceChecks {

    /** 校验稳定标识，不自动修剪或改写调用方传入的身份。 */
    public static String requireIdentity(String value) {
        if (value == null || value.isEmpty() || value.length() > 128
                || !value.equals(value.trim())) {
            throw new BizException(ResourceErrorCode.INVALID_RESOURCE_ARGUMENT);
        }
        return value;
    }
}
