package com.sequenceiq.freeipa.service.stack;

import static java.util.function.Predicate.not;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import com.sequenceiq.cloudbreak.logger.MDCBuilder;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.common.Status;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.common.instance.InstanceStatus;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.health.HealthDetailsFreeIpaResponse;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.health.NodeHealthDetails;
import com.sequenceiq.freeipa.client.FreeIpaClient;
import com.sequenceiq.freeipa.client.FreeIpaClientException;
import com.sequenceiq.freeipa.client.FreeIpaHealthCheckClient;
import com.sequenceiq.freeipa.client.FreeIpaHealthCheckClientFactory;
import com.sequenceiq.freeipa.client.RetryableFreeIpaClientException;
import com.sequenceiq.freeipa.client.healthcheckmodel.CheckResult;
import com.sequenceiq.freeipa.client.model.RPCMessage;
import com.sequenceiq.freeipa.client.model.RPCResponse;
import com.sequenceiq.freeipa.entity.InstanceMetaData;
import com.sequenceiq.freeipa.entity.Stack;
import com.sequenceiq.freeipa.service.freeipa.FreeIpaClientFactory;
import com.sequenceiq.freeipa.util.HealthCheckAvailabilityChecker;

@Service
public class FreeIpaHealthDetailsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FreeIpaHealthDetailsService.class);

    private static final String EXTERNAL_COMMAND_OUTPUT = "ExternalCommandOutput";

    private static final String STATUS_OK = "OK";

    private static final int STATUS_GROUP = 2;

    private static final String MESSAGE_UNAVAILABLE = "Message Unavailable";

    private static final Pattern RESULT_PATTERN = Pattern.compile("(ecure port|: TCP) \\([0-9]*\\): (.*)");

    private static final Pattern NEW_NODE_PATTERN = Pattern.compile("Check connection from master to remote replica '(.[^\']*)");

    private static final Set<InstanceStatus> CACHEABLE_INSTANCE_STATUS = Set.of(InstanceStatus.STOPPED, InstanceStatus.FAILED);

    @Inject
    private StackService stackService;

    @Inject
    private FreeIpaClientFactory freeIpaClientFactory;

    @Inject
    private HealthCheckAvailabilityChecker healthCheckAvailabilityChecker;

    @Inject
    private FreeIpaHealthCheckClientFactory freeIpaHealthCheckClientFactory;

    public HealthDetailsFreeIpaResponse getHealthDetails(String environmentCrn, String accountId) {
        Stack stack = stackService.getByEnvironmentCrnAndAccountIdWithLists(environmentCrn, accountId);
        MDCBuilder.buildMdcContext(stack);
        List<InstanceMetaData> instances = stack.getAllInstanceMetaDataList();
        HealthDetailsFreeIpaResponse response = new HealthDetailsFreeIpaResponse();

        for (InstanceMetaData instance: instances) {
            if (shouldRunHealthCheck(instance)) {
                try {
                    if (healthCheckAvailabilityChecker.isCdpFreeIpaHeathAgentAvailable(stack)) {
                        RPCResponse<CheckResult> rpcResponse = freeIpaHealthCheck(stack, instance);
                        parseMessages(rpcResponse, response, instance);
                    } else {
                        RPCResponse<Boolean> rpcResponse = legacyFreeIpaHealthCheck(stack, instance);
                        legacyParseMessages(rpcResponse, response, instance);
                    }
                } catch (FreeIpaClientException e) {
                    addUnreachableResponse(instance, response, e.getLocalizedMessage());
                    LOGGER.error(String.format("Unable to check the health of FreeIPA instance: %s", instance.getInstanceId()), e);
                }
            } else {
                NodeHealthDetails nodeResponse = new NodeHealthDetails();
                response.addNodeHealthDetailsFreeIpaResponses(nodeResponse);
                nodeResponse.setName(instance.getDiscoveryFQDN());
                nodeResponse.setStatus(instance.getInstanceStatus());
                nodeResponse.setInstanceId(instance.getInstanceId());
                nodeResponse.addIssue("Unable to check health as instance is " + instance.getInstanceStatus().name());
            }
        }
        return updateResponse(stack, response);
    }

    private boolean shouldRunHealthCheck(InstanceMetaData instance) {
        return !(instance.isTerminated() ||
                instance.isDeletedOnProvider() ||
                CACHEABLE_INSTANCE_STATUS.contains(instance.getInstanceStatus()));
    }

    private void addUnreachableResponse(InstanceMetaData instance, HealthDetailsFreeIpaResponse response, String issue) {
        NodeHealthDetails nodeResponse = new NodeHealthDetails();
        response.addNodeHealthDetailsFreeIpaResponses(nodeResponse);
        nodeResponse.setName(instance.getDiscoveryFQDN());
        nodeResponse.setStatus(InstanceStatus.UNREACHABLE);
        nodeResponse.setInstanceId(instance.getInstanceId());
        nodeResponse.addIssue(issue);
    }

    private HealthDetailsFreeIpaResponse updateResponse(Stack stack, HealthDetailsFreeIpaResponse response) {
        response.setEnvironmentCrn(stack.getEnvironmentCrn());
        response.setCrn(stack.getResourceCrn());
        response.setName(stack.getName());

        Set<String> notTermiatedStackInstanceIds = stack.getAllInstanceMetaDataList().stream()
                .filter(not(InstanceMetaData::isTerminated))
                .map(InstanceMetaData::getInstanceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<InstanceStatus> nonTerminatedStatuses = response.getNodeHealthDetails().stream()
                .filter(nodeHealthDetails -> notTermiatedStackInstanceIds.contains(nodeHealthDetails.getInstanceId()))
                .map(NodeHealthDetails::getStatus)
                .collect(Collectors.toList());
        if (nonTerminatedStatuses.isEmpty()) {
            LOGGER.debug("FreeIPA is unhealthy because all instances are terminated");
            response.setStatus(Status.UNHEALTHY);
        } else if (!areAllStatusTheSame(nonTerminatedStatuses)) {
            LOGGER.debug("There are different health statuses for FreeIPA so the the overall health is unhealthy");
            response.setStatus(Status.UNHEALTHY);
        } else if (hasMissingStatus(nonTerminatedStatuses, notTermiatedStackInstanceIds)) {
            LOGGER.debug("There are missing health checks for some instances of FreeIPA so the overall health is unhealthy");
            response.setStatus(Status.UNHEALTHY);
        } else {
            response.setStatus(toStatus(nonTerminatedStatuses.get(0)));
        }
        updateResponseWithInstanceIds(response, stack);
        return response;
    }

    private void updateResponseWithInstanceIds(HealthDetailsFreeIpaResponse response, Stack stack) {
        Map<String, String> nameIdMap = getNameIdMap(stack);
        for (NodeHealthDetails node: response.getNodeHealthDetails()) {
            node.setInstanceId(nameIdMap.get(node.getName()));
        }
    }

    private Map<String, String> getNameIdMap(Stack stack) {
        return stack.getInstanceGroups().stream().flatMap(ig -> ig.getInstanceMetaData().stream())
                .filter(im -> Objects.nonNull(im.getDiscoveryFQDN()) && Objects.nonNull(im.getInstanceId()))
                .collect(Collectors.toMap(InstanceMetaData::getDiscoveryFQDN, InstanceMetaData::getInstanceId));
    }

    @Retryable(RetryableFreeIpaClientException.class)
    public RPCResponse<Boolean> checkFreeIpaHealth(Stack stack, InstanceMetaData instance) throws FreeIpaClientException {
        RPCResponse<Boolean> result;
        if (healthCheckAvailabilityChecker.isCdpFreeIpaHeathAgentAvailable(stack)) {
            result = toBooleanRpcResponse(freeIpaHealthCheck(stack, instance));
        } else {
            result = legacyFreeIpaHealthCheck(stack, instance);
        }
        return result;
    }

    private RPCResponse<CheckResult> freeIpaHealthCheck(Stack stack, InstanceMetaData instance) throws FreeIpaClientException {
        try (FreeIpaHealthCheckClient client = freeIpaHealthCheckClientFactory.getClient(stack, instance)) {
            return client.nodeHealth();
        } catch (FreeIpaClientException e) {
            throw new RetryableFreeIpaClientException("Error during healthcheck", e);
        } catch (Exception e) {
            LOGGER.error("FreeIPA health check failed", e);
            throw new RetryableFreeIpaClientException("FreeIPA health check failed", e);
        }
    }

    private RPCResponse<Boolean> legacyFreeIpaHealthCheck(Stack stack, InstanceMetaData instance) throws FreeIpaClientException {
        FreeIpaClient freeIpaClient = freeIpaClientFactory.getFreeIpaClientForStackForLegacyHealthCheck(stack, instance.getDiscoveryFQDN());
        return freeIpaClient.serverConnCheck(freeIpaClient.getHostname(), instance.getDiscoveryFQDN());
    }

    private RPCResponse<Boolean> toBooleanRpcResponse(RPCResponse<CheckResult> nodeHealth) {
        RPCResponse<Boolean> response = new RPCResponse<>();
        response.setSummary(nodeHealth.getSummary());
        response.setResult(isHealthCheckPassing(nodeHealth));
        response.setCount(nodeHealth.getCount());
        response.setTruncated(nodeHealth.getTruncated());
        response.setMessages(nodeHealth.getMessages());
        response.setCompleted(nodeHealth.getCompleted());
        response.setFailed(nodeHealth.getFailed());
        response.setValue(nodeHealth.getValue());
        return response;
    }

    private Status toStatus(InstanceStatus instanceStatus) {
        switch (instanceStatus) {
            case REQUESTED:
                return Status.REQUESTED;
            case CREATED:
                return Status.AVAILABLE;
            case TERMINATED:
                return Status.DELETE_COMPLETED;
            case DELETED_ON_PROVIDER_SIDE:
            case DELETED_BY_PROVIDER:
                return Status.DELETED_ON_PROVIDER_SIDE;
            case STOPPED:
                return Status.STOPPED;
            case REBOOTING:
                return Status.UPDATE_IN_PROGRESS;
            case UNREACHABLE:
                return Status.UNREACHABLE;
            case DELETE_REQUESTED:
                return Status.DELETE_IN_PROGRESS;
            default:
                return Status.UNHEALTHY;
        }
    }

    private boolean areAllStatusTheSame(List<InstanceStatus> response) {
        InstanceStatus first = response.get(0);
        return response.stream().allMatch(Predicate.isEqual(first));
    }

    private boolean hasMissingStatus(List<InstanceStatus> response, Set<String> notTermiatedStackInstanceIds) {
        return response.size() != notTermiatedStackInstanceIds.size();
    }

    private void parseMessages(RPCResponse<CheckResult> rpcResponse, HealthDetailsFreeIpaResponse response, InstanceMetaData instanceMetaData) {
        NodeHealthDetails nodeResponse = new NodeHealthDetails();
        nodeResponse.setName(instanceMetaData.getDiscoveryFQDN());
        nodeResponse.setInstanceId(instanceMetaData.getInstanceId());
        if (isHealthCheckPassing(rpcResponse)) {
            nodeResponse.setStatus(InstanceStatus.CREATED);
        } else {
            nodeResponse.setStatus(InstanceStatus.UNHEALTHY);
            nodeResponse.setIssues(rpcResponse.getMessages().stream().map(RPCMessage::getMessage).collect(Collectors.toList()));
        }
        response.addNodeHealthDetailsFreeIpaResponses(nodeResponse);
    }

    private void legacyParseMessages(RPCResponse<Boolean> rpcResponse, HealthDetailsFreeIpaResponse response, InstanceMetaData instanceMetaData) {
        String precedingMessage = MESSAGE_UNAVAILABLE;
        NodeHealthDetails nodeResponse = null;
        for (RPCMessage message : rpcResponse.getMessages()) {
            Matcher nodeMatcher = NEW_NODE_PATTERN.matcher(message.getMessage());
            if (nodeMatcher.find()) {
                nodeResponse = new NodeHealthDetails();
                response.addNodeHealthDetailsFreeIpaResponses(nodeResponse);
                nodeResponse.setStatus(InstanceStatus.CREATED);
                nodeResponse.setName(instanceMetaData.getDiscoveryFQDN());
                nodeResponse.setInstanceId(instanceMetaData.getInstanceId());
            }
            if (nodeResponse == null) {
                LOGGER.info("No node for message: {}" + message.getMessage());
            } else {
                // When parsing the messages, if there's an error, the error
                // appears in the preceding message.
                if (EXTERNAL_COMMAND_OUTPUT.equals(message.getName())) {
                    Matcher matcher = RESULT_PATTERN.matcher(message.getMessage());
                    if (matcher.find()) {
                        if (!STATUS_OK.equals(matcher.group(STATUS_GROUP))) {
                            nodeResponse.setStatus(InstanceStatus.UNHEALTHY);
                            nodeResponse.addIssue(precedingMessage);
                        }
                    }
                    precedingMessage = message.getMessage();
                }
            }
        }
    }

    private boolean isHealthCheckPassing(RPCResponse<CheckResult> rpcResponse) {
        return rpcResponse.getMessages().stream()
                .map(RPCMessage::getCode)
                .filter(Objects::nonNull)
                .map(Response.Status.Family::familyOf)
                .map(f -> f.equals(Response.Status.Family.SUCCESSFUL))
                .reduce(Boolean::logicalAnd)
                .orElse(Boolean.FALSE);
    }
}
