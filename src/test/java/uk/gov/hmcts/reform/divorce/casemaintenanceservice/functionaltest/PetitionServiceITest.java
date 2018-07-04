package uk.gov.hmcts.reform.divorce.casemaintenanceservice.functionaltest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockClassRule;
import com.github.tomakehurst.wiremock.matching.EqualToPattern;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.divorce.casemaintenanceservice.CaseMaintenanceServiceApplication;
import uk.gov.hmcts.reform.divorce.casemaintenanceservice.domain.model.UserDetails;
import uk.gov.hmcts.reform.divorce.casemaintenanceservice.service.impl.CcdRetrievalServiceImpl;

import java.util.Arrays;
import java.util.Collections;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.hamcrest.CoreMatchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.MediaType.APPLICATION_JSON_UTF8_VALUE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
public class PetitionServiceITest {
    private static final String API_URL = "/casemaintenance/version/1/retrievePetition";
    private static final String CHECK_CCD_PARAM = "checkCcd";
    private static final String IDAM_USER_DETAILS_CONTEXT_PATH = "/details";
    private static final String USER_ID = "1";

    private static final String AWAITING_PAYMENT_STATE =
        (String)ReflectionTestUtils.getField(CcdRetrievalServiceImpl.class, "AWAITING_PAYMENT_STATE");
    private static final String SUBMITTED_PAYMENT_STATE =
        (String)ReflectionTestUtils.getField(CcdRetrievalServiceImpl.class, "SUBMITTED_PAYMENT_STATE");

    private static final String USER_TOKEN = "Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJqdGkiOiIwOTg3NjU0M"
        + "yIsInN1YiI6IjEwMCIsImlhdCI6MTUwODk0MDU3MywiZXhwIjoxNTE5MzAzNDI3LCJkYXRhIjoiY2l0aXplbiIsInR5cGUiOiJBQ0NFU1MiL"
        + "CJpZCI6IjEwMCIsImZvcmVuYW1lIjoiSm9obiIsInN1cm5hbWUiOiJEb2UiLCJkZWZhdWx0LXNlcnZpY2UiOiJEaXZvcmNlIiwibG9hIjoxL"
        + "CJkZWZhdWx0LXVybCI6Imh0dHBzOi8vd3d3Lmdvdi51ayIsImdyb3VwIjoiZGl2b3JjZSJ9.lkNr1vpAP5_Gu97TQa0cRtHu8I-QESzu8kMX"
        + "CJOQrVU";

    @ClassRule
    public static WireMockClassRule idamUserDetailsServer = new WireMockClassRule(4503);

    @Value("${ccd.jurisdictionid}")
    private String jurisdictionId;

    @Value("${ccd.casetype}")
    private String caseType;

    @MockBean
    private AuthTokenGenerator authTokenGenerator;

    @MockBean
    private CoreCaseDataApi coreCaseDataApi;

    @Autowired
    private MockMvc webClient;

    @Test
    public void givenJWTTokenIsNull_whenRetrievePetition_thenReturnBadRequest() throws Exception {
        webClient.perform(get(API_URL)
            .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());
    }

    @Test
    public void givenInvalidUserToken_whenRetrievePetition_thenReturnForbiddenError() throws Exception {
        final String message = "some message";
        stubUserDetailsEndpoint(HttpStatus.FORBIDDEN, new EqualToPattern(USER_TOKEN), message);

        webClient.perform(get(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN)
            .param(CHECK_CCD_PARAM, "true")
            .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isForbidden())
            .andExpect(content().string(containsString(message)));
    }

    @Test
    public void givenCouldNotConnectToAuthService_whenRetrievePetition_thenReturnHttp503() throws Exception {
        final String message = getUserDetails();
        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);

        when(authTokenGenerator.generate()).thenThrow(new HttpClientErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        webClient.perform(get(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN)
            .param(CHECK_CCD_PARAM, "true")
            .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    public void givenNoCaseInCcd_whenRetrievePetition_thenReturnNull() throws Exception {
        final String message = getUserDetails();
        final String serviceToken = "serviceToken";
        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);

        when(authTokenGenerator.generate()).thenReturn(serviceToken);

        when(coreCaseDataApi
            .searchForCitizen(USER_TOKEN, serviceToken, USER_ID, jurisdictionId, caseType, Collections.emptyMap()))
            .thenReturn(null);

        webClient.perform(get(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN)
            .param(CHECK_CCD_PARAM, "true")
            .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().string(""));
    }

    @Test
    public void givenOneSubmittedCaseInCcd_whenRetrievePetition_thenReturnTheCase() throws Exception {
        final String message = getUserDetails();
        final String serviceToken = "serviceToken";
        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);

        final Long caseId = 1L;
        final CaseDetails caseDetails = createCaseDetails(caseId, SUBMITTED_PAYMENT_STATE);

        when(authTokenGenerator.generate()).thenReturn(serviceToken);
        when(coreCaseDataApi
            .searchForCitizen(USER_TOKEN, serviceToken, USER_ID, jurisdictionId, caseType, Collections.emptyMap()))
            .thenReturn(Collections.singletonList(caseDetails));

        webClient.perform(get(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN)
            .param(CHECK_CCD_PARAM, "true")
            .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content()
                .json(ObjectMapperTestUtil
                    .convertObjectToJsonString(caseDetails)));
    }

    @Test
    public void givenMultipleSubmittedCaseInCcd_whenRetrievePetition_thenReturnTheFirstCase() throws Exception {
        final String message = getUserDetails();
        final String serviceToken = "serviceToken";
        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);

        final Long caseId1 = 1L;
        final Long caseId2 = 2L;
        final Long caseId3 = 3L;

        final CaseDetails caseDetails1 = createCaseDetails(caseId1, SUBMITTED_PAYMENT_STATE);
        final CaseDetails caseDetails2 = createCaseDetails(caseId2, SUBMITTED_PAYMENT_STATE);
        final CaseDetails caseDetails3 = createCaseDetails(caseId3, SUBMITTED_PAYMENT_STATE);

        when(authTokenGenerator.generate()).thenReturn(serviceToken);
        when(coreCaseDataApi
            .searchForCitizen(USER_TOKEN, serviceToken, USER_ID, jurisdictionId, caseType, Collections.emptyMap()))
            .thenReturn(Arrays.asList(caseDetails1, caseDetails2, caseDetails3));

        webClient.perform(get(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN)
            .param(CHECK_CCD_PARAM, "true")
            .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content()
                .json(ObjectMapperTestUtil
                    .convertObjectToJsonString(caseDetails1)));
    }

    @Test
    public void givenMultipleSubmittedAndOtherCaseInCcd_whenRetrievePetition_thenReturnFirstSubmittedCase()
        throws Exception {
        final String message = getUserDetails();
        final String serviceToken = "serviceToken";
        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);

        final Long caseId1 = 1L;
        final Long caseId2 = 2L;
        final Long caseId3 = 3L;

        final CaseDetails caseDetails1 = createCaseDetails(caseId1, SUBMITTED_PAYMENT_STATE);
        final CaseDetails caseDetails2 = createCaseDetails(caseId2, SUBMITTED_PAYMENT_STATE);
        final CaseDetails caseDetails3 = createCaseDetails(caseId3, AWAITING_PAYMENT_STATE);

        when(authTokenGenerator.generate()).thenReturn(serviceToken);
        when(coreCaseDataApi
            .searchForCitizen(USER_TOKEN, serviceToken, USER_ID, jurisdictionId, caseType, Collections.emptyMap()))
            .thenReturn(Arrays.asList(caseDetails1, caseDetails2, caseDetails3));

        webClient.perform(get(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN)
            .param(CHECK_CCD_PARAM, "true")
            .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content()
                .json(ObjectMapperTestUtil
                    .convertObjectToJsonString(caseDetails1)));
    }

    @Test
    public void givenOneAwaitingPaymentCaseInCcd_whenRetrievePetition_thenReturnTheCase() throws Exception {
        final String message = getUserDetails();
        final String serviceToken = "serviceToken";
        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);

        final Long caseId = 1L;
        final CaseDetails caseDetails = createCaseDetails(caseId, AWAITING_PAYMENT_STATE);

        when(authTokenGenerator.generate()).thenReturn(serviceToken);
        when(coreCaseDataApi
            .searchForCitizen(USER_TOKEN, serviceToken, USER_ID, jurisdictionId, caseType, Collections.emptyMap()))
            .thenReturn(Collections.singletonList(caseDetails));

        webClient.perform(get(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN)
            .param(CHECK_CCD_PARAM, "true")
            .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content()
                .json(ObjectMapperTestUtil
                    .convertObjectToJsonString(caseDetails)));
    }

    @Test
    public void givenOneAwaitingPaymentAndOtherNonSubmittedCaseInCcd_whenRetrievePetition_thenReturnAwaitingPaymentCase()
        throws Exception {
        final String message = getUserDetails();
        final String serviceToken = "serviceToken";
        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);

        final Long caseId1 = 1L;
        final Long caseId2 = 2L;
        final Long caseId3 = 3L;

        final CaseDetails caseDetails1 = createCaseDetails(caseId1, AWAITING_PAYMENT_STATE);
        final CaseDetails caseDetails2 = createCaseDetails(caseId2, "state1");
        final CaseDetails caseDetails3 = createCaseDetails(caseId3, "state2");

        when(authTokenGenerator.generate()).thenReturn(serviceToken);
        when(coreCaseDataApi
            .searchForCitizen(USER_TOKEN, serviceToken, USER_ID, jurisdictionId, caseType, Collections.emptyMap()))
            .thenReturn(Arrays.asList(caseDetails1, caseDetails2, caseDetails3));

        webClient.perform(get(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN)
            .param(CHECK_CCD_PARAM, "true")
            .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content()
                .json(ObjectMapperTestUtil
                    .convertObjectToJsonString(caseDetails1)));
    }

    @Test
    public void givenMultipleAwaitingPaymentAndOtherNonSubmittedCaseInCcd_whenRetrievePetition_thenReturnError()
        throws Exception {
        final String message = getUserDetails();
        final String serviceToken = "serviceToken";
        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);

        final Long caseId1 = 1L;
        final Long caseId2 = 2L;
        final Long caseId3 = 3L;

        final CaseDetails caseDetails1 = createCaseDetails(caseId1, AWAITING_PAYMENT_STATE);
        final CaseDetails caseDetails2 = createCaseDetails(caseId2, AWAITING_PAYMENT_STATE);
        final CaseDetails caseDetails3 = createCaseDetails(caseId3, "state2");

        when(authTokenGenerator.generate()).thenReturn(serviceToken);
        when(coreCaseDataApi
            .searchForCitizen(USER_TOKEN, serviceToken, USER_ID, jurisdictionId, caseType, Collections.emptyMap()))
            .thenReturn(Arrays.asList(caseDetails1, caseDetails2, caseDetails3));

        webClient.perform(get(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN)
            .param(CHECK_CCD_PARAM, "true")
            .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isMultipleChoices());
    }

    @Test
    public void givenCasesInNotAwaitingPaymentOrNonSubmittedCaseInCcd_whenRetrievePetition_thenReturnNull()
        throws Exception {
        final String message = getUserDetails();
        final String serviceToken = "serviceToken";
        stubUserDetailsEndpoint(HttpStatus.OK, new EqualToPattern(USER_TOKEN), message);

        final Long caseId1 = 1L;
        final Long caseId2 = 2L;
        final Long caseId3 = 3L;

        final CaseDetails caseDetails1 = createCaseDetails(caseId1, "state1");
        final CaseDetails caseDetails2 = createCaseDetails(caseId2, "state2");
        final CaseDetails caseDetails3 = createCaseDetails(caseId3, "state3");

        when(authTokenGenerator.generate()).thenReturn(serviceToken);
        when(coreCaseDataApi
            .searchForCitizen(USER_TOKEN, serviceToken, USER_ID, jurisdictionId, caseType, Collections.emptyMap()))
            .thenReturn(Arrays.asList(caseDetails1, caseDetails2, caseDetails3));

        webClient.perform(get(API_URL)
            .header(HttpHeaders.AUTHORIZATION, USER_TOKEN)
            .param(CHECK_CCD_PARAM, "true")
            .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().string(""));
    }

    private void stubUserDetailsEndpoint(HttpStatus status, StringValuePattern authHeader, String message) {
        idamUserDetailsServer.stubFor(WireMock.get(IDAM_USER_DETAILS_CONTEXT_PATH)
            .withHeader(HttpHeaders.AUTHORIZATION, authHeader)
            .willReturn(aResponse()
                .withStatus(status.value())
                .withHeader(CONTENT_TYPE, APPLICATION_JSON_UTF8_VALUE)
                .withBody(message)));
    }

    private String getUserDetails() throws JsonProcessingException {
        return new ObjectMapper().writeValueAsString(
            UserDetails.builder()
                .id(USER_ID)
                .email("test@test.com")
                .forename("forename")
                .surname("surname")
                .build());
    }

    private CaseDetails createCaseDetails(Long id, String state) {
        return CaseDetails.builder().id(id).state(state).build();
    }
}
