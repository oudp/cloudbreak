package com.sequenceiq.cloudbreak.cloud.aws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import java.util.Optional;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.elasticloadbalancingv2.model.LoadBalancer;
import com.sequenceiq.cloudbreak.cloud.aws.client.AmazonAutoScalingRetryClient;
import com.sequenceiq.cloudbreak.cloud.aws.client.AmazonCloudFormationRetryClient;
import com.sequenceiq.cloudbreak.cloud.aws.loadbalancer.AwsLoadBalancerScheme;
import com.sequenceiq.cloudbreak.cloud.aws.loadbalancer.converter.LoadBalancerTypeConverter;
import com.sequenceiq.cloudbreak.cloud.aws.util.AwsLifeCycleMapper;
import com.sequenceiq.cloudbreak.cloud.aws.view.AwsCredentialView;
import com.sequenceiq.cloudbreak.cloud.context.AuthenticatedContext;
import com.sequenceiq.cloudbreak.cloud.context.CloudContext;
import com.sequenceiq.cloudbreak.cloud.model.AvailabilityZone;
import com.sequenceiq.cloudbreak.cloud.model.CloudCredential;
import com.sequenceiq.cloudbreak.cloud.model.CloudInstance;
import com.sequenceiq.cloudbreak.cloud.model.CloudInstanceLifeCycle;
import com.sequenceiq.cloudbreak.cloud.model.CloudLoadBalancerMetadata;
import com.sequenceiq.cloudbreak.cloud.model.CloudVmMetaDataStatus;
import com.sequenceiq.cloudbreak.cloud.model.InstanceAuthentication;
import com.sequenceiq.cloudbreak.cloud.model.InstanceStatus;
import com.sequenceiq.cloudbreak.cloud.model.InstanceTemplate;
import com.sequenceiq.cloudbreak.cloud.model.Location;
import com.sequenceiq.cloudbreak.cloud.model.Region;
import com.sequenceiq.cloudbreak.cloud.model.Volume;
import com.sequenceiq.cloudbreak.cloud.scheduler.SyncPollingScheduler;
import com.sequenceiq.common.api.type.LoadBalancerType;

@RunWith(MockitoJUnitRunner.class)
public class AwsMetaDataCollectorTest {

    private static final String USER_ID = "horton@hortonworks.com";

    private static final Long WORKSPACE_ID = 1L;

    private static final CloudInstanceLifeCycle CLOUD_INSTANCE_LIFE_CYCLE = CloudInstanceLifeCycle.SPOT;

    @Mock
    private AwsClient awsClient;

    @Mock
    private CloudFormationStackUtil cloudFormationStackUtil;

    @Mock
    private AwsLifeCycleMapper awsLifeCycleMapper;

    @Mock
    private SyncPollingScheduler<Boolean> syncPollingScheduler;

    @Mock
    private AmazonCloudFormationRetryClient amazonCFClient;

    @Mock
    private AmazonAutoScalingRetryClient amazonASClient;

    @Mock
    private AmazonEC2Client amazonEC2Client;

    @Mock
    private DescribeInstancesRequest describeInstancesRequestGw;

    @Mock
    private DescribeInstancesRequest describeInstancesRequestMaster;

    @Mock
    private DescribeInstancesRequest describeInstancesRequestSlave;

    @Mock
    private DescribeInstancesResult describeInstancesResultGw;

    @Mock
    private DescribeInstancesResult describeInstancesResultMaster;

    @Mock
    private DescribeInstancesResult describeInstancesResultSlave;

    @Mock
    private LoadBalancerTypeConverter loadBalancerTypeConverter;

    @InjectMocks
    private AwsMetadataCollector awsMetadataCollector;

    @Before
    public void setUp() {
        Mockito.reset(amazonEC2Client);
    }

    @Test
    public void collectMigratedExistingOneGroup() {
        List<CloudInstance> vms = new ArrayList<>();
        List<Volume> volumes = new ArrayList<>();
        InstanceAuthentication instanceAuthentication = new InstanceAuthentication("sshkey", "", "cloudbreak");
        vms.add(new CloudInstance("i-1",
                new InstanceTemplate("fla", "cbgateway", 5L, volumes, InstanceStatus.CREATED, null, 0L, "imageId"),
                instanceAuthentication));


        when(awsClient.createCloudFormationRetryClient(any(AwsCredentialView.class), eq("region"))).thenReturn(amazonCFClient);
        when(awsClient.createAutoScalingRetryClient(any(AwsCredentialView.class), eq("region"))).thenReturn(amazonASClient);

        when(cloudFormationStackUtil.getAutoscalingGroupName(any(AuthenticatedContext.class), any(AmazonCloudFormationRetryClient.class), eq("cbgateway")))
                .thenReturn("cbgateway-AAA");

        List<String> gatewayIds = Collections.singletonList("i-1");
        when(cloudFormationStackUtil.getInstanceIds(any(AmazonAutoScalingRetryClient.class), eq("cbgateway-AAA")))
                .thenReturn(gatewayIds);

        when(cloudFormationStackUtil.createDescribeInstancesRequest(eq(gatewayIds))).thenReturn(describeInstancesRequestGw);

        when(amazonEC2Client.describeInstances(describeInstancesRequestGw)).thenReturn(describeInstancesResultGw);

        Instance instance = Mockito.mock(Instance.class);
        when(instance.getInstanceId()).thenReturn("i-1");
        when(instance.getPrivateIpAddress()).thenReturn("privateIp");
        when(instance.getPublicIpAddress()).thenReturn("publicIp");

        List<Reservation> gatewayReservations = Collections.singletonList(getReservation(instance));

        when(describeInstancesResultGw.getReservations()).thenReturn(gatewayReservations);

        AuthenticatedContext ac = authenticatedContext();
        List<CloudVmMetaDataStatus> statuses = awsMetadataCollector.collect(ac, Collections.emptyList(), vms, vms);

        Assert.assertEquals(1L, statuses.size());
        Assert.assertEquals("i-1", statuses.get(0).getCloudVmInstanceStatus().getCloudInstance().getInstanceId());
        Assert.assertEquals("privateIp", statuses.get(0).getMetaData().getPrivateIp());
        Assert.assertEquals("publicIp", statuses.get(0).getMetaData().getPublicIp());
    }

    @Test
    public void collectUnkownIntances() {
        List<CloudInstance> vms = new ArrayList<>();
        List<Volume> volumes = new ArrayList<>();
        InstanceAuthentication instanceAuthentication = new InstanceAuthentication("sshkey", "", "cloudbreak");
        vms.add(new CloudInstance(null,
                new InstanceTemplate("fla", "cbgateway", 5L, volumes, InstanceStatus.CREATED, null, 0L, "imageId"),
                instanceAuthentication));
        vms.add(new CloudInstance(null,
                new InstanceTemplate("fla", "cbgateway", 6L, volumes, InstanceStatus.CREATED, null, 0L, "imageId"),
                instanceAuthentication));
        vms.add(new CloudInstance(null,
                new InstanceTemplate("fla", "cbgateway", 7L, volumes, InstanceStatus.CREATED, null, 0L, "imageId"),
                instanceAuthentication));

        when(awsClient.createCloudFormationRetryClient(any(AwsCredentialView.class), eq("region"))).thenReturn(amazonCFClient);
        when(awsClient.createAutoScalingRetryClient(any(AwsCredentialView.class), eq("region"))).thenReturn(amazonASClient);

        when(cloudFormationStackUtil.getAutoscalingGroupName(any(AuthenticatedContext.class), any(AmazonCloudFormationRetryClient.class), eq("cbgateway")))
                .thenReturn("cbgateway-AAA");

        List<String> gatewayIds = Collections.singletonList("i-1");
        when(cloudFormationStackUtil.getInstanceIds(any(AmazonAutoScalingRetryClient.class), eq("cbgateway-AAA")))
                .thenReturn(gatewayIds);

        when(cloudFormationStackUtil.createDescribeInstancesRequest(eq(gatewayIds))).thenReturn(describeInstancesRequestGw);

        when(amazonEC2Client.describeInstances(describeInstancesRequestGw)).thenReturn(describeInstancesResultGw);

        List<Instance> instances = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Instance instance = Mockito.mock(Instance.class);
            when(instance.getInstanceId()).thenReturn("i-" + i);
            when(instance.getPrivateIpAddress()).thenReturn("privateIp" + i);
            when(instance.getPublicIpAddress()).thenReturn("publicIp" + i);
            instances.add(instance);
        }
        Instance[] instancesArray = new Instance[instances.size()];
        List<Reservation> gatewayReservations = Collections.singletonList(getReservation(instances.toArray(instancesArray)));

        when(describeInstancesResultGw.getReservations()).thenReturn(gatewayReservations);

        AuthenticatedContext ac = authenticatedContext();
        List<CloudVmMetaDataStatus> statuses = awsMetadataCollector.collect(ac, Collections.emptyList(), vms, Collections.emptyList());

        Assert.assertEquals(3L, statuses.size());
        Assert.assertEquals("i-0", statuses.get(0).getCloudVmInstanceStatus().getCloudInstance().getInstanceId());
        Assert.assertEquals("privateIp0", statuses.get(0).getMetaData().getPrivateIp());
        Assert.assertEquals("publicIp0", statuses.get(0).getMetaData().getPublicIp());
        Assert.assertEquals("i-1", statuses.get(1).getCloudVmInstanceStatus().getCloudInstance().getInstanceId());
        Assert.assertEquals("privateIp1", statuses.get(1).getMetaData().getPrivateIp());
        Assert.assertEquals("publicIp1", statuses.get(1).getMetaData().getPublicIp());
        Assert.assertEquals("i-2", statuses.get(2).getCloudVmInstanceStatus().getCloudInstance().getInstanceId());
        Assert.assertEquals("privateIp2", statuses.get(2).getMetaData().getPrivateIp());
        Assert.assertEquals("publicIp2", statuses.get(2).getMetaData().getPublicIp());
    }

    @Test
    public void collectNewAndExistingOne() {
        List<CloudInstance> vms = new ArrayList<>();
        List<Volume> volumes = new ArrayList<>();
        InstanceAuthentication instanceAuthentication = new InstanceAuthentication("sshkey", "", "cloudbreak");
        vms.add(new CloudInstance(null,
                new InstanceTemplate("fla", "cbgateway", 5L, volumes, InstanceStatus.CREATED, null, 0L, "imageId"),
                instanceAuthentication));
        vms.add(new CloudInstance("i-1",
                new InstanceTemplate("fla", "cbgateway", 5L, volumes, InstanceStatus.CREATED, null, 0L, "imageId"),
                instanceAuthentication));


        when(awsClient.createCloudFormationRetryClient(any(AwsCredentialView.class), eq("region"))).thenReturn(amazonCFClient);
        when(awsClient.createAutoScalingRetryClient(any(AwsCredentialView.class), eq("region"))).thenReturn(amazonASClient);

        when(cloudFormationStackUtil.getAutoscalingGroupName(any(AuthenticatedContext.class), any(AmazonCloudFormationRetryClient.class), eq("cbgateway")))
                .thenReturn("cbgateway-AAA");

        List<String> gatewayIds = Arrays.asList("i-1", "i-2");
        when(cloudFormationStackUtil.getInstanceIds(any(AmazonAutoScalingRetryClient.class), eq("cbgateway-AAA")))
                .thenReturn(gatewayIds);

        when(cloudFormationStackUtil.createDescribeInstancesRequest(eq(gatewayIds))).thenReturn(describeInstancesRequestGw);

        when(amazonEC2Client.describeInstances(describeInstancesRequestGw)).thenReturn(describeInstancesResultGw);

        Mockito.when(awsLifeCycleMapper.getLifeCycle(any())).thenReturn(CLOUD_INSTANCE_LIFE_CYCLE);

        Instance instance1 = Mockito.mock(Instance.class);
        when(instance1.getInstanceId()).thenReturn("i-1");
        when(instance1.getPrivateIpAddress()).thenReturn("privateIp1");
        when(instance1.getPublicIpAddress()).thenReturn("publicIp1");

        Instance instance2 = Mockito.mock(Instance.class);
        when(instance2.getInstanceId()).thenReturn("i-2");
        when(instance2.getPrivateIpAddress()).thenReturn("privateIp2");
        when(instance2.getPublicIpAddress()).thenReturn("publicIp2");

        List<Reservation> gatewayReservations = Collections.singletonList(getReservation(instance1, instance2));

        when(describeInstancesResultGw.getReservations()).thenReturn(gatewayReservations);

        AuthenticatedContext ac = authenticatedContext();
        List<CloudVmMetaDataStatus> statuses = awsMetadataCollector.collect(ac, Collections.emptyList(), vms, vms);

        Assert.assertEquals(2L, statuses.size());
        Assert.assertTrue(statuses.stream().anyMatch(predicate -> "i-1".equals(predicate.getCloudVmInstanceStatus().getCloudInstance().getInstanceId())));
        Assert.assertTrue(statuses.stream().anyMatch(predicate -> "privateIp1".equals(predicate.getMetaData().getPrivateIp())));
        Assert.assertTrue(statuses.stream().anyMatch(predicate -> "publicIp1".equals(predicate.getMetaData().getPublicIp())));

        Assert.assertTrue(statuses.stream().anyMatch(predicate -> "i-2".equals(predicate.getCloudVmInstanceStatus().getCloudInstance().getInstanceId())));
        Assert.assertTrue(statuses.stream().anyMatch(predicate -> "privateIp2".equals(predicate.getMetaData().getPrivateIp())));
        Assert.assertTrue(statuses.stream().anyMatch(predicate -> "publicIp2".equals(predicate.getMetaData().getPublicIp())));

        Assert.assertTrue(statuses.stream().allMatch(predicate -> CLOUD_INSTANCE_LIFE_CYCLE.equals(predicate.getMetaData().getLifeCycle())));
    }

    @Test
    public void collectNewNodes() {
        List<CloudInstance> everyVms = new ArrayList<>();
        List<CloudInstance> newVms = new ArrayList<>();
        List<Volume> volumes = new ArrayList<>();
        InstanceAuthentication instanceAuthentication = new InstanceAuthentication("sshkey", "", "cloudbreak");
        CloudInstance cloudInstance1 = new CloudInstance(null,
                new InstanceTemplate("fla", "cbgateway", 5L, volumes, InstanceStatus.CREATED, null, 0L, "imageId"),
                instanceAuthentication);
        everyVms.add(cloudInstance1);
        newVms.add(cloudInstance1);

        everyVms.add(new CloudInstance("i-1",
                new InstanceTemplate("fla", "cbgateway", 5L, volumes, InstanceStatus.CREATED, null, 0L, "imageId"),
                instanceAuthentication));


        when(awsClient.createCloudFormationRetryClient(any(AwsCredentialView.class), eq("region"))).thenReturn(amazonCFClient);
        when(awsClient.createAutoScalingRetryClient(any(AwsCredentialView.class), eq("region"))).thenReturn(amazonASClient);

        when(cloudFormationStackUtil.getAutoscalingGroupName(any(AuthenticatedContext.class), any(AmazonCloudFormationRetryClient.class), eq("cbgateway")))
                .thenReturn("cbgateway-AAA");

        List<String> gatewayIds = Arrays.asList("i-1", "i-2");
        when(cloudFormationStackUtil.getInstanceIds(any(AmazonAutoScalingRetryClient.class), eq("cbgateway-AAA")))
                .thenReturn(gatewayIds);

        when(cloudFormationStackUtil.createDescribeInstancesRequest(eq(gatewayIds))).thenReturn(describeInstancesRequestGw);

        when(amazonEC2Client.describeInstances(describeInstancesRequestGw)).thenReturn(describeInstancesResultGw);

        Instance instance1 = Mockito.mock(Instance.class);
        when(instance1.getInstanceId()).thenReturn("i-1");

        Instance instance2 = Mockito.mock(Instance.class);
        when(instance2.getInstanceId()).thenReturn("i-2");
        when(instance2.getPrivateIpAddress()).thenReturn("privateIp2");
        when(instance2.getPublicIpAddress()).thenReturn("publicIp2");

        List<Reservation> gatewayReservations = Collections.singletonList(getReservation(instance1, instance2));

        when(describeInstancesResultGw.getReservations()).thenReturn(gatewayReservations);

        AuthenticatedContext ac = authenticatedContext();
        List<CloudVmMetaDataStatus> statuses = awsMetadataCollector.collect(ac, Collections.emptyList(), newVms, everyVms);

        Assert.assertEquals(1L, statuses.size());
        Assert.assertTrue(statuses.stream().anyMatch(predicate -> "i-2".equals(predicate.getCloudVmInstanceStatus().getCloudInstance().getInstanceId())));
        Assert.assertTrue(statuses.stream().anyMatch(predicate -> "privateIp2".equals(predicate.getMetaData().getPrivateIp())));
        Assert.assertTrue(statuses.stream().anyMatch(predicate -> "publicIp2".equals(predicate.getMetaData().getPublicIp())));
    }

    @Test
    public void testCollectLoadBalancers() {
        setupMethodsForLoadBalancer();

        AuthenticatedContext ac = authenticatedContext();
        List<CloudLoadBalancerMetadata> metadata = awsMetadataCollector.collectLoadBalancer(ac,
            List.of(LoadBalancerType.DEFAULT_GATEWAY, LoadBalancerType.ENDPOINT_ACCESS_GATEWAY));

        Assert.assertEquals(2, metadata.size());
        Optional<CloudLoadBalancerMetadata> internalMetadata = metadata.stream()
            .filter(m -> m.getType() == LoadBalancerType.DEFAULT_GATEWAY)
            .findFirst();
        Assert.assertTrue(internalMetadata.isPresent());
        Assert.assertEquals("internal-lb.aws.dns", internalMetadata.get().getCloudDns());
        Assert.assertEquals("zone1", internalMetadata.get().getHostedZoneId());
        Optional<CloudLoadBalancerMetadata> externalMetadata = metadata.stream()
            .filter(m -> m.getType() == LoadBalancerType.ENDPOINT_ACCESS_GATEWAY)
            .findFirst();
        Assert.assertTrue(externalMetadata.isPresent());
        Assert.assertEquals("external-lb.aws.dns", externalMetadata.get().getCloudDns());
        Assert.assertEquals("zone2", externalMetadata.get().getHostedZoneId());
    }

    @Test
    public void testCollectLoadBalancerOnlyDefaultGateway() {
        setupMethodsForLoadBalancer();

        AuthenticatedContext ac = authenticatedContext();
        List<CloudLoadBalancerMetadata> metadata = awsMetadataCollector.collectLoadBalancer(ac,
            List.of(LoadBalancerType.DEFAULT_GATEWAY));

        Assert.assertEquals(1, metadata.size());
        Optional<CloudLoadBalancerMetadata> internalMetadata = metadata.stream()
            .filter(m -> m.getType() == LoadBalancerType.DEFAULT_GATEWAY)
            .findFirst();
        Assert.assertTrue(internalMetadata.isPresent());
        Assert.assertEquals("internal-lb.aws.dns", internalMetadata.get().getCloudDns());
        Assert.assertEquals("zone1", internalMetadata.get().getHostedZoneId());
    }

    @Test
    public void testCollectLoadBalancerOnlyEndpointAccessGateway() {
        setupMethodsForLoadBalancer();

        AuthenticatedContext ac = authenticatedContext();
        List<CloudLoadBalancerMetadata> metadata = awsMetadataCollector.collectLoadBalancer(ac,
            List.of(LoadBalancerType.ENDPOINT_ACCESS_GATEWAY));

        Assert.assertEquals(1, metadata.size());
        Optional<CloudLoadBalancerMetadata> externalMetadata = metadata.stream()
            .filter(m -> m.getType() == LoadBalancerType.ENDPOINT_ACCESS_GATEWAY)
            .findFirst();
        Assert.assertTrue(externalMetadata.isPresent());
        Assert.assertEquals("external-lb.aws.dns", externalMetadata.get().getCloudDns());
        Assert.assertEquals("zone2", externalMetadata.get().getHostedZoneId());
    }

    private Reservation getReservation(Instance... instance) {
        List<Instance> instances = Arrays.asList(instance);
        Reservation r = new Reservation();
        r.setInstances(instances);
        return r;
    }

    private AuthenticatedContext authenticatedContext() {
        Location location = Location.location(Region.region("region"), AvailabilityZone.availabilityZone("az"));
        CloudContext cloudContext = new CloudContext(5L, "name", "crn", "platform", "variant",
                location, USER_ID, WORKSPACE_ID);
        CloudCredential credential = new CloudCredential("crn", null, null, false);
        AuthenticatedContext authenticatedContext = new AuthenticatedContext(cloudContext, credential);
        authenticatedContext.putParameter(AmazonEC2Client.class, amazonEC2Client);
        return authenticatedContext;
    }

    private void setupMethodsForLoadBalancer() {
        LoadBalancer internalLoadBalancer = new LoadBalancer()
            .withDNSName("internal-lb.aws.dns")
            .withCanonicalHostedZoneId("zone1");
        LoadBalancer externalLoadBalancer = new LoadBalancer()
            .withDNSName("external-lb.aws.dns")
            .withCanonicalHostedZoneId("zone2");

        when(cloudFormationStackUtil.getLoadBalancerByLogicalId(any(), eq("LoadBalancerInternal"))).thenReturn(internalLoadBalancer);
        when(cloudFormationStackUtil.getLoadBalancerByLogicalId(any(), eq("LoadBalancerExternal"))).thenReturn(externalLoadBalancer);
        when(loadBalancerTypeConverter.convert(eq(LoadBalancerType.DEFAULT_GATEWAY))).thenReturn(AwsLoadBalancerScheme.INTERNAL);
        when(loadBalancerTypeConverter.convert(eq(LoadBalancerType.ENDPOINT_ACCESS_GATEWAY))).thenReturn(AwsLoadBalancerScheme.INTERNET_FACING);
    }
}
