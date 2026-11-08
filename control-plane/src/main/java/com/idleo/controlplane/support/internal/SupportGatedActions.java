package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.identity.Approvals;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * What another admin approves in support (ADR 0051): a notice to more than 20 people, and every broadcast notice (ADR
 * 0056), which reaches many.
 */
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

    @Bean
    @ConditionalOnWebApplication
    Approvals.GatedAction scheduleBroadcast(Broadcasts broadcasts, JsonMapper json) {
        return Approvals.action(
                AdminBroadcastsController.SCHEDULE,
                AdminPermission.BROADCASTS_SEND,
                Approvals.Cooling.MONEY,
                run -> broadcasts.schedule(json.treeToValue(run.params(), Broadcasts.Asked.class), run.actor()));
    }
}
