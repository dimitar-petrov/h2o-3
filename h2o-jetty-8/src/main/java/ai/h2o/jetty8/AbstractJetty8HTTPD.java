package ai.h2o.jetty8;

import ai.h2o.jetty8.proxy.ProxyLoginHandler;
import ai.h2o.jetty8.proxy.TransparentProxyServlet;
import ai.h2o.webserver.iface.Credentials;
import ai.h2o.webserver.iface.WebServerConfig;
import org.eclipse.jetty.plus.jaas.JAASLoginService;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.session.HashSessionIdManager;
import org.eclipse.jetty.server.session.HashSessionManager;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import water.ExtensionManager;
import water.H2O;
import water.api.DatasetServlet;
import water.api.NpsBinServlet;
import water.api.PostFileServlet;
import water.api.PutKeyServlet;
import water.api.RequestServer;
import water.server.RequestAuthExtension;
import water.server.ServletUtils;
import water.util.Log;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AbstractJetty8HTTPD {

  protected final WebServerConfig config;

  private String _ip;
  private int _port;

  // Jetty server object.
  private Server _server;

  protected AbstractJetty8HTTPD(WebServerConfig config) {
    this.config = config;
  }

  /**
   * @return URI scheme
   */
  public String getScheme() {
    if (config.jks != null) {
      return "https";
    }
    else {
      return "http";
    }
  }

  /**
   * @return Port number
   */
  public int getPort() {
    return _port;
  }

  /**
   * @return IP address
   */
  public String getIp() {
    return _ip;
  }

  private void setup(String ip, int port) {
    _ip = ip;
    _port = port;
    System.setProperty("org.eclipse.jetty.server.Request.maxFormContentSize", Integer.toString(Integer.MAX_VALUE));
  }

  /**
   * Choose a Port and IP address and start the Jetty server.
   *
   * @throws Exception -
   */
  public void start(String ip, int port) throws Exception {
    setup(ip, port);
    if (config.jks != null) {
      startHttps();
    }
    else {
      startHttp();
    }
  }

  private void createServer(Connector connector) throws Exception {
    _server.setConnectors(new Connector[]{connector});

    if (config.hash_login || config.ldap_login || config.kerberos_login || config.pam_login) {
      // REFER TO http://www.eclipse.org/jetty/documentation/9.1.4.v20140401/embedded-examples.html#embedded-secured-hello-handler
      if (config.login_conf == null) {
        throw new IllegalArgumentException("Must specify -login_conf argument");
      }

      LoginService loginService;
      if (config.hash_login) {
        Log.info("Configuring HashLoginService");
        loginService = new HashLoginService("H2O", config.login_conf);
      }
      else if (config.ldap_login) {
        Log.info("Configuring JAASLoginService (with LDAP)");
        System.setProperty("java.security.auth.login.config", config.login_conf);
        loginService = new JAASLoginService("ldaploginmodule");
      }
      else if (config.kerberos_login) {
        Log.info("Configuring JAASLoginService (with Kerberos)");
        System.setProperty("java.security.auth.login.config", config.login_conf);
        loginService = new JAASLoginService("krb5loginmodule");
      }
      else if (config.pam_login) {
        Log.info("Configuring JAASLoginService (with PAM)");
        System.setProperty("java.security.auth.login.config", config.login_conf);
        loginService = new JAASLoginService("pamloginmodule");
      }
      else {
        throw failEx("Unexpected authentication method selected");
      }
      IdentityService identityService = new DefaultIdentityService();
      loginService.setIdentityService(identityService);
      _server.addBean(loginService);

      // Set a security handler as the first handler in the chain.
      ConstraintSecurityHandler security = new ConstraintSecurityHandler();

      // Set up a constraint to authenticate all calls, and allow certain roles in.
      Constraint constraint = new Constraint();
      constraint.setName("auth");
      constraint.setAuthenticate(true);

      // Configure role stuff (to be disregarded).  We are ignoring roles, and only going off the user name.
      //
      //   Jetty 8 and prior.
      //
      //     Jetty 8 requires the security.setStrict(false) and ANY_ROLE.
      security.setStrict(false);
      constraint.setRoles(new String[]{Constraint.ANY_ROLE});

      //   Jetty 9 and later.
      //
      //     Jetty 9 and later uses a different servlet spec, and ANY_AUTH gives the same behavior
      //     for that API version as ANY_ROLE did previously.  This required some low-level debugging
      //     to figure out, so I'm documenting it here.
      //     Jetty 9 did not require security.setStrict(false).
      //
      // constraint.setRoles(new String[]{Constraint.ANY_AUTH});

      ConstraintMapping mapping = new ConstraintMapping();
      mapping.setPathSpec("/*"); // Lock down all API calls
      mapping.setConstraint(constraint);
      security.setConstraintMappings(Collections.singletonList(mapping));

      // Authentication / Authorization
      Authenticator authenticator;
      if (config.form_auth) {
        BasicAuthenticator basicAuthenticator = new BasicAuthenticator();
        FormAuthenticator formAuthenticator = new FormAuthenticator("/login", "/loginError", false);
        authenticator = new Jetty8DelegatingAuthenticator(basicAuthenticator, formAuthenticator);
      } else {
        authenticator = new BasicAuthenticator();
      }
      security.setLoginService(loginService);
      security.setAuthenticator(authenticator);

      HashSessionIdManager idManager = new HashSessionIdManager();
      _server.setSessionIdManager(idManager);

      HashSessionManager manager = new HashSessionManager();
      if (config.session_timeout > 0)
        manager.setMaxInactiveInterval(config.session_timeout * 60);

      SessionHandler sessionHandler = new SessionHandler(manager);
      sessionHandler.setHandler(security);

      // Pass-through to H2O if authenticated.
      registerHandlers(security);
      _server.setHandler(sessionHandler);
    } else {
      registerHandlers(_server);
    }

    _server.start();
  }

  private Server makeServer() {
    Server s = new Server();
    s.setSendServerVersion(false);
    return s;
  }

  private void startHttp() throws Exception {
    _server = makeServer();

    Connector connector=new SocketConnector();
    connector.setHost(_ip);
    connector.setPort(_port);

    createServer(
        configureConnector("http", connector));
  }

  /**
   * This implementation is based on http://blog.denevell.org/jetty-9-ssl-https.html
   *
   * @throws Exception -
   */
  private void startHttps() throws Exception {
    _server = makeServer();

    SslContextFactory sslContextFactory = new SslContextFactory(config.jks);
    sslContextFactory.setKeyStorePassword(config.jks_pass);

    SslSocketConnector httpsConnector = new SslSocketConnector(sslContextFactory);

    if (getIp() != null) {
      httpsConnector.setHost(getIp());
    }
    httpsConnector.setPort(getPort());

    createServer(
        configureConnector("https", httpsConnector));
  }

  // Configure connector via properties which we can modify.
  // Also increase request header size and buffer size from default values
  // located in org.eclipse.jetty.http.HttpBuffersImpl
  // see PUBDEV-5939 for details
  private Connector configureConnector(String proto, Connector connector) {
    connector.setRequestHeaderSize(getSysPropInt(proto+".requestHeaderSize", 32*1024));
    connector.setRequestBufferSize(getSysPropInt(proto+".requestBufferSize", 32*1024));
    connector.setResponseHeaderSize(getSysPropInt(proto+".responseHeaderSize", connector.getResponseHeaderSize()));
    connector.setResponseBufferSize(getSysPropInt(proto+".responseBufferSize", connector.getResponseBufferSize()));
    return connector;
  }

  private static int getSysPropInt(String suffix, int defaultValue) {
    return Integer.getInteger(WebServerConfig.SYSTEM_PROP_PREFIX + suffix, defaultValue);
  }

  /**
   * Stop Jetty server after it has been started.
   * This is unlikely to ever be called by H2O until H2O supports graceful shutdown.
   *
   * @throws Exception -
   */
  public void stop() throws Exception {
    if (_server != null) {
      _server.stop();
    }
  }

  /**
   * Hook up Jetty handlers.  Do this before start() is called.
   */
  private void registerHandlers(HandlerWrapper handlerWrapper) {
    // Both security and session handlers are already created (Note: we don't want to create a new separate session
    // handler just for ServletContextHandler - we want to have just one SessionHandler & SessionManager)
    ServletContextHandler context = new ServletContextHandler(
            ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS
    );

    if(null != config.context_path && ! config.context_path.isEmpty()) {
      context.setContextPath(config.context_path);
    } else {
      context.setContextPath("/");
    }

    registerHandlers(handlerWrapper, context);
  }

  public void registerHandlers(HandlerWrapper handlerWrapper, ServletContextHandler context) {
    context.addServlet(NpsBinServlet.class,   "/3/NodePersistentStorage.bin/*");
    context.addServlet(PostFileServlet.class, "/3/PostFile.bin");
    context.addServlet(PostFileServlet.class, "/3/PostFile");
    context.addServlet(DatasetServlet.class,  "/3/DownloadDataset");
    context.addServlet(DatasetServlet.class,  "/3/DownloadDataset.bin");
    context.addServlet(PutKeyServlet.class,   "/3/PutKey.bin");
    context.addServlet(PutKeyServlet.class,   "/3/PutKey");
    context.addServlet(RequestServer.class,   "/");

    final List<Handler> extHandlers = new ArrayList<>();
    extHandlers.add(new AuthenticationHandler());
    // here we wrap generic authentication handlers into jetty-aware wrappers
    for (final RequestAuthExtension requestAuthExtension : ExtensionManager.getInstance().getAuthExtensions()) {
      extHandlers.add(new AbstractHandler() {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
          if (requestAuthExtension.handle(target, request, response)) {
            baseRequest.setHandled(true);
          }
        }
      });
    }
    //
    extHandlers.add(context);

    // Handlers that can only be invoked for an authenticated user (if auth is enabled)
    HandlerCollection authHandlers = new HandlerCollection();
    authHandlers.setHandlers(extHandlers.toArray(new Handler[0]));

    // LoginHandler handles directly login requests and delegates the rest to the authHandlers
    LoginHandler loginHandler = new LoginHandler("/login", "/loginError");
    loginHandler.setHandler(authHandlers);

    HandlerCollection hc = new HandlerCollection();
    hc.setHandlers(new Handler[]{
        new GateHandler(),
        loginHandler
    });
    handlerWrapper.setHandler(hc);
  }

  //TODO make this effective in proxy instead of registerHandlers
  public void registerHandlers__Proxy(HandlerWrapper handlerWrapper, ServletContextHandler context, Credentials credentials, String proxyTo) {
    // setup authenticating proxy servlet (each request is forwarded with BASIC AUTH)
    final ServletHolder proxyServlet = new ServletHolder(TransparentProxyServlet.class);
    proxyServlet.setInitParameter("ProxyTo", proxyTo);
    proxyServlet.setInitParameter("Prefix", "/");
    proxyServlet.setInitParameter("BasicAuth", credentials.toBasicAuth());
    context.addServlet(proxyServlet, "/*");
    // authHandlers assume the user is already authenticated
    final HandlerCollection authHandlers = new HandlerCollection();
    authHandlers.setHandlers(new Handler[]{
        new AuthenticationHandler(),
        context,
    });
    // handles requests of login form and delegates the rest to the authHandlers
    final ProxyLoginHandler loginHandler = new ProxyLoginHandler("/login", "/loginError");
    loginHandler.setHandler(authHandlers);
    // login handler is the root handler
    handlerWrapper.setHandler(loginHandler);
  }

  public RuntimeException failEx(String message) {
    return H2O.fail(message);
  }

  //TODO make this effective in proxy instead of failEx
  public RuntimeException failEx__Proxy(String message) {
    return new IllegalStateException(message);
  }

  public void sendUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
    ServletUtils.sendResponseError(response, HttpServletResponse.SC_UNAUTHORIZED, message);
  }

  //TODO make this effective in proxy instead of sendUnauthorizedResponse
  public void sendUnauthorizedResponse__Proxy(HttpServletResponse response, String message) throws IOException {
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, message);
  }

  public class AuthenticationHandler extends AbstractHandler {
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            throws IOException {

      if (!config.ldap_login && !config.kerberos_login && !config.pam_login) return;

      String loginName = request.getUserPrincipal().getName();
      if (!loginName.equals(config.user_name)) {
        Log.warn("Login name (" + loginName + ") does not match cluster owner name (" + config.user_name + ")");
        sendUnauthorizedResponse(response, "Login name does not match cluster owner name");
        baseRequest.setHandled(true);
      }
    }
  }

}