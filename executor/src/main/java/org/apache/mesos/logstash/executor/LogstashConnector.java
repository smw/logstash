package org.apache.mesos.logstash.executor;

import com.github.dockerjava.api.DockerClient;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;
import org.apache.log4j.Logger;

import java.io.InputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class responsible for connecting each discovered framework to logstash
 * Created by peldan on 22/06/15.
 */
public class LogstashConnector implements FrameworkListener {

    public static final Logger LOGGER = Logger.getLogger(LogstashConnector.class.toString());

    private String logstashContainer;

    private Map<String, LogForwarder> forwarders;

    private LogstashService logstash;

    private DockerInfo client;

    private DockerPoll poll;

    public LogstashConnector(DockerInfo client, LogstashService service) {
        this.client = client;
        forwarders = new HashMap<>();

        logstash = service;
    }

    public void init() {
        LOGGER.info("Hello, world!");

        poll = new DockerPoll(client);
        poll.attach(this);

        logstash.start();
    }

    public void frameworkAdded(Framework f) {
        forwarders.put(f.getId(), new LogForwarder(f));


        LOGGER.info("Reconfiguring logstash!");

        List<Framework> frameworks = new ArrayList<>();
        for(LogForwarder fw : forwarders.values()) {
            frameworks.add(fw.framework);
        }

        logstash.reconfigure(frameworks);
    }

    public void frameworkRemoved(Framework f) {
        //
    }


    private void runForwarders() {
        for(LogForwarder f : forwarders.values()) {
            f.start();
        }
    }


    class LogForwarder {
        Framework framework;

        LogForwarder(Framework framework) {
            this.framework = framework;
        }

        void start() {
            LOGGER.info("Running magic command");
            com.spotify.docker.client.LogStream logStream = client.execInContainer(framework.getId(), "tail", "-f", framework.getLogLocation());
            String fileName = LogDispatcher.writeLogToFile(framework.getId(), "", logStream);

            LOGGER.info(String.format("Thread writing to file %s", fileName));
        }
    }
}
