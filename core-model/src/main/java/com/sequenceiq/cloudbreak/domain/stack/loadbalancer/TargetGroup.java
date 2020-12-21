package com.sequenceiq.cloudbreak.domain.stack.loadbalancer;

import java.util.HashSet;
import java.util.Set;

import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToMany;
import javax.persistence.SequenceGenerator;

import com.sequenceiq.cloudbreak.domain.ProvisionEntity;
import com.sequenceiq.cloudbreak.domain.converter.TargetGroupTypeConverter;
import com.sequenceiq.cloudbreak.domain.stack.instance.InstanceGroup;
import com.sequenceiq.common.api.type.TargetGroupType;

@Entity
public class TargetGroup implements ProvisionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO, generator = "targetgroup_generator")
    @SequenceGenerator(name = "targetgroup_generator", sequenceName = "targetgroup_id_seq", allocationSize = 1)
    private Long id;

    @Convert(converter = TargetGroupTypeConverter.class)
    private TargetGroupType type;

    @ManyToMany(fetch = FetchType.EAGER)
    private Set<LoadBalancer> loadBalancers = new HashSet<>();

    @ManyToMany(fetch = FetchType.EAGER)
    private Set<InstanceGroup> instanceGroups = new HashSet<>();

    public Long getId() {
        return id;
    }

    public TargetGroupType getType() {
        return type;
    }

    public void setType(TargetGroupType type) {
        this.type = type;
    }

    public Set<LoadBalancer> getLoadBalancers() {
        return loadBalancers;
    }

    public void setLoadBalancers(Set<LoadBalancer> loadBalancers) {
        this.loadBalancers = loadBalancers;
    }

    public void addLoadBalancer(LoadBalancer loadBalancer) {
        loadBalancers.add(loadBalancer);
    }

    public Set<InstanceGroup> getInstanceGroups() {
        return instanceGroups;
    }

    public void setInstanceGroups(Set<InstanceGroup> instanceGroups) {
        this.instanceGroups = instanceGroups;
    }
}
