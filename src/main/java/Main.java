import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.ConfigurationUtils;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.spi.core.remoting.Acceptor;
import org.apache.hadoop.minikdc.MiniKdc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class Main {
    static {
        String path = System.getProperty("java.security.auth.login.config");
        if (path == null) {
            URL resource = Main.class.getClassLoader().getResource("login.config");
            if (resource != null) {
                path = resource.getFile();
                System.setProperty("java.security.auth.login.config", path);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        new Main().main_();
    }

    public void main_() throws Exception {
        Path keytabFolder = Paths.get("keytabFolder");
        keytabFolder.toFile().mkdirs();

        Path temporaryFolder = Files.createTempDirectory(null);
        final Path kdcFolder = temporaryFolder.resolve("kdc");
        kdcFolder.toFile().mkdir();
        final Properties conf = MiniKdc.createConf();
        conf.setProperty("debug", "true");
        conf.setProperty("transport", "UDP");  // for tcp kinit would require kdc = tcp/...
        MiniKdc kdc = new MiniKdc(conf, kdcFolder.toFile());

        kdc.start();

        System.out.println(kdc.getHost());
        System.out.println(new String(Files.readAllBytes(kdc.getKrb5conf().toPath())));

        kdc.createPrincipal(keytabFolder.resolve("principal.keytab").toFile(),
                "principal", "amqp/localhost");

        EmbeddedActiveMQ embeddedBroker = new EmbeddedActiveMQ();
        Configuration configuration = new ConfigurationImpl();
        int amqpPort = -1;

        ConfigurationUtils.validateConfiguration(configuration);
        embeddedBroker.setConfiguration(configuration);
        embeddedBroker.start();
        amqpPort = addAMQPAcceptor(embeddedBroker);


//        Map<String, Object> params = new HashMap<>();
//
//        params.put(TransportConstants.SSL_ENABLED_PROP_NAME, true);
//        params.put(TransportConstants.ENABLED_CIPHER_SUITES_PROP_NAME, getSuitableCipherSuite());
//        params.put(TransportConstants.SSL_KRB5_CONFIG_PROP_NAME, "core-tls-krb5-server");
//
//        ConfigurationImpl config = createBasicConfig().addAcceptorConfiguration(new TransportConfiguration(NETTY_ACCEPTOR_FACTORY, params, "nettySSL"));
//        config.setPopulateValidatedUser(true); // so we can verify the kerb5 id is present
//        config.setSecurityEnabled(true);
//
//        config.addAcceptorConfiguration(new TransportConfiguration(INVM_ACCEPTOR_FACTORY));
//
//        ActiveMQSecurityManager securityManager = new ActiveMQJAASSecurityManager("Krb5Plus");
//        server = addServer(ActiveMQServers.newActiveMQServer(config, ManagementFactory.getPlatformMBeanServer(), securityManager, false));
//        HierarchicalRepository<Set<Role>> securityRepository = server.getSecurityRepository();
//
//
//        final String roleName = "ALLOW_ALL";
//        Role role = new Role(roleName, true, true, true, true, true, true, true, true, true, true);
//        Set<Role> roles = new HashSet<>();
//        roles.add(role);
//        securityRepository.addMatch(QUEUE.toString(), roles);
//
//        server.start();
//        waitForServerToStart(server);
//
//        // note kerberos user does not exist on the broker save as a role member in dual-authentication-roles.properties
//        userPrincipal = CLIENT_PRINCIPAL + "@" + kdc.getRealm();
//
//        tc = new TransportConfiguration(NETTY_CONNECTOR_FACTORY);
//
//        tc.getParams().put(TransportConstants.SSL_ENABLED_PROP_NAME, true);
//        tc.getParams().put(TransportConstants.ENABLED_CIPHER_SUITES_PROP_NAME, getSuitableCipherSuite());
//        tc.getParams().put(TransportConstants.SNIHOST_PROP_NAME, SNI_HOST); // static service name rather than dynamic machine name
//        tc.getParams().put(TransportConstants.SSL_KRB5_CONFIG_PROP_NAME, "core-tls-krb5-client");
//        final ServerLocator locator = addServerLocator(ActiveMQClient.createServerLocatorWithoutHA(tc));

        String queue = "someQueue";
        embeddedBroker.getActiveMQServer().createQueue(
                SimpleString.toSimpleString(queue),
                RoutingType.ANYCAST,
                SimpleString.toSimpleString(queue),
                null, false, false);

        String pwd = Paths.get(".").toString();
        Path krb5conf = kdc.getKrb5conf().toPath();  // /var/dtests/node_data/gssapi/msgqe.com/msgqe_krb5.conf
        String kinitCmd = "KRB5_CONFIG=" + krb5conf + " kinit --keytab=" + pwd + "/keytabFolder/principal.keytab principal@EXAMPLE.COM";
        String client = "python /home/jdanek/Work/repos/dtests/dtests/node_data/clients/python/aac5_sender.py";
        String clientEnv = "LD_LIBRARY_PATH=/home/jdanek/Work/repos/qpid-proton/build/install/lib64 PYTHONPATH=/home/jdanek/Work/repos/qpid-proton/build/install/lib64/proton/bindings/python/";
        String clientCmd = clientEnv + " QPID_LOG_ENABLE=trace+ PN_TRACE_FRM=1 KRB5_CONFIG=" + krb5conf + " KRB5_TRACE=/dev/stdout " + client + " --broker-url \"127.0.0.1:" + amqpPort + "/" + queue + "\" --log-msgs dict --count 1 --conn-allowed-mechs GSSAPI";
        System.out.println(clientCmd);
        System.out.println(kinitCmd);

        Thread.sleep(9999999);

        embeddedBroker.stop();
        kdc.stop();
    }

    int addAMQPAcceptor(EmbeddedActiveMQ embeddedBroker) {
        Exception lastException = null;
        for (int i = 0; i < 10; i++) {
            try {
                int port = findRandomOpenPortOnAllLocalInterfaces();
                Acceptor acceptor = embeddedBroker.getActiveMQServer().getRemotingService().createAcceptor("amqp", "tcp://127.0.0.1:" + port + "?protocols=AMQP;saslMechanisms=GSSAPI,PLAIN;saslLoginConfigScope=krb5-server");
                acceptor.start();  // this will throw if the port is not available
                return port;
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new RuntimeException("Failed to bind to an available port", lastException);
    }

    /**
     * @return port number (there is a race so it may not be available anymore)
     * @throws IOException
     */
    // https://stackoverflow.com/questions/2675362/how-to-find-an-available-port
    private int findRandomOpenPortOnAllLocalInterfaces() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0));
            return socket.getLocalPort();
        }
    }
}
