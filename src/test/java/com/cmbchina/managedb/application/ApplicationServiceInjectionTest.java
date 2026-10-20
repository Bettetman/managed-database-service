package com.cmbchina.managedb.application;

import com.cmbchina.managedb.domain.*;
import com.cmbchina.managedb.domain.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 验证统一 Service 构造注入，不装配 Web、数据源或执行线程池。 */
class ApplicationServiceInjectionTest {
    /** 现有和新增应用服务均由 Spring 发现并通过 Lombok 构造注入。 */
    @Test
    void injectsAllApplicationServicesWithoutManualBeanFactories() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DatasourceResourceRepository.class, () -> mock(DatasourceResourceRepository.class));
            context.registerBean(TableOperationRepository.class, () -> mock(TableOperationRepository.class));
            context.registerBean(DatabaseSchemaPort.class, () -> mock(DatabaseSchemaPort.class));
            context.registerBean(TableDataQueryPort.class, () -> mock(TableDataQueryPort.class));
            context.registerBean(RuntimeConnectionPort.class, () -> mock(RuntimeConnectionPort.class));
            context.registerBean(DatasourceKeyGenerator.class, () -> mock(DatasourceKeyGenerator.class));
            context.registerBean(TableTaskDispatcher.class, () -> mock(TableTaskDispatcher.class));
            context.registerBean(TableSyncSettings.class, () -> new TableSyncSettings(65536, 200, 4194304));
            context.registerBean(TableStructureComparator.class, TableStructureComparator::new);
            context.scan("com.cmbchina.managedb.application"); context.refresh();
            assertSame(context.getBean(DatasourceRegistrationService.class), context.getBean(DatasourceRegistration.class));
            assertSame(context.getBean(DatasourceResourceQueryService.class), context.getBean(DatasourceResourceQueries.class));
            assertNotNull(context.getBean(TableOperationService.class));
            assertNotNull(context.getBean(ResourceCleanupService.class));
            assertNotNull(context.getBean(TableReadinessService.class));
            assertNotNull(context.getBean(DatasourceTableMetadataService.class));
            assertNotNull(context.getBean(DatasourceTableDataService.class));
            assertNotNull(context.getBean(DatasourceRuntimeConnectionService.class));
            assertNotNull(context.getBean(TableSqlSupport.class));
        }
    }
}
