package org.apache.mesos.logstash.systemtest;

import com.containersol.minimesos.mesos.MesosSlave;
import com.containersol.minimesos.mesos.ZooKeeper;
import com.github.dockerjava.api.DockerClient;

import java.util.TreeMap;

public class LogstashMesosSlave extends MesosSlave {
    public LogstashMesosSlave(DockerClient dockerClient, ZooKeeper zooKeeper) {
        super(dockerClient, zooKeeper);
    }

    @Override
    public TreeMap<String, String> getDefaultEnvVars() {
        final TreeMap<String, String> envVars = super.getDefaultEnvVars();
        envVars.put("MESOS_RESOURCES", "ports(logstash):[514-514,25826-25826,9299-9299,9300-9300]");
        return envVars;
    }
}
