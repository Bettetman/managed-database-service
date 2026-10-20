package com.cmbchina.managedb.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmbchina.managedb.infrastructure.persistence.po.TableBindingPo;
import org.apache.ibatis.annotations.*;

/** 表目录 CRUD 复用 Plus，位置行锁保留必要 SQL。 */
public interface TableBindingMapper extends BaseMapper<TableBindingPo> {
    /** 在资源锁之后锁定精确位置，包含软删除记录。 */
    @Select("SELECT id,datasource_key,datasource_id,table_name,status,is_deleted AS deleted FROM md_datasource_table WHERE datasource_id=#{datasourceId} AND table_name=#{tableName} FOR UPDATE")
    TableBindingPo lock(@Param("datasourceId")long datasourceId,@Param("tableName")String tableName);
}
