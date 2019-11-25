package com.sequenceiq.environment.environment.flow.deletion.handler.distrox;

import java.util.Collection;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.dyngr.Polling;
import com.dyngr.core.AttemptResult;
import com.dyngr.core.AttemptResults;
import com.sequenceiq.cloudbreak.api.endpoint.v4.common.Status;
import com.sequenceiq.cloudbreak.api.endpoint.v4.stacks.response.StackViewV4Response;
import com.sequenceiq.distrox.api.v1.distrox.endpoint.DistroXV1Endpoint;
import com.sequenceiq.distrox.api.v1.distrox.model.cluster.DistroXMultiDeleteV1Request;
import com.sequenceiq.environment.environment.domain.Environment;
import com.sequenceiq.environment.util.PollingConfig;

@Component
public class DistroXDeleteService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistroXDeleteService.class);

    private final DistroXV1Endpoint distroXV1Endpoint;

    public DistroXDeleteService(DistroXV1Endpoint distroXV1Endpoint) {
        this.distroXV1Endpoint = distroXV1Endpoint;
    }

    public void deleteDistroXClustersForEnvironment(PollingConfig pollingConfig, Environment environment) {
        Collection<StackViewV4Response> list = distroXV1Endpoint.list(null, environment.getResourceCrn()).getResponses();
        LOGGER.info("Found {} Data Hub clusters for environment {}.", list.size(), environment.getName());
        if (list.isEmpty()) {
            LOGGER.info("No Data Hub clusters found for environment.");
        } else {
            waitDistroXClustersDeletion(pollingConfig, environment, list);
            LOGGER.info("Data hub deletion finished.");
        }
    }

    private void waitDistroXClustersDeletion(PollingConfig pollingConfig, Environment environment, Collection<StackViewV4Response> list) {
        DistroXMultiDeleteV1Request multiDeleteRequest = new DistroXMultiDeleteV1Request();
        multiDeleteRequest.setCrns(list.stream().map(StackViewV4Response::getCrn).collect(Collectors.toSet()));
        LOGGER.debug("Calling distroXV1Endpoint.deleteMultiple with crn [{}]", multiDeleteRequest.getNames());
        distroXV1Endpoint.deleteMultiple(multiDeleteRequest, true);

        LOGGER.debug("Starting poller to check all DistroX stacks for environment {} is deleted", environment.getName());
        Polling.stopAfterDelay(pollingConfig.getTimeout(), pollingConfig.getTimeoutTimeUnit())
                .stopIfException(pollingConfig.getStopPollingIfExceptionOccured())
                .waitPeriodly(pollingConfig.getSleepTime(), pollingConfig.getSleepTimeUnit())
                .run(() -> periodicCheckForDeletion(environment));
    }

    private AttemptResult<Object> periodicCheckForDeletion(Environment environment) {
        Collection<StackViewV4Response> actualClusterList = distroXV1Endpoint.list(null, environment.getResourceCrn()).getResponses();
        if (!actualClusterList.isEmpty()) {
            if (actualClusterList.stream().anyMatch(c -> c.getStatus() == Status.DELETE_FAILED)) {
                return AttemptResults.breakFor(new IllegalStateException("Found a cluster with delete failed status."));
            }
            return AttemptResults.justContinue();
        }
        return AttemptResults.finishWith(null);
    }
}