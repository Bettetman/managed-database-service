package com.cmbchina.managedb.domain.valueobject;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import com.cmbchina.managedb.common.exception.BizException;
import com.cmbchina.managedb.domain.ResourceErrorCode;

/** SaaS 生成的十二位数据源标识，不承担权限凭证职责。 */
@Getter
@EqualsAndHashCode(of = "value")
public final class DatasourceKey {
    private final String value;

    /** 校验编码格式，不自动补前缀或改变大小写。 */
    public DatasourceKey(String value) {
        if (value == null || !value.matches("[0-9a-f]{12}")) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        this.value = value;
    }
}
