package com.xetax.crm.auth.config;

import org.modelmapper.ModelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The ported auth code maps entity ↔ dto with ModelMapper (the rest of the CRM
 * uses MapStruct); kept as-is so the auth flow stays byte-for-byte compatible.
 */
@Configuration
public class ModelMapperConfig {
    @Bean
    public ModelMapper modelMapper() {
        return new ModelMapper();
    }
}
