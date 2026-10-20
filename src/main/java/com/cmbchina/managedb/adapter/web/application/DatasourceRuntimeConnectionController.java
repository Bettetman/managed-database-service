package com.cmbchina.managedb.adapter.web.application;

import com.cmbchina.managedb.application.DatasourceRuntimeConnectionService;
import com.cmbchina.managedb.application.view.RuntimeConnectionView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 后端基础设施取密入口，产品交互仍由 Studio AG-UI 启动流程承接。 */
@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/managed-database/datasources/{datasourceKey}/runtime-connection", produces = MediaType.APPLICATION_JSON_VALUE)
public class DatasourceRuntimeConnectionController {
    private final DatasourceRuntimeConnectionService service;

    /** 复用服务密钥过滤器，明确禁止中间层缓存连接响应。 */
    @GetMapping
    public ResponseEntity<RuntimeConnectionView> resolve(@PathVariable String datasourceKey) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(service.resolve(datasourceKey));
    }
}
