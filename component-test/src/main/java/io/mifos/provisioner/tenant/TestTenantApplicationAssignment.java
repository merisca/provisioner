/*
 * Copyright 2017 The Mifos Initiative.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mifos.provisioner.tenant;

import io.mifos.anubis.api.v1.client.Anubis;
import io.mifos.anubis.api.v1.domain.AllowedOperation;
import io.mifos.anubis.api.v1.domain.ApplicationSignatureSet;
import io.mifos.anubis.api.v1.domain.PermittableEndpoint;
import io.mifos.anubis.api.v1.domain.Signature;
import io.mifos.anubis.provider.SystemRsaKeyProvider;
import io.mifos.anubis.test.v1.SystemSecurityEnvironment;
import io.mifos.anubis.token.TokenSerializationResult;
import io.mifos.core.api.context.AutoSeshat;
import io.mifos.core.api.util.ApiConstants;
import io.mifos.core.api.util.ApiFactory;
import io.mifos.core.lang.AutoTenantContext;
import io.mifos.core.lang.security.RsaKeyPairFactory;
import io.mifos.core.test.env.TestEnvironment;
import io.mifos.identity.api.v1.client.IdentityManager;
import io.mifos.identity.api.v1.domain.CallEndpointSet;
import io.mifos.identity.api.v1.domain.Permission;
import io.mifos.identity.api.v1.domain.PermittableGroup;
import io.mifos.permittedfeignclient.api.v1.client.ApplicationPermissionRequirements;
import io.mifos.permittedfeignclient.api.v1.domain.ApplicationPermission;
import io.mifos.provisioner.ProvisionerCassandraInitializer;
import io.mifos.provisioner.ProvisionerMariaDBInitializer;
import io.mifos.provisioner.api.v1.client.Provisioner;
import io.mifos.provisioner.api.v1.domain.*;
import io.mifos.provisioner.config.ProvisionerConstants;
import io.mifos.provisioner.config.ProvisionerServiceConfig;
import io.mifos.provisioner.internal.service.applications.ApplicationCallContextProvider;
import io.mifos.provisioner.internal.util.TokenProvider;
import org.junit.*;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.netflix.feign.EnableFeignClients;
import org.springframework.cloud.netflix.ribbon.RibbonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

/**
 * @author Myrle Krantz
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public class TestTenantApplicationAssignment {
  private static final String APP_NAME = "provisioner-v1";
  private static final String CLIENT_ID = "sillyRabbit";

  @Configuration
  @EnableFeignClients(basePackages = {"io.mifos.provisioner.api.v1.client"})
  @RibbonClient(name = APP_NAME)
  @Import({ProvisionerServiceConfig.class})
  public static class TestConfiguration {
    public TestConfiguration() {
      super();
    }

    @Bean()
    public Logger logger() {
      return LoggerFactory.getLogger("test-logger");
    }

    @Bean()
    public ApplicationCallContextProvider applicationCallContextProvider(
            final ApiFactory apiFactory,
            final @Qualifier("tokenProviderSpy") TokenProvider tokenProviderSpy)
    {
      return Mockito.spy(new ApplicationCallContextProvider(apiFactory, tokenProviderSpy));
    }

    @Bean(name = "tokenProviderSpy")
    public TokenProvider tokenProviderSpy(final @Qualifier("tokenProvider") TokenProvider tokenProvider)
    {
      return Mockito.spy(tokenProvider);
    }
  }


  private static TestEnvironment testEnvironment = new TestEnvironment(APP_NAME);
  private static ProvisionerMariaDBInitializer mariaDBInitializer = new ProvisionerMariaDBInitializer();
  private static ProvisionerCassandraInitializer cassandraInitializer = new ProvisionerCassandraInitializer();
  private static SystemSecurityEnvironment systemSecurityEnvironment
          = new SystemSecurityEnvironment(testEnvironment.getSystemKeyTimestamp(), testEnvironment.getSystemPublicKey(), testEnvironment.getSystemPrivateKey());

  @ClassRule
  public static TestRule orderClassRules = RuleChain
          .outerRule(testEnvironment)
          .around(mariaDBInitializer)
          .around(cassandraInitializer);

  @Autowired
  private Provisioner provisioner;

  @Autowired
  @Qualifier("tokenProviderSpy")
  protected TokenProvider tokenProviderSpy;

  @Autowired
  protected ApplicationCallContextProvider applicationCallContextProviderSpy;

  @Autowired
  protected SystemRsaKeyProvider systemRsaKeyProvider;

  private AutoSeshat autoSeshat;

  public TestTenantApplicationAssignment() {
    super();
  }

  @BeforeClass
  public static void setup() throws Exception {

    System.setProperty("system.privateKey.modulus", testEnvironment.getSystemPrivateKey().getModulus().toString());
    System.setProperty("system.privateKey.exponent", testEnvironment.getSystemPrivateKey().getPrivateExponent().toString());
    System.setProperty("system.initialclientid", CLIENT_ID);
  }

  @Before
  public void before()
  {
    final AuthenticationResponse authentication = this.provisioner.authenticate(
            CLIENT_ID, ApiConstants.SYSTEM_SU, ProvisionerConstants.INITIAL_PWD);
    autoSeshat = new AutoSeshat(authentication.getToken());
  }

  @After
  public void after() throws InterruptedException {
    this.provisioner.deleteTenant(Fixture.getCompTestTenant().getIdentifier());
    Thread.sleep(500L);
    autoSeshat.close();
  }

  private static class TokenChecker implements Answer<TokenSerializationResult> {
    TokenSerializationResult result = null;

    @Override
    public TokenSerializationResult answer(final InvocationOnMock invocation) throws Throwable {
      result = (TokenSerializationResult) invocation.callRealMethod();
      Assert.assertNotNull(result);
      return result;
    }
  }

  private class VerifyIsisInitializeContext implements Answer<ApplicationSignatureSet> {

    private final String keyTimestamp;
    private final BigInteger modulus;
    private final BigInteger exponent;
    private final String tenantIdentifier;

    private boolean validSecurityContext = false;

    VerifyIsisInitializeContext(
            final String keyTimestamp,
            final BigInteger modulus,
            final BigInteger exponent,
            final String tenantIdentifier) {
      this.keyTimestamp = keyTimestamp;
      this.modulus = modulus;
      this.exponent = exponent;
      this.tenantIdentifier = tenantIdentifier;
    }

    @Override
    public ApplicationSignatureSet answer(final InvocationOnMock invocation) throws Throwable {
      validSecurityContext = systemSecurityEnvironment.isValidSystemSecurityContext("identity", "1", tenantIdentifier);

      final Signature fakeSignature = new Signature();
      fakeSignature.setPublicKeyMod(modulus);
      fakeSignature.setPublicKeyExp(exponent);

      final ApplicationSignatureSet ret = new ApplicationSignatureSet();
      ret.setTimestamp(keyTimestamp);
      ret.setApplicationSignature(fakeSignature);
      ret.setIdentityManagerSignature(fakeSignature);

      return ret;
    }

    boolean isValidSecurityContext() {
      return validSecurityContext;
    }
  }

  private class VerifyAnubisInitializeContext implements Answer<Void> {

    private boolean validSecurityContext = false;
    final private String target;
    private final String tenantIdentifier;

    private VerifyAnubisInitializeContext(final String target, String tenantIdentifier) {
      this.target = target;
      this.tenantIdentifier = tenantIdentifier;
    }

    @Override
    public Void answer(final InvocationOnMock invocation) throws Throwable {
      validSecurityContext = systemSecurityEnvironment.isValidSystemSecurityContext(target, "1", tenantIdentifier);
      return null;
    }

    boolean isValidSecurityContext() {
      return validSecurityContext;
    }
  }

  private class VerifyCreateSignatureSetContext implements Answer<ApplicationSignatureSet> {

    private final RsaKeyPairFactory.KeyPairHolder answer;
    private boolean validSecurityContext = false;
    final private String target;
    private final String tenantIdentifier;

    private VerifyCreateSignatureSetContext(final RsaKeyPairFactory.KeyPairHolder answer, final String target, final String tenantIdentifier) {
      this.answer = answer;
      this.target = target;
      this.tenantIdentifier = tenantIdentifier;
    }

    @Override
    public ApplicationSignatureSet answer(final InvocationOnMock invocation) throws Throwable {
      final String timestamp = invocation.getArgumentAt(0, String.class);
      final Signature identityManagerSignature = invocation.getArgumentAt(1, Signature.class);
      validSecurityContext = systemSecurityEnvironment.isValidSystemSecurityContext(target, "1", tenantIdentifier);

      return new ApplicationSignatureSet(
              timestamp,
              new Signature(answer.getPublicKeyMod(), answer.getPublicKeyExp()),
              identityManagerSignature);
    }

    boolean isValidSecurityContext() {
      return validSecurityContext;
    }
  }


  private class VerifyAnubisPermittablesContext implements Answer<List<PermittableEndpoint>> {

    private boolean validSecurityContext = false;
    private final List<PermittableEndpoint> answer;
    private final String tenantIdentifier;

    private VerifyAnubisPermittablesContext(final List<PermittableEndpoint> answer, final String tenantIdentifier) {
      this.answer = answer;
      this.tenantIdentifier = tenantIdentifier;
    }

    @Override
    public List<PermittableEndpoint> answer(final InvocationOnMock invocation) throws Throwable {
      validSecurityContext = systemSecurityEnvironment.isValidGuestSecurityContext(tenantIdentifier);
      return answer;
    }

    boolean isValidSecurityContext() {
      return validSecurityContext;
    }
  }


  private class VerifyAnputRequiredPermissionsContext implements Answer<List<ApplicationPermission>> {

    private boolean validSecurityContext = false;
    private final List<ApplicationPermission> answer;
    private final String tenantIdentifier;

    private VerifyAnputRequiredPermissionsContext(final List<ApplicationPermission> answer, final String tenantIdentifier) {
      this.answer = answer;
      this.tenantIdentifier = tenantIdentifier;
    }

    @Override
    public List<ApplicationPermission> answer(final InvocationOnMock invocation) throws Throwable {
      validSecurityContext = systemSecurityEnvironment.isValidGuestSecurityContext(tenantIdentifier);
      return answer;
    }

    boolean isValidSecurityContext() {
      return validSecurityContext;
    }
  }

  @Test
  public void testTenantApplicationAssignment() throws InterruptedException {
    //Create io.mifos.provisioner.tenant
    final Tenant tenant = Fixture.getCompTestTenant();
    provisioner.createTenant(tenant);


    //Create identity service application
    final Application identityServiceApp = new Application();
    identityServiceApp.setName("identity-v1");
    identityServiceApp.setHomepage("http://xyz.identity:2020/v1");
    identityServiceApp.setDescription("identity manager");
    identityServiceApp.setVendor("fineract");

    provisioner.createApplication(identityServiceApp);


    //Assign identity service application.  This requires some mocking since we can't actually call initialize in a component test.
    final AssignedApplication identityServiceAssigned = new AssignedApplication();
    identityServiceAssigned.setName("identity-v1");


    final IdentityManager identityServiceMock = Mockito.mock(IdentityManager.class);
    when(applicationCallContextProviderSpy.getApplication(IdentityManager.class, "http://xyz.identity:2020/v1")).thenReturn(identityServiceMock);

    final VerifyIsisInitializeContext verifyInitializeContextAndReturnSignature;
    try (final AutoTenantContext ignored = new AutoTenantContext(tenant.getIdentifier())) {
      verifyInitializeContextAndReturnSignature = new VerifyIsisInitializeContext(
              systemSecurityEnvironment.tenantKeyTimestamp(),
              systemSecurityEnvironment.tenantPublicKey().getModulus(),
              systemSecurityEnvironment.tenantPublicKey().getPublicExponent(), tenant.getIdentifier());
    }
    doAnswer(verifyInitializeContextAndReturnSignature).when(identityServiceMock).initialize(anyString());

    final TokenChecker tokenChecker = new TokenChecker();
    doAnswer(tokenChecker).when(tokenProviderSpy).createToken(tenant.getIdentifier(), "identity-v1", 2L, TimeUnit.MINUTES);

    {
      final IdentityManagerInitialization identityServiceAdminInitialization
              = provisioner.assignIdentityManager(tenant.getIdentifier(), identityServiceAssigned);

      Assert.assertTrue(verifyInitializeContextAndReturnSignature.isValidSecurityContext());
      Assert.assertNotNull(identityServiceAdminInitialization);
      Assert.assertNotNull(identityServiceAdminInitialization.getAdminPassword());
    }

    verify(applicationCallContextProviderSpy, atMost(2)).getApplicationCallContext(tenant.getIdentifier(), "identity-v1");


    //Create horus application.
    final Application officeApp = new Application();
    officeApp.setName("office-v1");
    officeApp.setHomepage("http://xyz.office:2021/v1");
    officeApp.setDescription("organization manager");
    officeApp.setVendor("fineract");

    provisioner.createApplication(officeApp);


    //Assign horus application.
    final AssignedApplication officeAssigned = new AssignedApplication();
    officeAssigned.setName("office-v1");

    final Anubis anubisMock = Mockito.mock(Anubis.class);
    when(applicationCallContextProviderSpy.getApplication(Anubis.class, "http://xyz.office:2021/v1")).thenReturn(anubisMock);

    final ApplicationPermissionRequirements anputMock = Mockito.mock(ApplicationPermissionRequirements.class);
    when(applicationCallContextProviderSpy.getApplication(ApplicationPermissionRequirements.class, "http://xyz.office:2021/v1")).thenReturn(anputMock);

    final RsaKeyPairFactory.KeyPairHolder keysInApplicationSignature = RsaKeyPairFactory.createKeyPair();

    final PermittableEndpoint xxPermittableEndpoint = new PermittableEndpoint("/x/y", "POST", "x");
    final PermittableEndpoint xyPermittableEndpoint = new PermittableEndpoint("/y/z", "POST", "x");
    final PermittableEndpoint xyGetPermittableEndpoint = new PermittableEndpoint("/y/z", "GET", "x");
    final PermittableEndpoint mPermittableEndpoint = new PermittableEndpoint("/m/n", "GET", "m");

    final ApplicationPermission forFooPermission = new ApplicationPermission("forPurposeFoo", new Permission("x", AllowedOperation.ALL));
    final ApplicationPermission forBarPermission = new ApplicationPermission("forPurposeBar", new Permission("m", Collections.singleton(AllowedOperation.READ)));

    final VerifyAnubisInitializeContext verifyAnubisInitializeContext;
    final VerifyCreateSignatureSetContext verifyCreateSignatureSetContext;
    final VerifyAnubisPermittablesContext verifyAnubisPermittablesContext;
    final VerifyAnputRequiredPermissionsContext verifyAnputRequiredPermissionsContext;
    try (final AutoTenantContext ignored = new AutoTenantContext(tenant.getIdentifier())) {
      verifyAnubisInitializeContext = new VerifyAnubisInitializeContext("office", tenant.getIdentifier());
      verifyCreateSignatureSetContext = new VerifyCreateSignatureSetContext(keysInApplicationSignature, "office", tenant.getIdentifier());
      verifyAnubisPermittablesContext = new VerifyAnubisPermittablesContext(Arrays.asList(xxPermittableEndpoint, xxPermittableEndpoint, xyPermittableEndpoint, xyGetPermittableEndpoint, mPermittableEndpoint), tenant.getIdentifier());
      verifyAnputRequiredPermissionsContext = new VerifyAnputRequiredPermissionsContext(Arrays.asList(forFooPermission, forBarPermission), tenant.getIdentifier());
    }
    doAnswer(verifyAnubisInitializeContext).when(anubisMock).initializeResources();
    doAnswer(verifyCreateSignatureSetContext).when(anubisMock).createSignatureSet(anyString(), anyObject());
    doAnswer(verifyAnubisPermittablesContext).when(anubisMock).getPermittableEndpoints();
    doAnswer(verifyAnputRequiredPermissionsContext).when(anputMock).getRequiredPermissions();


    {
      provisioner.assignApplications(tenant.getIdentifier(), Collections.singletonList(officeAssigned));
      Thread.sleep(1500L); //Application assigning is asynchronous and I have no message queue.
    }

    verify(applicationCallContextProviderSpy).getApplicationCallContext(tenant.getIdentifier(), "office-v1");
    verify(applicationCallContextProviderSpy, never()).getApplicationCallContext(eq(Fixture.TENANT_NAME), Mockito.anyString());
    verify(tokenProviderSpy).createToken(tenant.getIdentifier(), "office-v1", 2L, TimeUnit.MINUTES);

    try (final AutoTenantContext ignored = new AutoTenantContext(tenant.getIdentifier())) {
      verify(identityServiceMock).setApplicationSignature(
              "office-v1",
              systemSecurityEnvironment.tenantKeyTimestamp(),
              new Signature(keysInApplicationSignature.getPublicKeyMod(), keysInApplicationSignature.getPublicKeyExp()));
      verify(identityServiceMock).createPermittableGroup(new PermittableGroup("x", Arrays.asList(xxPermittableEndpoint, xyPermittableEndpoint, xyGetPermittableEndpoint)));
      verify(identityServiceMock).createPermittableGroup(new PermittableGroup("m", Collections.singletonList(mPermittableEndpoint)));
      verify(identityServiceMock).createApplicationPermission("office-v1", new Permission("x", AllowedOperation.ALL));
      verify(identityServiceMock).createApplicationPermission("office-v1", new Permission("m", Collections.singleton(AllowedOperation.READ)));
      verify(identityServiceMock).createApplicationCallEndpointSet("office-v1", new CallEndpointSet("forPurposeFoo", Collections.singletonList("x")));
      verify(identityServiceMock).createApplicationCallEndpointSet("office-v1", new CallEndpointSet("forPurposeBar", Collections.singletonList("m")));
    }

    Assert.assertTrue(verifyAnubisInitializeContext.isValidSecurityContext());
    Assert.assertTrue(verifyCreateSignatureSetContext.isValidSecurityContext());
    Assert.assertTrue(verifyAnubisPermittablesContext.isValidSecurityContext());
    Assert.assertTrue(verifyAnputRequiredPermissionsContext.isValidSecurityContext());
  }
}
