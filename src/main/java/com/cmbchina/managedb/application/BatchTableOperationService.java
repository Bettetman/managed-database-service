package com.cmbchina.managedb.application;

import com.cmbchina.managedb.application.view.TableOperationReceipt;
import com.cmbchina.managedb.common.exception.BizException;
import com.cmbchina.managedb.domain.*;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** 全量预检后一次认领批次，执行共享逐步证据和未知结果处理机制。 */
@Service
@RequiredArgsConstructor
public class BatchTableOperationService {
    private final TableOperationRepository repository;
    private final DatabaseSchemaPort schemas;
    private final TableSqlSupport support;
    private final TableStructureComparator comparator;
    private final TableTaskDispatcher dispatcher;
    private final TableSyncSettings settings;
    private final TableOperationService operations;
    /** 不接受破坏性模式；任一预检失败都不得登记目录或发送 SQL。 */
    public TableOperationReceipt submit(String rawKey,String mode,List<String> sqls){
        String key=new DatasourceKey(rawKey).getValue();
        if(!"CREATE".equals(mode))throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        settings.validateBatch(sqls);
        List<String> originals=new ArrayList<>(sqls),names=new ArrayList<>();List<Boolean> satisfied=new ArrayList<>();List<TableBinding.Status> statuses=new ArrayList<>();
        CheckContext context=repository.checkContext(key);DatasourceResource resource=context.getResource();resource.requireActive();
        if(resource.getActiveOperationId()!=null)throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        Set<String> unique=new HashSet<>();List<TableStructure> structures=new ArrayList<>();
        for(String sql:originals){
            TableStructure expected=support.parse(resource.getDatasourceId(),sql).getStructure();String name=expected.getTableName();
            if(!unique.add(name))throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
            names.add(name);structures.add(expected);
        }
        for(TableStructure expected:structures){
            String name=expected.getTableName();Optional<TableBinding> binding=repository.binding(resource.getDatasourceId(),name);
            if(binding.isPresent())binding.get().requireOwner(key,resource.getDatasourceId());
            Optional<TableStructure> actual=schemas.readTable(resource.getDatasourceId(),name);boolean exists=false;
            if(actual.isPresent()){
                if(!binding.isPresent()||binding.get().getStatus()!=TableBinding.Status.CREATED)throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
                if(!comparator.compare(expected,actual.get()).isEmpty())throw new BizException(ResourceErrorCode.STRUCTURE_MISMATCH);exists=true;
            }
            support.requireReferences(key,resource.getDatasourceId(),expected);
            statuses.add(binding.map(TableBinding::getStatus).orElse(null));satisfied.add(exists);
        }
        TableOperation op=TableOperation.startBatch(repository.nextId(),key,resource.getDatasourceId(),names,originals,satisfied);
        try(TableTaskDispatcher.Reservation slot=dispatcher.reserve()){
            repository.beginBatch(op,statuses,context.getOperationCount());
            try{slot.dispatch(()->operations.run(key,op.getId()));}catch(BizException rejected){
                String code=rejected.getErrorCode().getErrorCodeStr();op.complete(0,TableOperation.StepStatus.FAILED,code,Collections.singletonMap("sqlExecuted",false));op.finish(TableOperation.Status.FAILED,code);repository.finish(op);throw rejected;
            }
        }
        return new TableOperationReceipt(op.getId());
    }
}
