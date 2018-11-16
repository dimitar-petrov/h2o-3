package ai.h2o.webserver.iface;

import java.util.Map;

/**
 * Holds configuration relevant to HTTP server.
 */
public class H2OHttpConfig {

  /**
   * Prefix of hidden system properties, same as in H2O.OptArgs.SYSTEM_PROP_PREFIX.
   */
  public static final String SYSTEM_PROP_PREFIX = "sys.ai.h2o.";

  public String jks;

  public String jks_pass;

  public LoginType loginType;

  public String login_conf;

  public boolean form_auth;

  public int session_timeout; // parsed value (in minutes)

  public String user_name;

  public String ip;

  public String network;

  public String context_path;

  public Map<String,String> h2oExtendedHeaders;

  // proxy only:

  public int port;

  public int baseport;

  public String web_ip;


}