package com.cmbchina.managedb.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmbchina.managedb.infrastructure.persistence.po.DatasourceRegistrationPo;
import com.cmbchina.managedb.infrastructure.persistence.po.DatasourceCountPo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

/** 简单读写复用 MyBatis-Plus，跨表及分组统计保留明确 SQL。 */
public interface DatasourceRegistrationMapper extends BaseMapper<DatasourceRegistrationPo> {
    /** 执行事务首先锁定应用，恢复完整占用。 */
    @Select("SELECT id,datasource_key,datasource_id,app_key,app_name,status,active_operation_id,is_deleted AS deleted FROM md_datasource_resource WHERE datasource_key=#{datasourceKey} FOR UPDATE")
    DatasourceRegistrationPo lock(@Param("datasourceKey")String datasourceKey);
    /** 只认领有效且无占用的应用。 */
    @Update("UPDATE md_datasource_resource SET active_operation_id=#{operationId} WHERE datasource_key=#{datasourceKey} AND status='REGISTERED' AND is_deleted=0 AND active_operation_id IS NULL")
    int claim(@Param("datasourceKey")String datasourceKey,@Param("operationId")long operationId);
    /** 只释放本操作占用，UNKNOWN 不调用。 */
    @Update("UPDATE md_datasource_resource SET active_operation_id=NULL WHERE datasource_key=#{datasourceKey} AND active_operation_id=#{operationId}")
    int release(@Param("datasourceKey")String datasourceKey,@Param("operationId")long operationId);

    /** 回收成功与当前占用释放原子提交，保留资源和归属历史。 */
    @Update("UPDATE md_datasource_resource SET status='RELEASED',active_operation_id=NULL WHERE datasource_key=#{datasourceKey} AND active_operation_id=#{operationId} AND status='REGISTERED' AND is_deleted=0")
    int completeRelease(@Param("datasourceKey")String datasourceKey,@Param("operationId")long operationId);

    /** 统计最近确认建成的表，软删除不意味着物理表已经删除。 */
    @Select({"<script>", "SELECT datasource_id, COUNT(*) AS total FROM md_datasource_table",
            "WHERE status = 'CREATED' AND datasource_id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "GROUP BY datasource_id", "</script>"})
    List<DatasourceCountPo> countTables(@Param("ids") List<Long> ids);

    /** 有效资源数只用于表数相同时处理平局。 */
    @Select({"<script>", "SELECT datasource_id, COUNT(*) AS total FROM md_datasource_resource",
            "WHERE status = 'REGISTERED' AND is_deleted = 0 AND datasource_id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "GROUP BY datasource_id", "</script>"})
    List<DatasourceCountPo> countResources(@Param("ids") List<Long> ids);

}
