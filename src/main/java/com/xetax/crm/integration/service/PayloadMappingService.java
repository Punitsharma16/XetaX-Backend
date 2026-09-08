package com.xetax.crm.integration.service;

import java.util.Map;

public interface PayloadMappingService {

    Map<String,Object> map(Long integrationId, Map<String,Object> payload);

}
