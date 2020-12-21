package com.sequenceiq.environment.network.service;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.sequenceiq.cloudbreak.cloud.NetworkConnector;
import com.sequenceiq.cloudbreak.cloud.init.CloudPlatformConnectors;
import com.sequenceiq.cloudbreak.cloud.model.CloudPlatformVariant;
import com.sequenceiq.cloudbreak.cloud.model.CloudSubnet;
import com.sequenceiq.cloudbreak.cloud.model.SubnetSelectionParameters;
import com.sequenceiq.cloudbreak.cloud.model.SubnetSelectionResult;
import com.sequenceiq.cloudbreak.common.mappable.CloudPlatform;
import com.sequenceiq.common.api.type.Tunnel;
import com.sequenceiq.environment.network.dto.NetworkDto;

@Component
public class SubnetIdProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(SubnetIdProvider.class);

    @Inject
    private CloudPlatformConnectors cloudPlatformConnectors;

    public String provide(NetworkDto network, Tunnel tunnel, CloudPlatform cloudPlatform) {
        if (network == null || network.getSubnetIds() == null || network.getSubnetIds().isEmpty() || network.getCbSubnets() == null
                || network.getCbSubnets().isEmpty()) {
            LOGGER.debug("Check failed, returning null");
            return null;
        }
        return selectSubnet(network, tunnel, cloudPlatform, network.getCbSubnetValues(), null);
    }

    public String provideEndpointGateway(NetworkDto network, CloudPlatform cloudPlatform, String baseSubnetId) {
        if (baseSubnetId == null || network == null ||
                ((network.getEndpointGatewaySubnetMetas() == null || network.getEndpointGatewaySubnetMetas().isEmpty())
                && (network.getCbSubnets() == null || network.getCbSubnets().isEmpty()))) {
            LOGGER.debug("Check failed, returning null");
            return null;
        }
        // Use tunnel type DIRECT so that any logic that checks if CCM/private subnets are required evaluates to false
        Tunnel tunnel = Tunnel.DIRECT;
        Set<String> availabilityZones = network.getCbSubnetValues().stream()
            .filter(subnet -> baseSubnetId.equals(subnet.getId()))
            .map(CloudSubnet::getAvailabilityZone)
            .collect(Collectors.toSet());
        if (network.getEndpointGatewaySubnetMetas() != null && !network.getEndpointGatewaySubnetMetas().isEmpty()) {
            return selectSubnet(network, tunnel, cloudPlatform, network.getEndpointGatewaySubnetMetas().values(), availabilityZones);
        } else {
            List<CloudSubnet> publicSubnets = network.getCbSubnetValues().stream()
                .filter(subnet -> !subnet.isPrivateSubnet())
                .collect(Collectors.toList());
            return selectSubnet(network, tunnel, cloudPlatform, publicSubnets, availabilityZones);
        }
    }

    private String selectSubnet(NetworkDto network, Tunnel tunnel, CloudPlatform cloudPlatform, Collection<CloudSubnet> subnets,
            Set<String> requiredAvailabilityZones) {
        LOGGER.debug("Choosing subnet, network: {},  platform: {}, tunnel: {}", network, cloudPlatform, tunnel);

        NetworkConnector networkConnector = cloudPlatformConnectors
                .get(new CloudPlatformVariant(cloudPlatform.name(), cloudPlatform.name()))
                .networkConnector();
        if (networkConnector == null) {
            LOGGER.warn("Network connector is null for '{}' cloud platform, returning null", cloudPlatform.name());
            return null;
        }
        SubnetSelectionParameters subnetSelectionParameters = SubnetSelectionParameters
                .builder()
                .withTunnel(tunnel)
                .withRequiredAvailabilityZones(requiredAvailabilityZones)
                .build();

        SubnetSelectionResult subnetSelectionResult = networkConnector
                .chooseSubnets(subnets, subnetSelectionParameters);
        CloudSubnet selectedSubnet = subnetSelectionResult.hasResult()
                ? subnetSelectionResult.getResult().get(0)
                : null;
        if (selectedSubnet == null) {
            if (requiredAvailabilityZones == null || requiredAvailabilityZones.isEmpty()) {
                selectedSubnet = fallback(network);
            } else {
                LOGGER.debug("Could not find subnet in required AZ {}, returning null", requiredAvailabilityZones);
                return null;
            }
        }
        return selectedSubnet.getId();
    }

    private CloudSubnet fallback(NetworkDto network) {
        CloudSubnet chosenSubnet = network.getSubnetMetas().values().iterator().next();
        LOGGER.debug("Choosing subnet, fallback strategy: '{}'", chosenSubnet.getId());
        return chosenSubnet;
    }
}
