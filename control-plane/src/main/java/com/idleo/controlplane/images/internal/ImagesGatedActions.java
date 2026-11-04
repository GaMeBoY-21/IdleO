package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.identity.Approvals;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** What another admin approves in images (ADR 0051). Blocking is never gated; lifting a block is. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class ImagesGatedActions {

    @Bean
    Approvals.GatedAction unblockImage(AdminImagesController images) {
        return Approvals.action(
                AdminImagesController.UNBLOCK,
                AdminPermission.IMAGES_UNBLOCK,
                Approvals.Cooling.ACCESS,
                run -> images.unblockNow(
                        UUID.fromString(run.params().get("id").asString()), run.reason(), run.actor()));
    }
}
