package com.sequenceiq.it.cloudbreak.action.v4.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sequenceiq.it.cloudbreak.SdxClient;
import com.sequenceiq.it.cloudbreak.action.Action;
import com.sequenceiq.it.cloudbreak.context.TestContext;
import com.sequenceiq.it.cloudbreak.dto.util.RenewDatalakeCertificateTestDto;

public class RenewDatalakeCertificateAction implements Action<RenewDatalakeCertificateTestDto, SdxClient> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RenewDatalakeCertificateAction.class);

    @Override
    public RenewDatalakeCertificateTestDto action(TestContext testContext, RenewDatalakeCertificateTestDto renewCertificateTestDto, SdxClient sdxClient)
            throws Exception {
        sdxClient.getSdxClient().sdxEndpoint().renewCertificate(renewCertificateTestDto.getStackCrn());
        return renewCertificateTestDto;
    }
}
