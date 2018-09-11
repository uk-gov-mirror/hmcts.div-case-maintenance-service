package uk.gov.hmcts.reform.divorce.casemaintenanceservice.functionaltest;

import com.github.tomakehurst.wiremock.matching.EqualToPattern;
import feign.FeignException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.client.HttpClientErrorException;
import uk.gov.hmcts.reform.ccd.client.CaseAccessApi;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.UserId;
import uk.gov.hmcts.reform.divorce.casemaintenanceservice.CaseMaintenanceServiceApplication;
import uk.gov.hmcts.reform.divorce.casemaintenanceservice.service.impl.CcdAccessServiceImpl;

import java.util.Collections;

import static org.hamcrest.CoreMatchers.containsString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringRunner.class)
@ContextConfiguration(classes = CaseMaintenanceServiceApplication.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@PropertySource(value = "classpath:application.yml")
@TestPropertySource(properties = {
    "feign.hystrix.enabled=false",
    "eureka.client.enabled=false"
    })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class LinkRespondentITest extends AuthIdamMockSupport {
    private static final String CASE_ID = "caseId";
    private static final String LETTER_HOLDER_ID = "letterHolderId";
    private static final String LETTER_HOLDER_CASE_FIELD =
        (String)ReflectionTestUtils.getField(CcdAccessServiceImpl.class, "LETTER_HOLDER_CASE_FIELD");

    private static final String API_URL =
        String.format("/casemaintenance/version/1/link-respondent/%s/%s", CASE_ID, LETTER_HOLDER_ID);

    @Value("${ccd.jurisdictionid}")
    private String jurisdictionId;

    @Value("${ccd.casetype}")
    private String caseType;


    @Autowired
    private MockMvc webClient;

    @MockBean
    private CoreCaseDataApi coreCaseDataApi;

    @MockBean
    private CaseAccessApi caseAccessApi;

    @Test
    public void givenAuthTokenIsNull_whenLinkRespondent_thenReturnBadRequest() throws Exception {
        webClient.perform(MockMvcRequestBuilders.post(API_URL))
            .andExpect(status().isBadRequest());
    }

    @Test
    public void givenCouldNotAuthenticateCaseWorker_whenLinkRespondent_thenReturnHttp502() throws Exception {
        final String message = getUserDetails();
        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);
        stubCaseWorkerAuthentication(HttpStatus.BAD_GATEWAY);

        webClient.perform(MockMvcRequestBuilders.post(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN))
            .andExpect(status().isBadGateway());
    }

    @Test
    public void givenCouldNotConnectToAuthService_whenLinkRespondent_thenReturnHttp503() throws Exception {
        final String message = getUserDetails();
        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);
        stubCaseWorkerAuthentication(HttpStatus.OK);

        when(serviceTokenGenerator.generate()).thenThrow(new HttpClientErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        webClient.perform(MockMvcRequestBuilders.post(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    public void givenCouldNotConnectToCcd_whenLinkRespondent_thenReturnHttp503() throws Exception {
        final String message = getUserDetails();
        final String serviceAuthToken = "serviceAuthToken";
        final int feignStatusCode = HttpStatus.BAD_REQUEST.value();
        final FeignException feignException = getMockedFeignException(feignStatusCode);

        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);
        stubCaseWorkerAuthentication(HttpStatus.OK);

        when(serviceTokenGenerator.generate()).thenReturn(serviceAuthToken);
        when(coreCaseDataApi.readForCaseWorker(
            BEARER_CASE_WORKER_TOKEN,
            serviceAuthToken,
            CASE_WORKER_USER_ID,
            jurisdictionId,
            caseType,
            CASE_ID)
        ).thenThrow(feignException);

        webClient.perform(MockMvcRequestBuilders.post(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN))
            .andExpect(status().isBadRequest());
    }


    @Test
    public void givenNoCaseWithId_whenLinkRespondent_thenReturnNotFound() throws Exception {
        final String message = getUserDetails();
        final String serviceAuthToken = "serviceAuthToken";

        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);
        stubCaseWorkerAuthentication(HttpStatus.OK);

        when(serviceTokenGenerator.generate()).thenReturn(serviceAuthToken);
        when(coreCaseDataApi.readForCaseWorker(
            BEARER_CASE_WORKER_TOKEN,
            serviceAuthToken,
            CASE_WORKER_USER_ID,
            jurisdictionId,
            caseType,
            CASE_ID)
        ).thenReturn(null);

        webClient.perform(MockMvcRequestBuilders.post(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN))
            .andExpect(status().isNotFound());
    }

    @Test
    public void givenLetterHolderIdIsNull_whenLinkRespondent_thenReturnNotFound() throws Exception {
        final String message = getUserDetails();
        final String serviceAuthToken = "serviceAuthToken";

        final CaseDetails caseDetails = CaseDetails.builder().build();

        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);
        stubCaseWorkerAuthentication(HttpStatus.OK);

        when(serviceTokenGenerator.generate()).thenReturn(serviceAuthToken);
        when(coreCaseDataApi.readForCaseWorker(
            BEARER_CASE_WORKER_TOKEN,
            serviceAuthToken,
            CASE_WORKER_USER_ID,
            jurisdictionId,
            caseType,
            CASE_ID)
        ).thenReturn(caseDetails);

        webClient.perform(MockMvcRequestBuilders.post(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN))
            .andExpect(status().isNotFound());
    }

    @Test
    public void givenLetterHolderIdDoNotMatch_whenLinkRespondent_thenReturnNotFound() throws Exception {
        final String message = getUserDetails();
        final String serviceAuthToken = "serviceAuthToken";

        final CaseDetails caseDetails = CaseDetails.builder()
            .data(Collections.singletonMap(LETTER_HOLDER_CASE_FIELD, "nonmatchingletterholderid"))
            .build();

        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);
        stubCaseWorkerAuthentication(HttpStatus.OK);

        when(serviceTokenGenerator.generate()).thenReturn(serviceAuthToken);
        when(coreCaseDataApi.readForCaseWorker(
            BEARER_CASE_WORKER_TOKEN,
            serviceAuthToken,
            CASE_WORKER_USER_ID,
            jurisdictionId,
            caseType,
            CASE_ID)
        ).thenReturn(caseDetails);

        webClient.perform(MockMvcRequestBuilders.post(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN))
            .andExpect(status().isNotFound());
    }

    @Test
    public void givenInvalidUserToken_whenLinkRespondent_thenReturnForbiddenError() throws Exception {
        final String message = getUserDetails();
        final String serviceAuthToken = "serviceAuthToken";

        final CaseDetails caseDetails = CaseDetails.builder()
            .data(Collections.singletonMap(LETTER_HOLDER_CASE_FIELD, LETTER_HOLDER_ID))
            .build();

        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);
        stubCaseWorkerAuthentication(HttpStatus.OK);

        when(serviceTokenGenerator.generate()).thenReturn(serviceAuthToken);
        when(coreCaseDataApi.readForCaseWorker(
            BEARER_CASE_WORKER_TOKEN,
            serviceAuthToken,
            CASE_WORKER_USER_ID,
            jurisdictionId,
            caseType,
            CASE_ID)
        ).thenReturn(caseDetails);

        stubUserDetailsEndpoint(HttpStatus.FORBIDDEN, new EqualToPattern(USER_TOKEN), message);

        webClient.perform(MockMvcRequestBuilders.post(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN))
            .andExpect(status().isForbidden())
            .andExpect(content().string(containsString(message)));
    }

    @Test
    public void givenGrantAccessFails_whenLinkRespondent_thenReturnBadRequest() throws Exception {
        final String message = getUserDetails();
        final String serviceAuthToken = "serviceAuthToken";
        final int feignStatusCode = HttpStatus.BAD_REQUEST.value();
        final FeignException feignException = getMockedFeignException(feignStatusCode);

        final CaseDetails caseDetails = CaseDetails.builder()
            .data(Collections.singletonMap(LETTER_HOLDER_CASE_FIELD, LETTER_HOLDER_ID))
            .build();

        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);
        stubCaseWorkerAuthentication(HttpStatus.OK);

        when(serviceTokenGenerator.generate()).thenReturn(serviceAuthToken);
        when(coreCaseDataApi.readForCaseWorker(
            BEARER_CASE_WORKER_TOKEN,
            serviceAuthToken,
            CASE_WORKER_USER_ID,
            jurisdictionId,
            caseType,
            CASE_ID)
        ).thenReturn(caseDetails);

        doThrow(feignException)
            .when(caseAccessApi)
            .grantAccessToCase(
                eq(BEARER_CASE_WORKER_TOKEN),
                eq(serviceAuthToken),
                eq(CASE_WORKER_USER_ID),
                eq(jurisdictionId),
                eq(caseType),
                eq(CASE_ID),
                argThat(new UserIdMatcher(USER_ID))
            );

        webClient.perform(MockMvcRequestBuilders.post(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN))
            .andExpect(status().isBadRequest());
    }

    @Test
    public void givenAllGoesWell_whenLinkRespondent_thenProceedAsExpected() throws Exception {
        final String message = getUserDetails();
        final String serviceAuthToken = "serviceAuthToken";

        final CaseDetails caseDetails = CaseDetails.builder()
            .data(Collections.singletonMap(LETTER_HOLDER_CASE_FIELD, LETTER_HOLDER_ID))
            .build();

        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);
        stubCaseWorkerAuthentication(HttpStatus.OK);

        when(serviceTokenGenerator.generate()).thenReturn(serviceAuthToken);
        when(coreCaseDataApi.readForCaseWorker(
            BEARER_CASE_WORKER_TOKEN,
            serviceAuthToken,
            CASE_WORKER_USER_ID,
            jurisdictionId,
            caseType,
            CASE_ID)
        ).thenReturn(caseDetails);

        doNothing()
            .when(caseAccessApi)
            .grantAccessToCase(
                eq(BEARER_CASE_WORKER_TOKEN),
                eq(serviceAuthToken),
                eq(CASE_WORKER_USER_ID),
                eq(jurisdictionId),
                eq(caseType),
                eq(CASE_ID),
                argThat(new UserIdMatcher(USER_ID))
            );

        webClient.perform(MockMvcRequestBuilders.post(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN))
            .andExpect(status().isOk());
    }

    private static class UserIdMatcher implements ArgumentMatcher<UserId> {
        private final String code;

        UserIdMatcher(String code) {
            this.code = code;
        }

        @Override
        public boolean matches(UserId compare) {
            if (compare == null) {
                return false;
            }

            return code.equals(compare.getId());
        }
    }
}