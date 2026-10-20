package com.cmbchina.managedb.adapter.web.application;

import lombok.RequiredArgsConstructor;
import com.cmbchina.managedb.application.DatasourceRegistration;
import com.cmbchina.managedb.application.view.DatasourceResourceView;
import com.cmbchina.managedb.adapter.web.request.DatasourceRegistrationRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** SaaS 普通同步注册入口，datasourceKey 仅由服务生成。 */
@RestController
@RequiredArgsConstructor
public class DatasourceRegistrationController {
    private final DatasourceRegistration registration;

    /** 接受应用关联信息，事务提交后仍返回原资源绑定合同。 */
    @PostMapping(value = "/managed-database/datasources",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DatasourceResourceView> register(@RequestBody JsonNode request) {
        DatasourceRegistrationRequest input = DatasourceRegistrationRequest.parse(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(registration.register(input.getAppKey(), input.getAppName()));
    }
}
