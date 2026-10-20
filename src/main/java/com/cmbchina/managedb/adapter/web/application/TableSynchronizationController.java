package com.cmbchina.managedb.adapter.web.application;

import com.cmbchina.managedb.adapter.web.request.CheckRequest;
import com.cmbchina.managedb.adapter.web.request.OperationRequest;
import com.cmbchina.managedb.application.*;
import com.cmbchina.managedb.application.view.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 普通 JSON 表操作及启动门禁，协议层不访问仓储或数据库连接。 */
@RestController
@RequiredArgsConstructor
@RequestMapping(value="/managed-database/datasources/{datasourceKey}",produces=MediaType.APPLICATION_JSON_VALUE)
public class TableSynchronizationController {
    private final TableOperationService operations;
    private final TableReadinessService readiness;
    /** 受理创建或明确授权的数据丢弃重建。 */
    @PostMapping(value="/table-operations",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TableOperationReceipt> submit(@PathVariable String datasourceKey,@RequestBody OperationRequest request){return ResponseEntity.status(HttpStatus.ACCEPTED).body(operations.submit(datasourceKey,request.getMode(),request.getSql(),Boolean.TRUE.equals(request.getAllowDataLoss())));}
    /** 查询原记录，不触发重放。 */
    @GetMapping("/table-operations/{operationId}")
    public TableOperationView detail(@PathVariable String datasourceKey,@PathVariable long operationId){return operations.detail(datasourceKey,operationId);}
    /** 查询最近保留历史，便于响应丢失和页面恢复。 */
    @GetMapping("/table-operations")
    public List<TableOperationView> recent(@PathVariable String datasourceKey,@RequestParam(defaultValue="20")int limit){return operations.recent(datasourceKey,limit);}
    /** 当前全部 SQL 的只读结构检查。 */
    @PostMapping(value="/tables/check",consumes=MediaType.APPLICATION_JSON_VALUE)
    public TableReadinessView check(@PathVariable String datasourceKey,@RequestBody CheckRequest request){return readiness.check(datasourceKey,request.getSqls());}
}
