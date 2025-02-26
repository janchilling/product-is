/*
 * Copyright (c) 2021, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.identity.integration.test.oauth2;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.Lookup;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.cookie.CookieSpecProvider;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.cookie.RFC6265CookieSpecProvider;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.carbon.automation.engine.context.TestUserMode;
import org.wso2.identity.integration.test.rest.api.server.application.management.v1.model.ApplicationResponseModel;
import org.wso2.identity.integration.test.rest.api.server.application.management.v1.model.OpenIDConnectConfiguration;
import org.wso2.identity.integration.test.util.Utils;
import org.wso2.identity.integration.test.utils.CommonConstants;
import org.wso2.identity.integration.test.utils.DataExtractUtil;
import org.wso2.identity.integration.test.utils.OAuth2Constant;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.core.Is.is;

/**
 * This test class is used to check the behaviour of token revocation when all the user sessions are terminated via
 * Session management REST API. So when all the sessions are terminated via REST API, the all the respective tokens
 * should be revoked.
 */
public class OAuth2TokenRevocationWithMultipleSessionTerminationTestCase extends OAuth2ServiceAbstractIntegrationTest {

    private String accessTokenInFirstSession;
    private String accessTokenInSecondSession;
    private String consumerKey;
    private String consumerSecret;
    private String sessionDataKeyConsent;
    private String sessionDataKey;
    private Lookup<CookieSpecProvider> cookieSpecRegistry;
    private RequestConfig requestConfig;
    private  HttpClient httpClientForFirstSession;
    private  HttpClient httpClientForSecondSession;
    private static final String SESSION_API_ENDPOINT = "https://localhost:9853/api/users/v1/me/sessions";
    private String applicationId;

    @BeforeClass(alwaysRun = true)
    public void testInit() throws Exception {

        super.init();
        setSystemproperties();
        super.init(TestUserMode.SUPER_TENANT_USER);

        cookieSpecRegistry = RegistryBuilder.<CookieSpecProvider>create()
                .register(CookieSpecs.DEFAULT, new RFC6265CookieSpecProvider())
                .build();
        requestConfig = RequestConfig.custom()
                .setCookieSpec(CookieSpecs.DEFAULT)
                .build();
        httpClientForFirstSession = HttpClientBuilder.create()
                .setDefaultCookieStore(new BasicCookieStore())
                .setDefaultRequestConfig(requestConfig)
                .setDefaultCookieSpecRegistry(cookieSpecRegistry)
                .build();
        httpClientForSecondSession = HttpClientBuilder.create()
                .setDefaultCookieStore(new BasicCookieStore())
                .setDefaultRequestConfig(requestConfig)
                .setDefaultCookieSpecRegistry(cookieSpecRegistry)
                .build();
    }

    @AfterClass(alwaysRun = true)
    public void testConclude() throws Exception {

        deleteApp(applicationId);
        restClient.closeHttpClient();
    }

    @Test(groups = "wso2.is", description = "Create OAuth2 application")
    public void testCreateApplication() throws Exception {

        ApplicationResponseModel application = addApplication();
        Assert.assertNotNull(application, "OAuth App creation failed.");
        applicationId = application.getId();
        OpenIDConnectConfiguration oidcConfig = getOIDCInboundDetailsOfApplication(application.getId());

        consumerKey = oidcConfig.getClientId();
        Assert.assertNotNull(consumerKey, "Application creation failed.");
        consumerSecret = oidcConfig.getClientSecret();
    }

    @Test(groups = "wso2.is", dependsOnMethods = {"testCreateApplication"},
            description = "Test login using openId connect authorization code flow")
    public void testMultipleOIDCLogins() throws Exception {

        testLoginToFirstSession();
        testLoginToSecondSession();
    }

    private void testLoginToFirstSession() throws Exception {

        initiateAuthorizationRequest(httpClientForFirstSession, OAuth2Constant.OAUTH2_SCOPE_OPENID + " " + "random");
        authenticateUser(httpClientForFirstSession);
        String authorizationCode = performConsentApproval(httpClientForFirstSession);
        accessTokenInFirstSession = generateAuthzCodeAccessToken(authorizationCode, httpClientForFirstSession);
        introspectActiveAccessToken(accessTokenInFirstSession, httpClientForFirstSession);
    }

    private void testLoginToSecondSession() throws Exception {

        initiateAuthorizationRequest(httpClientForSecondSession, OAuth2Constant.OAUTH2_SCOPE_OPENID
                + " " + "internal_login");
        authenticateUser(httpClientForSecondSession);
        String authzCode = performConsentApproval(httpClientForSecondSession);
        accessTokenInSecondSession = generateAuthzCodeAccessToken(authzCode, httpClientForSecondSession);
        introspectActiveAccessToken(accessTokenInSecondSession, httpClientForSecondSession);

    }

    @Test(groups = "wso2.is", dependsOnMethods = {"testMultipleOIDCLogins"}, description = "Get all User sessions " +
            "using session management REST API")
    public void testGetUserSessions() {

        Response response = getResponseOfGet(SESSION_API_ENDPOINT);
        response.then()
                .assertThat()
                .statusCode(HttpStatus.SC_OK)
                .log().ifValidationFails()
                .body("size()", notNullValue())
                .body("userId", notNullValue())
                .body("sessions", notNullValue())
                .body("sessions.applications", notNullValue())
                .body("sessions.applications.subject", notNullValue())
                .body("sessions.applications.appName[0]", notNullValue())
                .body("sessions.applications.appId", notNullValue())
                .body("sessions.userAgent", notNullValue())
                .body("sessions.ip", notNullValue())
                .body("sessions.loginTime", notNullValue())
                .body("sessions.lastAccessTime", notNullValue())
                .body("sessions.id", notNullValue());
        List<String> sessionIdsList = response.jsonPath().getList("sessions.id");
        Assert.assertEquals(sessionIdsList.size(), 2);
    }

    @Test(groups = "wso2.is", dependsOnMethods = {"testGetUserSessions"}, description = "Terminate all the User " +
            "sessions using session management REST API")
    public void testDeleteUserSessions() {

        // Delete all sessions using session management api.
        getResponseOfDelete(SESSION_API_ENDPOINT).then()
                .log().ifValidationFails()
                .assertThat()
                .statusCode(HttpStatus.SC_NO_CONTENT);
        // Get all sessions.
        getResponseOfGet(SESSION_API_ENDPOINT)
                .then()
                .log().ifValidationFails()
                .assertThat()
                .statusCode(HttpStatus.SC_OK)
                .body("size()", is(0));
    }

    @Test(groups = "wso2.is", dependsOnMethods = {"testDeleteUserSessions"}, description = " The expected behaviour " +
            "is, When all the sessions are terminated via REST API, the mapped accesstokens should be revoked")
    public void testTokensRevocationSessionsAreTerminated() throws Exception {

        JSONObject object1 = testIntrospectionEndpoint(accessTokenInFirstSession, httpClientForFirstSession);
        Assert.assertEquals(object1.get("active"), false);

        JSONObject object2 = testIntrospectionEndpoint(accessTokenInSecondSession, httpClientForSecondSession);
        Assert.assertEquals(object2.get("active"), false);
    }

    /**
     * Build post request and return json response object.
     *
     * @param endpoint       Endpoint.
     * @param postParameters postParameters.
     * @param key            Basic authentication key.
     * @param secret         Basic authentication secret.
     * @return JSON object of the response.
     * @throws Exception Exception
     */
    private JSONObject responseObject(HttpClient client, String endpoint, List<NameValuePair> postParameters,
                                      String key, String secret) throws Exception {

        HttpPost httpPost = new HttpPost(endpoint);
        httpPost.setHeader("Authorization", "Basic " + getBase64EncodedString(key, secret));
        httpPost.setHeader("Content-Type", "application/x-www-form-urlencoded");
        httpPost.setEntity(new UrlEncodedFormEntity(postParameters));
        HttpResponse response = client.execute(httpPost);
        String responseString = EntityUtils.toString(response.getEntity(), "UTF-8");
        EntityUtils.consume(response.getEntity());
        JSONParser parser = new JSONParser();
        JSONObject json = (JSONObject) parser.parse(responseString);
        if (json == null) {
            throw new Exception("Error occurred while getting the response.");
        }
        return json;
    }


    private void initiateAuthorizationRequest(HttpClient client, String scope) throws IOException {

        List<NameValuePair> urlParameters = getOIDCInitiationRequestParams(scope);
        HttpResponse response = sendPostRequestWithParameters(client, urlParameters,
                OAuth2Constant.AUTHORIZED_USER_URL);
        Assert.assertNotNull(response, "Authorization response is null");

        Header locationHeader = response.getFirstHeader(OAuth2Constant.HTTP_RESPONSE_HEADER_LOCATION);
        Assert.assertNotNull(locationHeader, "Authorization response header is null.");
        EntityUtils.consume(response.getEntity());
        response = sendGetRequest(client, locationHeader.getValue());
        sessionDataKey = Utils.extractDataFromResponse(response, CommonConstants.SESSION_DATA_KEY, 1);
        EntityUtils.consume(response.getEntity());
    }

    private void authenticateUser(HttpClient client) throws IOException {

        // Pass user credentials to commonauth endpoint and authenticate the user.
        HttpResponse response = sendLoginPost(client, sessionDataKey);
        Assert.assertNotNull(response, "OIDC login request response is null.");
        Header locationHeader = response.getFirstHeader(OAuth2Constant.HTTP_RESPONSE_HEADER_LOCATION);
        Assert.assertNotNull(locationHeader, "OIDC login response header is null.");
        EntityUtils.consume(response.getEntity());
        // Get the sessionDatakeyConsent from the redirection after authenticating the user.
        response = sendGetRequest(client, locationHeader.getValue());
        Map<String, Integer> keyPositionMap = new HashMap<>(1);
        keyPositionMap.put("name=\"sessionDataKeyConsent\"", 1);
        List<DataExtractUtil.KeyValue> keyValues = DataExtractUtil.extractSessionConsentDataFromResponse(response,
                keyPositionMap);
        Assert.assertNotNull(keyValues, "SessionDataKeyConsent keyValues map is null.");
        sessionDataKeyConsent = keyValues.get(0).getValue();
        Assert.assertNotNull(sessionDataKeyConsent, "sessionDataKeyConsent is null.");
        EntityUtils.consume(response.getEntity());
    }


    private String performConsentApproval(HttpClient client) throws IOException {

        HttpResponse response = sendApprovalPostWithConsent(client, sessionDataKeyConsent, null);
        Assert.assertNotNull(response, "OIDC consent approval request response is null.");
        Header locationHeader = response.getFirstHeader(OAuth2Constant.HTTP_RESPONSE_HEADER_LOCATION);
        Assert.assertNotNull(locationHeader, "OIDC consent approval request location header is null.");
        EntityUtils.consume(response.getEntity());
        // Get authorization code flow.
        response = sendPostRequest(client, locationHeader.getValue());
        Map<String, Integer> keyPositionMap = new HashMap<>(1);
        keyPositionMap.put("Authorization Code", 1);
        List<DataExtractUtil.KeyValue> keyValues = DataExtractUtil.extractTableRowDataFromResponse(response,
                keyPositionMap);
        Assert.assertNotNull(keyValues, "Authorization code not received.");
        String authzCode = keyValues.get(0).getValue();
        Assert.assertNotNull(authzCode, "Authorization code not received.");
        EntityUtils.consume(response.getEntity());
        return authzCode;
    }

    private String generateAuthzCodeAccessToken(String authorizationCode, HttpClient client) throws Exception {

        List<NameValuePair> urlParameters = new ArrayList<>();
        urlParameters.add(new BasicNameValuePair(OAuth2Constant.GRANT_TYPE_NAME,
                OAuth2Constant.OAUTH2_GRANT_TYPE_AUTHORIZATION_CODE));
        urlParameters.add(new BasicNameValuePair(OAuth2Constant.OAUTH2_REDIRECT_URI, OAuth2Constant.CALLBACK_URL));
        urlParameters.add(new BasicNameValuePair(OAuth2Constant.AUTHORIZATION_CODE_NAME, authorizationCode));
        JSONObject jsonResponse = responseObject(client, OAuth2Constant.ACCESS_TOKEN_ENDPOINT, urlParameters,
                consumerKey, consumerSecret);
        Assert.assertNotNull(jsonResponse.get(OAuth2Constant.ACCESS_TOKEN), "Access token is null.");
        Assert.assertNotNull(jsonResponse.get(OAuth2Constant.REFRESH_TOKEN), "Refresh token is null.");
        return (String) jsonResponse.get(OAuth2Constant.ACCESS_TOKEN);
    }

    private List<NameValuePair> getOIDCInitiationRequestParams(String scope) {

        List<NameValuePair> urlParameters = new ArrayList<>();
        urlParameters.add(new BasicNameValuePair("grantType", OAuth2Constant.OAUTH2_GRANT_TYPE_CODE));
        urlParameters.add(new BasicNameValuePair("consumerKey", consumerKey));
        urlParameters.add(new BasicNameValuePair("callbackurl", OAuth2Constant.CALLBACK_URL));
        urlParameters.add(new BasicNameValuePair("authorizeEndpoint", OAuth2Constant.APPROVAL_URL));
        urlParameters.add(new BasicNameValuePair("authorize", OAuth2Constant.AUTHORIZE_PARAM));
        urlParameters.add(new BasicNameValuePair("scope", scope));
        return urlParameters;
    }

    private void introspectActiveAccessToken(String accessToken, HttpClient client) throws Exception {

        JSONObject object = testIntrospectionEndpoint(accessToken, client);
        Assert.assertEquals(object.get("active"), true);
    }

    /**
     * Invoke given endpointUri for GET with given body and Basic authentication.
     *
     * @param endpointURI EndpointURI.
     * @return Response Response of the GET request.
     */
    private Response getResponseOfGet(String endpointURI) {

        return given().auth().preemptive().basic(userInfo.getUserName(), userInfo.getPassword())
                .contentType(ContentType.JSON)
                .header(HttpHeaders.ACCEPT, ContentType.JSON)
                .log().ifValidationFails()
                .when()
                .get(endpointURI);
    }

    /**
     * Invoke given endpointUri for DELETE with given body and Basic authentication, authentication credential being
     * the authenticatingUserName and authenticatingCredential.
     *
     * @param endpointURI EndpointURI.
     * @return Response Response of the DELETE request.
     */
    private Response getResponseOfDelete(String endpointURI) {

        return given().auth().preemptive().basic(userInfo.getUserName(), userInfo.getPassword())
                .contentType(ContentType.JSON)
                .header(HttpHeaders.ACCEPT, ContentType.JSON)
                .log().ifValidationFails().log().ifValidationFails()
                .when().log().ifValidationFails()
                .delete(endpointURI);
    }

    /**
     * Get introspection endpoint response by callling introspection endpoint.
     *
     * @return JSONObject
     * @throws Exception Exception
     */
    private JSONObject testIntrospectionEndpoint(String accessToken, HttpClient client) throws Exception {

        List<NameValuePair> urlParameters = new ArrayList<>();
        urlParameters.add(new BasicNameValuePair("token", accessToken));
        return responseObject(client, OAuth2Constant.INTRO_SPEC_ENDPOINT, urlParameters, userInfo.getUserName(),
                userInfo.getPassword());
    }
}