package com.cmbchina.managedb.application;

import com.cmbchina.managedb.application.view.TableReadinessView;
import com.cmbchina.managedb.common.exception.BizException;
import com.cmbchina.managedb.domain.*;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** 启动检查只读，当前全模型由 Studio 提供，历史成功不等于当前同步。 */
@Service
@RequiredArgsConstructor
public class TableReadinessService {
    private final TableOperationRepository repository;
    private final DatabaseSchemaPort schemas;
    private final TableSqlSupport support;
    private final TableStructureComparator comparator;
    private final TableSyncSettings settings;
    /** 前后使用新快照检测受理写入，不锁管理行等待元数据。 */
    public TableReadinessView check(String rawDatasourceKey,List<String> sqls){
        String datasourceKey=new DatasourceKey(rawDatasourceKey).getValue();settings.validateList(sqls);CheckContext before=repository.checkContext(datasourceKey);DatasourceResource resource=before.getResource();resource.requireActive();
        if(resource.getActiveOperationId()!=null)return busy(before,"OPERATION_IN_PROGRESS");
        List<TableStructure> expected=new ArrayList<>();Set<String> names=new HashSet<>();
        for(String sql:sqls){TableStructure structure=support.parse(resource.getDatasourceId(),sql).getStructure();if(!names.add(structure.getTableName()))throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);expected.add(structure);}
        List<TableReadinessView.Entry> entries=new ArrayList<>();boolean ready=true;
        for(TableStructure structure:expected){
            String name=structure.getTableName(),status;List<StructureDifference> differences=Collections.emptyList();Optional<TableBinding> binding=repository.binding(resource.getDatasourceId(),name);
            if(binding.isPresent()&&(binding.get().isDeleted()||!datasourceKey.equals(binding.get().getDatasourceKey())))status="TABLE_CONFLICT";
            else{
                Optional<TableStructure> actual=schemas.readTable(resource.getDatasourceId(),name);
                if(!binding.isPresent())status=actual.isPresent()?"TABLE_CONFLICT":"NOT_CREATED";
                else if(binding.get().getStatus()!=TableBinding.Status.CREATED||!actual.isPresent())status="NOT_CREATED";
                else{
                    try{support.requireReferences(datasourceKey,resource.getDatasourceId(),structure);differences=comparator.compare(structure,actual.get());status=differences.isEmpty()?"SYNCED":"STRUCTURE_MISMATCH";}
                    catch(BizException e){if(e.getErrorCode()!=ResourceErrorCode.TABLE_BINDING_CONFLICT)throw e;status="TABLE_CONFLICT";}
                }
            }
            if(!status.equals("SYNCED"))ready=false;entries.add(new TableReadinessView.Entry(name,status,differences));
        }
        List<String> extra=new ArrayList<>();for(TableBinding table:repository.bindings(datasourceKey))if(table.getStatus()==TableBinding.Status.CREATED&&!names.contains(table.getTableName()))extra.add(table.getTableName());
        CheckContext after=repository.checkContext(datasourceKey);after.getResource().requireActive();if(after.getResource().getActiveOperationId()!=null||before.getOperationCount()!=after.getOperationCount()||resource.getDatasourceId()!=after.getResource().getDatasourceId())return busy(after,"CHECK_CHANGED");
        return new TableReadinessView(ready,resource.getDatasourceId(),null,null,null,entries,extra);
    }
    /** 占用或检查期间出现写入时不伪造逐表成功。 */
    private TableReadinessView busy(CheckContext context,String reason){
        Long id=context.getResource().getActiveOperationId();String status=id==null?null:repository.detail(context.getResource().getDatasourceKey().getValue(),id).map(op->op.getStatus().name()).orElse("RUNNING");
        return new TableReadinessView(false,context.getResource().getDatasourceId(),reason,id==null?null:Long.toString(id),status,Collections.emptyList(),Collections.emptyList());
    }
}
