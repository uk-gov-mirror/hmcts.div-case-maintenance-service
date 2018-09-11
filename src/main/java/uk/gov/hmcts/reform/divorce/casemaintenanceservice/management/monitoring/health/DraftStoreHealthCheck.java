package uk.gov.hmcts.reform.divorce.casemaintenanceservice.management.monitoring.health;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class DraftStoreHealthCheck extends WebServiceHealthCheck {
    @Autowired
    public DraftStoreHealthCheck(HttpEntityFactory httpEntityFactory, RestTemplate restTemplate,
                                 @Value("${draft.store.api.baseurl}") String uri) {
        super(httpEntityFactory, restTemplate, uri);
    }
}