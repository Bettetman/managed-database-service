package com.cmbchina.managedb.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmbchina.managedb.infrastructure.persistence.po.TableOperationPo;
import org.apache.ibatis.annotations.*;

/** 操作历史使用 Plus，状态写入前按固定锁顺序读取。 */
public interface TableOperationMapper extends BaseMapper<TableOperationPo> {
    /** 资源锁、目录锁之后锁定当前操作。 */
    @Select("SELECT id,datasource_key,datasource_id,action_type,execution_json,status,execution_result,created_at,is_deleted AS deleted FROM md_database_operation WHERE id=#{id} AND datasource_key=#{datasourceKey} FOR UPDATE")
    TableOperationPo lock(@Param("datasourceKey")String datasourceKey,@Param("id")long id);
}
