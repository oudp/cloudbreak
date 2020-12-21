package com.sequenceiq.environment.environment.flow.creation.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.ws.rs.BadRequestException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.util.ReflectionUtils;

import reactor.bus.Event;
import reactor.bus.EventBus;

import com.sequenceiq.cloudbreak.cloud.model.CloudSubnet;
import com.sequenceiq.cloudbreak.cloud.model.network.SubnetType;
import com.sequenceiq.common.api.type.PublicEndpointAccessGateway;
import com.sequenceiq.environment.environment.domain.Environment;
import com.sequenceiq.environment.environment.dto.EnvironmentDto;
import com.sequenceiq.environment.environment.flow.creation.event.EnvCreationFailureEvent;
import com.sequenceiq.environment.environment.service.EnvironmentResourceService;
import com.sequenceiq.environment.environment.service.EnvironmentService;
import com.sequenceiq.environment.network.CloudNetworkService;
import com.sequenceiq.environment.network.dao.domain.AwsNetwork;
import com.sequenceiq.environment.network.dao.domain.BaseNetwork;
import com.sequenceiq.environment.network.dao.domain.RegistrationType;
import com.sequenceiq.environment.network.dto.NetworkDto;
import com.sequenceiq.flow.reactor.api.event.EventSender;

public class NetworkCreationHandlerTest {

    private static final String UNMATCHED_AZ_MSG = "Please provide public subnets in each of the following availability zones:";

    @InjectMocks
    private NetworkCreationHandler underTest;

    @Mock
    private EnvironmentService environmentService;

    @Mock
    private EnvironmentResourceService environmentResourceService;

    @Mock
    private CloudNetworkService cloudNetworkService;

    @Mock
    private EventBus eventBus;

    @Mock
    private EventSender eventSender;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        Field enabledPlatformsField = ReflectionUtils.findField(NetworkCreationHandler.class, "enabledPlatforms");
        ReflectionUtils.makeAccessible(enabledPlatformsField);
        ReflectionUtils.setField(enabledPlatformsField, underTest, Set.of("AWS", "AZURE"));

        doNothing().when(eventSender).sendEvent(any(), any());
        when(eventBus.notify(any(Object.class), any(Event.class))).thenReturn(null);
    }

    @Test
    public void testWithEndpointGatewayAndProvidedSubnets() {
        EnvironmentDto environmentDto = createEnvironmentDto();
        Event<EnvironmentDto> environmentDtoEvent = Event.wrap(environmentDto);
        AwsNetwork network = createNetwork();
        Environment environment = createEnvironment(network);
        Optional<Environment> environmentOptional = Optional.of(environment);

        Map<String, CloudSubnet> subnets = createDefaultPrivateSubnets();
        Map<String, CloudSubnet> endpointGatewaySubnets = createDefaultPublicSubnets();

        when(environmentService.findEnvironmentById(any())).thenReturn(environmentOptional);
        when(cloudNetworkService.retrieveSubnetMetadata(any(EnvironmentDto.class), any())).thenReturn(subnets);
        when(cloudNetworkService.retrieveEndpointGatewaySubnetMetadata(any(EnvironmentDto.class), any())).thenReturn(endpointGatewaySubnets);
        when(environmentResourceService.createAndSetNetwork(any(), any(), any(), any(), any())).thenReturn(network);

        underTest.accept(environmentDtoEvent);

        assertEquals(2, environmentDto.getNetwork().getEndpointGatewaySubnetMetas().size());
        assertEquals(Set.of("public-id1", "public-id2"), environmentDto.getNetwork().getEndpointGatewaySubnetIds());
    }

    @Test
    public void testWithEndpointGatewayRemovePrivateSubnets() {
        EnvironmentDto environmentDto = createEnvironmentDto();
        Event<EnvironmentDto> environmentDtoEvent = Event.wrap(environmentDto);
        AwsNetwork network = createNetwork();
        Environment environment = createEnvironment(network);
        Optional<Environment> environmentOptional = Optional.of(environment);

        Map<String, CloudSubnet> subnets = createDefaultPrivateSubnets();
        Map<String, CloudSubnet> endpointGatewaySubnets = createDefaultPublicSubnets();
        endpointGatewaySubnets.putAll(createDefaultPrivateSubnets());

        when(environmentService.findEnvironmentById(any())).thenReturn(environmentOptional);
        when(cloudNetworkService.retrieveSubnetMetadata(any(EnvironmentDto.class), any())).thenReturn(subnets);
        when(cloudNetworkService.retrieveEndpointGatewaySubnetMetadata(any(EnvironmentDto.class), any())).thenReturn(endpointGatewaySubnets);
        when(environmentResourceService.createAndSetNetwork(any(), any(), any(), any(), any())).thenReturn(network);

        underTest.accept(environmentDtoEvent);

        assertEquals(2, environmentDto.getNetwork().getEndpointGatewaySubnetMetas().size());
        assertEquals(Set.of("public-id1", "public-id2"), environmentDto.getNetwork().getEndpointGatewaySubnetIds());
    }

    @Test
    public void testWithEndpointGatewayAndEnvironmentSubnets() {
        EnvironmentDto environmentDto = createEnvironmentDto();
        Event<EnvironmentDto> environmentDtoEvent = Event.wrap(environmentDto);
        AwsNetwork network = createNetwork();
        Environment environment = createEnvironment(network);
        Optional<Environment> environmentOptional = Optional.of(environment);

        Map<String, CloudSubnet> subnets = createDefaultPrivateSubnets();
        subnets.putAll(createDefaultPublicSubnets());

        when(environmentService.findEnvironmentById(any())).thenReturn(environmentOptional);
        when(cloudNetworkService.retrieveSubnetMetadata(any(EnvironmentDto.class), any())).thenReturn(subnets);
        when(cloudNetworkService.retrieveEndpointGatewaySubnetMetadata(any(EnvironmentDto.class), any())).thenReturn(Map.of());
        when(environmentResourceService.createAndSetNetwork(any(), any(), any(), any(), any())).thenReturn(network);

        underTest.accept(environmentDtoEvent);
    }

    @Test
    public void testWithEndpointGatewayAndNoPublicSubnetsProvided() {
        EnvironmentDto environmentDto = createEnvironmentDto();
        Event<EnvironmentDto> environmentDtoEvent = Event.wrap(environmentDto);
        AwsNetwork network = createNetwork();
        Environment environment = createEnvironment(network);
        Optional<Environment> environmentOptional = Optional.of(environment);

        Map<String, CloudSubnet> subnets = createDefaultPrivateSubnets();

        when(environmentService.findEnvironmentById(any())).thenReturn(environmentOptional);
        when(cloudNetworkService.retrieveSubnetMetadata(any(EnvironmentDto.class), any())).thenReturn(subnets);
        when(cloudNetworkService.retrieveEndpointGatewaySubnetMetadata(any(EnvironmentDto.class), any())).thenReturn(Map.of());
        when(environmentResourceService.createAndSetNetwork(any(), any(), any(), any(), any())).thenReturn(network);

        underTest.accept(environmentDtoEvent);

        ArgumentCaptor<Event<EnvCreationFailureEvent>> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventBus, times(1)).notify(any(Object.class), eventCaptor.capture());
        Event<EnvCreationFailureEvent> value = eventCaptor.getValue();
        assertTrue(value.getData().getException() instanceof BadRequestException);
        assertTrue(value.getData().getException().getMessage().startsWith(UNMATCHED_AZ_MSG));
    }

    @Test
    public void testWithEndpointGatewayWithMissingPublicSubnets() {
        EnvironmentDto environmentDto = createEnvironmentDto();
        Event<EnvironmentDto> environmentDtoEvent = Event.wrap(environmentDto);
        AwsNetwork network = createNetwork();
        Environment environment = createEnvironment(network);
        Optional<Environment> environmentOptional = Optional.of(environment);

        Map<String, CloudSubnet> subnets = createDefaultPrivateSubnets();
        subnets.put("id3", createPrivateSubnet("id3", "AZ-3"));
        Map<String, CloudSubnet> endpointGatewaySubnets = createDefaultPublicSubnets();

        when(environmentService.findEnvironmentById(any())).thenReturn(environmentOptional);
        when(cloudNetworkService.retrieveSubnetMetadata(any(EnvironmentDto.class), any())).thenReturn(subnets);
        when(cloudNetworkService.retrieveEndpointGatewaySubnetMetadata(any(EnvironmentDto.class), any())).thenReturn(endpointGatewaySubnets);
        when(environmentResourceService.createAndSetNetwork(any(), any(), any(), any(), any())).thenReturn(network);

        underTest.accept(environmentDtoEvent);

        ArgumentCaptor<Event<EnvCreationFailureEvent>> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventBus, times(1)).notify(any(Object.class), eventCaptor.capture());
        Event<EnvCreationFailureEvent> value = eventCaptor.getValue();
        assertTrue(value.getData().getException() instanceof BadRequestException);
        assertTrue(value.getData().getException().getMessage().startsWith(UNMATCHED_AZ_MSG));
    }

    private EnvironmentDto createEnvironmentDto() {
        NetworkDto networkDto = NetworkDto.builder()
            .build();

        EnvironmentDto environmentDto = new EnvironmentDto();
        environmentDto.setId(123L);
        environmentDto.setName("name");
        environmentDto.setNetwork(networkDto);
        return environmentDto;
    }

    private AwsNetwork createNetwork() {
        AwsNetwork network = new AwsNetwork();
        network.setPublicEndpointAccessGateway(PublicEndpointAccessGateway.ENABLED);
        network.setRegistrationType(RegistrationType.EXISTING);
        return network;
    }

    private Environment createEnvironment(BaseNetwork network) {
        Environment environment = new Environment();
        environment.setName("name");
        environment.setAccountId("1234");
        environment.setNetwork(network);
        environment.setCloudPlatform("AWS");
        return environment;
    }

    private CloudSubnet createPrivateSubnet(String id, String aZ) {
        return new CloudSubnet(id, "name", aZ, "cidr", true, false, false, SubnetType.PRIVATE);
    }

    private CloudSubnet createPublicSubnet(String id, String aZ) {
        return new CloudSubnet(id, "name", aZ, "cidr", false, true, true, SubnetType.PUBLIC);
    }

    private Map<String, CloudSubnet> createDefaultPrivateSubnets() {
        Map<String, CloudSubnet> subnets = new HashMap<>();
        subnets.put("id1", createPrivateSubnet("id1", "AZ-1"));
        subnets.put("id2", createPrivateSubnet("id2", "AZ-2"));
        return subnets;
    }

    private Map<String, CloudSubnet> createDefaultPublicSubnets() {
        Map<String, CloudSubnet> subnets = new HashMap<>();
        subnets.put("public-id1", createPublicSubnet("public-id1", "AZ-1"));
        subnets.put("public-id2", createPublicSubnet("public-id2", "AZ-2"));
        return subnets;
    }
}
