package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.identity.Approvals;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** What another admin approves in support (ADR 0051): a notice to more than 20 people. */
@Configuration(proxyBeanMethods = false)
class SupportGatedActions {

    @Bean
    @ConditionalOnWebApplication
    Approvals.GatedAction sendNotice(AdminNoticesController notices, JsonMapper json) {
        return Approvals.action(
                AdminNoticesController.NOTICE,
                AdminPermission.NOTICES_PUBLISH,
                Approvals.Cooling.MONEY,
                run -> notices.sendNow(
                        json.treeToValue(run.params(), AdminNoticesController.AdminNotice.class), run.actor()));
    }
}
