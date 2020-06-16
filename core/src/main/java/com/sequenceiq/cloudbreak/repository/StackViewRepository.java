package com.sequenceiq.cloudbreak.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.transaction.Transactional;
import javax.transaction.Transactional.TxType;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sequenceiq.cloudbreak.domain.view.StackView;
import com.sequenceiq.cloudbreak.workspace.repository.EntityType;
import com.sequenceiq.cloudbreak.workspace.repository.workspace.WorkspaceResourceRepository;

@EntityType(entityClass = StackView.class)
@Transactional(TxType.REQUIRED)
public interface StackViewRepository extends WorkspaceResourceRepository<StackView, Long> {

    @Query("SELECT s FROM StackView s WHERE s.workspace.id= :workspaceId AND s.terminated = null AND s.name LIKE :name")
    Optional<StackView> findByWorkspaceIdAndName(@Param("workspaceId") Long workspaceId, @Param("name") String name);

    @Query("SELECT s FROM StackView s WHERE s.workspace.id= :workspaceId AND s.terminated = null AND s.resourceCrn LIKE :crn")
    Optional<StackView> findByWorkspaceIdAndCrn(@Param("workspaceId") Long workspaceId, @Param("crn") String resourceCrn);

    @Query("SELECT s.resourceCrn FROM StackView s WHERE s.workspace.tenant.name = :tenantName AND s.terminated = null AND s.name LIKE :name")
    Optional<String> findResourceCrnByTenantNameAndName(@Param("tenantName") String tenantName, @Param("name") String name);

    @Query("SELECT s.resourceCrn FROM StackView s WHERE s.workspace.tenant.name = :tenantName AND s.terminated = null AND s.name IN (:names)")
    Set<String> findResourceCrnsByTenantNameAndNames(@Param("tenantName") String tenantName, @Param("names") List<String> names);

    @Query("SELECT s.resourceCrn FROM StackView s WHERE s.workspace.tenant.name = :tenantName AND s.terminated = null")
    Set<String> findResourceCrnsByTenant(@Param("tenantName") String tenantName);

    @Query("SELECT s FROM StackView s WHERE s.id= :id")
    Optional<StackView> findById(@Param("id") Long id);

    @Override
    default <S extends StackView> S save(S entity) {
        throw new UnsupportedOperationException("Save is not supported on stack view type");
    }
}
