package com.sequenceiq.cloudbreak.converter.v4.environment.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.BadRequestException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sequenceiq.cloudbreak.cloud.model.CloudSubnet;
import com.sequenceiq.cloudbreak.cloud.model.network.SubnetType;
import com.sequenceiq.cloudbreak.common.converter.MissingResourceNameGenerator;
import com.sequenceiq.cloudbreak.common.mappable.CloudPlatform;
import com.sequenceiq.cloudbreak.domain.Network;
import com.sequenceiq.common.api.type.OutboundInternetTraffic;
import com.sequenceiq.common.api.type.PublicEndpointAccessGateway;
import com.sequenceiq.environment.api.v1.environment.model.response.EnvironmentNetworkResponse;

@ExtendWith(MockitoExtension.class)
public class EnvironmentBaseNetworkConverterTest {

    @InjectMocks
    private TestEnvironmentBaseNetworkConverter underTest;

    @Mock
    private MissingResourceNameGenerator missingResourceNameGenerator;

    @Test
    public void testConvertToLegacyNetworkWhenSubnetNotFound() {
        EnvironmentNetworkResponse source = new EnvironmentNetworkResponse();
        source.setSubnetMetas(Map.of("key", getCloudSubnet("any")));
        BadRequestException badRequestException = assertThrows(BadRequestException.class, () -> underTest.convertToLegacyNetwork(source, "eu-west-1a"));
        assertEquals(badRequestException.getMessage(), "No subnet for the given availability zone: eu-west-1a");
    }

    @Test
    public void testConvertToLegacyNetworkWhenSubnetFound() {
        EnvironmentNetworkResponse source = new EnvironmentNetworkResponse();
        Set<String> networkCidrs = Set.of("1.2.3.4/32", "0.0.0.0/0");
        source.setNetworkCidrs(networkCidrs);
        source.setOutboundInternetTraffic(OutboundInternetTraffic.DISABLED);
        source.setSubnetMetas(Map.of("key", getCloudSubnet("eu-west-1a")));
        Network network = underTest.convertToLegacyNetwork(source, "eu-west-1a");
        assertEquals(network.getAttributes().getValue("subnetId"), "eu-west-1");
        assertTrue(network.getNetworkCidrs().containsAll(networkCidrs));
        assertEquals(source.getOutboundInternetTraffic(), network.getOutboundInternetTraffic());
    }

    @Test
    public void testConvertToLegacyNetworkWithEndpointAccessGatewayAndProvidedSubnets() {
        EnvironmentNetworkResponse source = new EnvironmentNetworkResponse();
        Set<String> networkCidrs = Set.of("1.2.3.4/32", "0.0.0.0/0");
        source.setNetworkCidrs(networkCidrs);
        source.setOutboundInternetTraffic(OutboundInternetTraffic.DISABLED);
        source.setSubnetMetas(Map.of("key", getCloudSubnet("AZ-1")));
        source.setPublicEndpointAccessGateway(PublicEndpointAccessGateway.ENABLED);
        source.setEndpointGatewaySubnetMetas(Map.of("public-key", getPublicCloudSubnet("public-id-1", "AZ-1")));

        Network network = underTest.convertToLegacyNetwork(source, "AZ-1");

        assertEquals(network.getAttributes().getValue("endpointGatewaySubnetId"), "public-id-1");
    }

    @Test
    public void testConvertToLegacyNetworkWithEndpointAccessGatewayAndEnvironmentSubnets() {
        EnvironmentNetworkResponse source = new EnvironmentNetworkResponse();
        Set<String> networkCidrs = Set.of("1.2.3.4/32", "0.0.0.0/0");
        source.setNetworkCidrs(networkCidrs);
        source.setOutboundInternetTraffic(OutboundInternetTraffic.DISABLED);
        source.setSubnetMetas(Map.of(
            "key1", getPrivateCloudSubnet("private-id-1", "AZ-1"),
            "key2", getPublicCloudSubnet("public-id-1", "AZ-1")
        ));
        source.setPublicEndpointAccessGateway(PublicEndpointAccessGateway.ENABLED);

        Network network = underTest.convertToLegacyNetwork(source, "AZ-1");

        assertEquals(network.getAttributes().getValue("endpointGatewaySubnetId"), "public-id-1");
    }

    @Test
    public void testConvertToLegacyNetworkWithEndpointAccessGatewayProvidedSubnetsNoPublicSubnets() {
        EnvironmentNetworkResponse source = new EnvironmentNetworkResponse();
        Set<String> networkCidrs = Set.of("1.2.3.4/32", "0.0.0.0/0");
        source.setNetworkCidrs(networkCidrs);
        source.setOutboundInternetTraffic(OutboundInternetTraffic.DISABLED);
        source.setSubnetMetas(Map.of("key", getPrivateCloudSubnet("private-id-1", "AZ-1")));
        source.setPublicEndpointAccessGateway(PublicEndpointAccessGateway.ENABLED);
        source.setEndpointGatewaySubnetMetas(Map.of("private-key", getPrivateCloudSubnet("private-id-1", "AZ-1")));

        Exception exception = assertThrows(BadRequestException.class, () ->
            underTest.convertToLegacyNetwork(source, "AZ-1")
        );
        assertEquals("Could not find public subnet in availability zone: AZ-1", exception.getMessage());
    }

    @Test
    public void testConvertToLegacyNetworkWithEndpointAccessGatewayEnvironmentSubnetsNoPublicSubnets() {
        EnvironmentNetworkResponse source = new EnvironmentNetworkResponse();
        Set<String> networkCidrs = Set.of("1.2.3.4/32", "0.0.0.0/0");
        source.setNetworkCidrs(networkCidrs);
        source.setOutboundInternetTraffic(OutboundInternetTraffic.DISABLED);
        source.setSubnetMetas(Map.of("key", getPrivateCloudSubnet("private-id-1", "AZ-1")));
        source.setPublicEndpointAccessGateway(PublicEndpointAccessGateway.ENABLED);

        Exception exception = assertThrows(BadRequestException.class, () ->
            underTest.convertToLegacyNetwork(source, "AZ-1")
        );
        assertEquals("Could not find public subnet in availability zone: AZ-1", exception.getMessage());
    }

    private CloudSubnet getCloudSubnet(String availabilityZone) {
        return new CloudSubnet("eu-west-1", "name", availabilityZone, "cidr");
    }

    private CloudSubnet getPublicCloudSubnet(String id, String availabilityZone) {
        return new CloudSubnet(id, "name", availabilityZone, "cidr", false, true, true, SubnetType.PUBLIC);
    }

    private CloudSubnet getPrivateCloudSubnet(String id, String availabilityZone) {
        return new CloudSubnet(id, "name", availabilityZone, "cidr", true, false, false, SubnetType.PRIVATE);
    }

    private static class TestEnvironmentBaseNetworkConverter extends EnvironmentBaseNetworkConverter {

        @Override
        Map<String, Object> getAttributesForLegacyNetwork(EnvironmentNetworkResponse source) {
            return Collections.emptyMap();
        }

        @Override
        public CloudPlatform getCloudPlatform() {
            return CloudPlatform.AWS;
        }
    }
}
