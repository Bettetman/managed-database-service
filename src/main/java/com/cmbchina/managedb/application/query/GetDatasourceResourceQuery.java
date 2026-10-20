package com.cmbchina.managedb.application.query;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.NonNull;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;

/** 按已有 datasourceKey 查询，不引入尚未接入的用户身份。 */
@Getter
@RequiredArgsConstructor
public final class GetDatasourceResourceQuery {
    @NonNull
    private final DatasourceKey datasourceKey;
}
