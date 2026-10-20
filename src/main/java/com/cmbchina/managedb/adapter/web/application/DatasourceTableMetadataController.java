package com.cmbchina.managedb.adapter.web.application;

import com.cmbchina.managedb.application.DatasourceTableMetadataService;
import com.cmbchina.managedb.application.view.DatasourceTablesView;
import com.cmbchina.managedb.application.view.TableColumnsView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** Studio 后端专用的普通 JSON 元数据适配，复用现有服务密钥过滤器。 */
@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/managed-database/datasources/{datasourceKey}/tables", produces = MediaType.APPLICATION_JSON_VALUE)
public class DatasourceTableMetadataController {
    private final DatasourceTableMetadataService metadata;

    /** 查询当前数据源资源已建成且真实存在的表，不提供任意 Schema 参数。 */
    @GetMapping
    public DatasourceTablesView tables(@PathVariable String datasourceKey) {
        return metadata.tables(datasourceKey);
    }

    /** 查询当前数据源资源指定表的实际字段，不接受连接参数或执行 SQL。 */
    @GetMapping("/{tableName}/columns")
    public TableColumnsView columns(@PathVariable String datasourceKey, @PathVariable String tableName) {
        return metadata.columns(datasourceKey, tableName);
    }
}
