package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.AdminListing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Images's lists, for the console's exports and saved views (ADR 0052). */
@Configuration(proxyBeanMethods = false)
class ImagesListings {

    @Bean
    AdminListing imagesListing() {
        return AdminImagesController.LIST;
    }
}
