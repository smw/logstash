package org.apache.mesos.logstash.scheduler;

import com.google.protobuf.ByteString;
import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;
import org.apache.mesos.logstash.config.FrameworkConfig;
import org.apache.mesos.logstash.state.ClusterState;
import org.apache.mesos.logstash.state.FrameworkState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.embedded.EmbeddedServletContainerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;
import static java.util.Collections.synchronizedCollection;
import static org.apache.mesos.Protos.*;

@Component
public class LogstashScheduler implements org.apache.mesos.Scheduler, ApplicationListener<EmbeddedServletContainerInitializedEvent> {
    private static final Logger LOGGER = LoggerFactory.getLogger(LogstashScheduler.class);

    @Inject
    Features features;
    @Inject
    FrameworkConfig frameworkConfig;

    @Inject
    TaskInfoBuilder taskInfoBuilder;

    private SchedulerDriver driver;

    @Inject
    MesosSchedulerDriverFactory mesosSchedulerDriverFactory;

    @Inject
    OfferStrategy offerStrategy;

    @Inject
    ClusterState clusterState;

    @Inject
    private FrameworkState frameworkState;

    private final AtomicBoolean appStarted = new AtomicBoolean(false);

    @Override
    public void onApplicationEvent(EmbeddedServletContainerInitializedEvent event) {
        if (appStarted.compareAndSet(false, true)) {
            start();
        }
    }

    public void start() {
        Protos.FrameworkInfo.Builder frameworkBuilder = Protos.FrameworkInfo.newBuilder()
                .setName(frameworkConfig.getFrameworkName())
                .setUser(frameworkConfig.getMesosUser())
                .setRole(frameworkConfig.getMesosRole())
                .setCheckpoint(true)
                .setFailoverTimeout(frameworkConfig.getFailoverTimeout())
                .setId(frameworkState.getFrameworkID());

        LOGGER.info("Starting Logstash Framework: \n{}", frameworkBuilder);

        if (frameworkConfig.getMesosPrincipal() != null) {
            Protos.Credential.Builder credentialBuilder = Protos.Credential.newBuilder();
            frameworkBuilder.setPrincipal(frameworkConfig.getMesosPrincipal());
            credentialBuilder.setPrincipal(frameworkConfig.getMesosPrincipal());
            credentialBuilder.setSecret(ByteString.copyFromUtf8(frameworkConfig.getMesosSecret()));
            LOGGER.info("Starting Logstash Framework: \n{}", frameworkBuilder);

            driver = mesosSchedulerDriverFactory.createMesosDriver(this, frameworkBuilder.build(),
                    credentialBuilder.build(), frameworkConfig.getZkUrl());
        }
        else {
            LOGGER.info("Starting Logstash Framework: \n{}", frameworkBuilder);

            driver = mesosSchedulerDriverFactory.createMesosDriver(this, frameworkBuilder.build(),
                    frameworkConfig.getZkUrl());
        }
        driver.start();
    }

    @PreDestroy
    public void stop() throws ExecutionException, InterruptedException {
        LOGGER.info("Stopping scheduler. Failover={}", features.isFailover());

        if (features.isFailover()) {
            driver.stop(true);
        } else {
            driver.stop(false);
            frameworkState.removeFrameworkId();
        }
    }

    @Override
    public void registered(SchedulerDriver schedulerDriver, FrameworkID frameworkId,
                           MasterInfo masterInfo) {

        frameworkState.setFrameworkId(frameworkId);

        LOGGER.info("Framework registered as: {}", frameworkId.getValue());

        Protos.Request request = Protos.Request.newBuilder()
                .addAllResources(taskInfoBuilder.getResourcesList())
                .build();

        List<Protos.Request> requests = Collections.singletonList(request);
        schedulerDriver.requestResources(requests);
    }

    @Override
    public void reregistered(SchedulerDriver schedulerDriver, MasterInfo masterInfo) {
        LOGGER.info("Re-registered with master. ip={}", masterInfo.getId());
    }

    @Override
    public void resourceOffers(SchedulerDriver schedulerDriver, List<Offer> offers) {
        offers.forEach(offer -> {
            final String offerId = offer.getId().getValue();

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Received offerId={}: {}", offerId, flattenProtobufString(offer.toString()));
            }

            final OfferStrategy.OfferResult result = offerStrategy.evaluate(clusterState, offer);

            if (result.acceptable()) {
                LOGGER.info("Accepting offer offerId={}", offerId);

                TaskInfo taskInfo = taskInfoBuilder.buildTask(offer);

                schedulerDriver.launchTasks(
                        singletonList(offer.getId()),
                        singletonList(taskInfo));

                clusterState.addTask(taskInfo);
            } else {
                LOGGER.debug("Declined offer offerId={} because: " + result.complaints.stream().collect(Collectors.joining("; ")));
                schedulerDriver.declineOffer(offer.getId());
            }
        });
    }

    @Override
    public void statusUpdate(SchedulerDriver schedulerDriver, TaskStatus status) {
        LOGGER.info("Received Status Update. taskId={}, state={}, message={}",
                status.getTaskId().getValue(),
                status.getState(),
                status.getMessage());

        if (new TreeSet<>(Arrays.asList(TaskState.TASK_FINISHED, TaskState.TASK_FAILED, TaskState.TASK_KILLED, TaskState.TASK_LOST, TaskState.TASK_ERROR)).contains(status.getState())) {
            clusterState.removeTaskById(status.getTaskId());
        }
    }

    @Override
    public void frameworkMessage(SchedulerDriver schedulerDriver, ExecutorID executorID,
                                 SlaveID slaveID, byte[] bytes) {
        LOGGER.info("Received framework message");
    }

    private String flattenProtobufString(String s) {
        return s.replace("  ", " ").replace("{\n", "{").replace("\n}", " }").replace("\n", ", ");
    }

    @Override
    public void disconnected(SchedulerDriver schedulerDriver) {
        LOGGER.warn("Scheduler driver disconnected.");
    }

    @Override
    public void error(SchedulerDriver schedulerDriver, String errorMessage) {
        LOGGER.error("Scheduler driver error. message={}", errorMessage);
    }

    @Override
    public void offerRescinded(SchedulerDriver schedulerDriver, OfferID offerID) {
        LOGGER.info("Offer Rescinded. offerId={}", offerID.getValue());
    }

    @Override
    public void slaveLost(SchedulerDriver schedulerDriver, SlaveID slaveID) {
        LOGGER.info("Slave Lost. slaveId={}", slaveID.getValue());
        clusterState.removeTaskBySlaveId(slaveID);
    }

    @Override
    public void executorLost(SchedulerDriver schedulerDriver, ExecutorID executorID,
                             SlaveID slaveID, int exitStatus) {
        LOGGER.warn("Executor Lost. executorId={}", executorID.getValue());
        clusterState.removeTaskByExecutorId(executorID);
        schedulerDriver.reviveOffers();
    }

    private boolean isRunningState(TaskStatus taskStatus) {
        return taskStatus.getState().equals(TaskState.TASK_RUNNING);
    }


    private String createWebuiUrl(int webServerPort) {
        String webUiUrl = null;
        try {
            String hostName = InetAddress.getLocalHost().getHostName();
            webUiUrl = "http:\\/\\/" + hostName + ":" + webServerPort;
        } catch (UnknownHostException e) {
            LOGGER.warn("Can not determine host name", e);
        }
        LOGGER.debug("Setting webuiUrl to " + webUiUrl);
        return webUiUrl;
    }

    /**
     * Implementation of Observable to fix the setChanged problem.
     */
    private static class StatusUpdateObservable extends Observable {
        @Override
        public void notifyObservers(Object arg) {
            this.setChanged(); // This is ridiculous.
            super.notifyObservers(arg);
        }
    }
}
