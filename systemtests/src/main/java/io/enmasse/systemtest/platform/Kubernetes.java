/*
 * Copyright 2019-2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.platform;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.enmasse.systemtest.condition.MultinodeCluster;
import io.fabric8.openshift.api.model.Route;
import org.apache.commons.io.output.CloseShieldOutputStream;
import org.slf4j.Logger;

import io.enmasse.address.model.Address;
import io.enmasse.address.model.AddressList;
import io.enmasse.address.model.AddressSpace;
import io.enmasse.address.model.AddressSpaceList;
import io.enmasse.address.model.AddressSpaceSchema;
import io.enmasse.address.model.AddressSpaceSchemaList;
import io.enmasse.address.model.CoreCrd;
import io.enmasse.address.model.DoneableAddress;
import io.enmasse.address.model.DoneableAddressSpace;
import io.enmasse.address.model.DoneableAddressSpaceSchema;
import io.enmasse.admin.model.v1.AddressPlan;
import io.enmasse.admin.model.v1.AddressPlanList;
import io.enmasse.admin.model.v1.AddressSpacePlan;
import io.enmasse.admin.model.v1.AddressSpacePlanList;
import io.enmasse.admin.model.v1.AdminCrd;
import io.enmasse.admin.model.v1.AuthenticationService;
import io.enmasse.admin.model.v1.AuthenticationServiceList;
import io.enmasse.admin.model.v1.BrokeredInfraConfig;
import io.enmasse.admin.model.v1.BrokeredInfraConfigList;
import io.enmasse.admin.model.v1.ConsoleService;
import io.enmasse.admin.model.v1.ConsoleServiceList;
import io.enmasse.admin.model.v1.DoneableAddressPlan;
import io.enmasse.admin.model.v1.DoneableAddressSpacePlan;
import io.enmasse.admin.model.v1.DoneableAuthenticationService;
import io.enmasse.admin.model.v1.DoneableBrokeredInfraConfig;
import io.enmasse.admin.model.v1.DoneableConsoleService;
import io.enmasse.admin.model.v1.DoneableStandardInfraConfig;
import io.enmasse.admin.model.v1.StandardInfraConfig;
import io.enmasse.admin.model.v1.StandardInfraConfigList;
import io.enmasse.iot.model.v1.DoneableIoTConfig;
import io.enmasse.iot.model.v1.DoneableIoTProject;
import io.enmasse.iot.model.v1.IoTConfig;
import io.enmasse.iot.model.v1.IoTConfigList;
import io.enmasse.iot.model.v1.IoTCrd;
import io.enmasse.iot.model.v1.IoTProject;
import io.enmasse.iot.model.v1.IoTProjectList;
import io.enmasse.model.CustomResourceDefinitions;
import io.enmasse.systemtest.Endpoint;
import io.enmasse.systemtest.EnmasseInstallType;
import io.enmasse.systemtest.Environment;
import io.enmasse.systemtest.OLMInstallationType;
import io.enmasse.systemtest.condition.OpenShiftVersion;
import io.enmasse.systemtest.logs.CustomLogger;
import io.enmasse.systemtest.platform.cluster.ClusterType;
import io.enmasse.systemtest.platform.cluster.KubeCluster;
import io.enmasse.systemtest.platform.cluster.MinikubeCluster;
import io.enmasse.systemtest.platform.cluster.NoClusterException;
import io.enmasse.systemtest.time.TimeoutBudget;
import io.enmasse.systemtest.utils.TestUtils;
import io.enmasse.user.model.v1.DoneableUser;
import io.enmasse.user.model.v1.User;
import io.enmasse.user.model.v1.UserCrd;
import io.enmasse.user.model.v1.UserList;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.DoneablePod;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import io.fabric8.kubernetes.api.model.policy.PodDisruptionBudget;
import io.fabric8.kubernetes.api.model.storage.StorageClass;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.VersionInfo;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import okhttp3.Response;

public abstract class Kubernetes {
    private static final Logger log = CustomLogger.getLogger();
    private static Kubernetes instance;
    protected final Environment environment;
    protected final KubernetesClient client;
    protected final String infraNamespace;
    protected static KubeCluster cluster;
    private boolean olmAvailable;
    private boolean verboseLog;

    static {
        try {
            CustomResourceDefinitions.registerAll();
        } catch (RuntimeException t) {
            t.printStackTrace();
            throw new ExceptionInInitializerError(t);
        }
    }

    /**
     * Check if tests are running a platform which is compatible to a specific OpenShift version
     * (OpenShift or CRC).
     *
     * @param version The version to check for.
     * @return {@code true} if running the requested OpenShift version, {@code false} otherwise.
     */
    public static boolean isOpenShiftCompatible(OpenShiftVersion version) {

        if (!isOpenShift() && !isCRC()) {
            return false;
        }

        if (version == null || version == OpenShiftVersion.WHATEVER) {
            return true;
        }

        return Kubernetes.getInstance().getOcpVersion() == version;

    }

    /**
     * Check if tests are running on something which is compatible with OpenShift (OpenShift or CRC).
     */
    public static boolean isOpenShiftCompatible() {
        return isOpenShiftCompatible(OpenShiftVersion.WHATEVER);
    }

    /**
     * Check if tests are running on OpenShift (excluding CRC).
     */
    public static boolean isOpenShift() {
        return Kubernetes.getInstance().getCluster().toString().equals(ClusterType.OPENSHIFT.toString().toLowerCase());
    }

    /**
     * Check if tests are running on OpenShift in CRC.
     */
    public static boolean isCRC() {
        return Kubernetes.getInstance().getCluster().toString().equals(ClusterType.CRC.toString().toLowerCase());
    }

    protected Kubernetes(Environment environment, Supplier<KubernetesClient> clientSupplier) {
        this.environment = environment;
        this.client = clientSupplier.get();
        if (environment.installType() == EnmasseInstallType.OLM
                && environment.olmInstallType() == OLMInstallationType.DEFAULT) {
            this.infraNamespace = getOlmNamespace();
        } else {
            this.infraNamespace = environment.namespace();
        }
        this.verboseLog = true;
    }

    private static int getPort(Service service, String portName) {
        List<ServicePort> ports = service.getSpec().getPorts();
        for (ServicePort port : ports) {
            if (port.getName().equals(portName)) {
                return port.getPort();
            }
        }
        throw new IllegalArgumentException(
                "Unable to find port " + portName + " for service " + service.getMetadata().getName());
    }

    public static Kubernetes getInstance() {
        if (instance == null) {
            try {
                cluster = KubeCluster.detect();
                log.info("Cluster is {}", cluster.toString());
            } catch (NoClusterException ex) {
                log.error(ex.getMessage());
                throw new RuntimeException(ex);
            }
            Environment env = Environment.getInstance();
            if (cluster.toString().equals(MinikubeCluster.IDENTIFIER)) {
                instance = new Minikube(env);
            } else {
                instance = new OpenShift(env);
            }
            try {
                instance.olmAvailable = instance.getCRD("clusterserviceversions.operators.coreos.com") != null
                        && instance.getCRD("subscriptions.operators.coreos.com") != null;
                if (instance.olmAvailable) {
                    log.info("OLM is available in this cluster");
                } else {
                    log.info("OLM is not available in this cluster");
                }
            } catch (Exception e) {
                log.error("Error checking olm availability", e);
                instance.olmAvailable = false;
            }
        }
        return instance;
    }

    public static void disableVerboseLogging() {
        Kubernetes.getInstance().verboseLog = false;
    }

    public double getKubernetesVersion() {
        try (var client = new DefaultKubernetesClient()) {
            final VersionInfo versionInfo = client.getVersion();
            return Double.parseDouble(versionInfo.getMajor() + "." + versionInfo.getMinor().replace("+", ""));
        }
    }

    public OpenShiftVersion getOcpVersion() {
        return OpenShiftVersion.fromK8sVersion(getKubernetesVersion());
    }

    public MultinodeCluster isClusterMultinode() {
        return MultinodeCluster.isMultinode(client.nodes().list().getItems().size());
    }


    public String getInfraNamespace() {
        return infraNamespace;
    }

    public KubernetesClient getClient() {
        return client;
    }

    public KubeCluster getCluster() {
        return cluster;
    }

    public boolean isOLMAvailable() {
        return olmAvailable;
    }

    ///////////////////////////////////////////////////////////////////////////////
    // client and crd clients
    ///////////////////////////////////////////////////////////////////////////////

    public MixedOperation<AddressSpace, AddressSpaceList, DoneableAddressSpace, Resource<AddressSpace, DoneableAddressSpace>> getAddressSpaceClient() {
        return getAddressSpaceClient(infraNamespace);
    }

    public MixedOperation<AddressSpace, AddressSpaceList, DoneableAddressSpace,
            Resource<AddressSpace, DoneableAddressSpace>> getAddressSpaceClient(String namespace) {
        return (MixedOperation<AddressSpace, AddressSpaceList, DoneableAddressSpace, Resource<AddressSpace, DoneableAddressSpace>>) client.customResources(CoreCrd.addressSpaces(), AddressSpace.class, AddressSpaceList.class, DoneableAddressSpace.class).inNamespace(namespace);
    }

    public MixedOperation<Address, AddressList, DoneableAddress, Resource<Address, DoneableAddress>> getAddressClient() {
        return getAddressClient(infraNamespace);
    }

    public MixedOperation<Address, AddressList, DoneableAddress, Resource<Address, DoneableAddress>> getAddressClient(String namespace) {
        return (MixedOperation<Address, AddressList, DoneableAddress, Resource<Address, DoneableAddress>>) client.customResources(CoreCrd.addresses(), Address.class, AddressList.class, DoneableAddress.class).inNamespace(namespace);
    }

    public MixedOperation<User, UserList, DoneableUser, Resource<User, DoneableUser>> getUserClient() {
        return getUserClient(infraNamespace);
    }

    public MixedOperation<User, UserList, DoneableUser,
            Resource<User, DoneableUser>> getUserClient(String namespace) {
        return (MixedOperation<User, UserList, DoneableUser, Resource<User, DoneableUser>>) client.customResources(UserCrd.messagingUser(), User.class, UserList.class, DoneableUser.class).inNamespace(namespace);
    }

    public MixedOperation<AddressSpacePlan, AddressSpacePlanList, DoneableAddressSpacePlan,
            Resource<AddressSpacePlan, DoneableAddressSpacePlan>> getAddressSpacePlanClient() {
        return getAddressSpacePlanClient(infraNamespace);
    }

    public MixedOperation<AddressSpacePlan, AddressSpacePlanList, DoneableAddressSpacePlan,
            Resource<AddressSpacePlan, DoneableAddressSpacePlan>> getAddressSpacePlanClient(String namespace) {
        return (MixedOperation<AddressSpacePlan, AddressSpacePlanList, DoneableAddressSpacePlan,
                Resource<AddressSpacePlan, DoneableAddressSpacePlan>>) client.customResources(AdminCrd.addressSpacePlans(), AddressSpacePlan.class, AddressSpacePlanList.class, DoneableAddressSpacePlan.class).inNamespace(namespace);
    }

    public MixedOperation<AddressPlan, AddressPlanList, DoneableAddressPlan,
            Resource<AddressPlan, DoneableAddressPlan>> getAddressPlanClient() {
        return getAddressPlanClient(infraNamespace);
    }

    public MixedOperation<AddressPlan, AddressPlanList, DoneableAddressPlan,
            Resource<AddressPlan, DoneableAddressPlan>> getAddressPlanClient(String namespace) {
        return (MixedOperation<AddressPlan, AddressPlanList, DoneableAddressPlan,
                Resource<AddressPlan, DoneableAddressPlan>>) client.customResources(AdminCrd.addressPlans(), AddressPlan.class, AddressPlanList.class, DoneableAddressPlan.class).inNamespace(namespace);
    }

    public MixedOperation<BrokeredInfraConfig, BrokeredInfraConfigList, DoneableBrokeredInfraConfig,
            Resource<BrokeredInfraConfig, DoneableBrokeredInfraConfig>> getBrokeredInfraConfigClient() {
        return getBrokeredInfraConfigClient(infraNamespace);
    }

    public MixedOperation<BrokeredInfraConfig, BrokeredInfraConfigList, DoneableBrokeredInfraConfig,
            Resource<BrokeredInfraConfig, DoneableBrokeredInfraConfig>> getBrokeredInfraConfigClient(String namespace) {
        return (MixedOperation<BrokeredInfraConfig, BrokeredInfraConfigList, DoneableBrokeredInfraConfig,
                Resource<BrokeredInfraConfig, DoneableBrokeredInfraConfig>>) client.customResources(AdminCrd.brokeredInfraConfigs(), BrokeredInfraConfig.class, BrokeredInfraConfigList.class, DoneableBrokeredInfraConfig.class).inNamespace(namespace);
    }

    public MixedOperation<StandardInfraConfig, StandardInfraConfigList, DoneableStandardInfraConfig,
            Resource<StandardInfraConfig, DoneableStandardInfraConfig>> getStandardInfraConfigClient() {
        return getStandardInfraConfigClient(infraNamespace);
    }

    public MixedOperation<StandardInfraConfig, StandardInfraConfigList, DoneableStandardInfraConfig,
            Resource<StandardInfraConfig, DoneableStandardInfraConfig>> getStandardInfraConfigClient(String namespace) {
        return (MixedOperation<StandardInfraConfig, StandardInfraConfigList, DoneableStandardInfraConfig,
                Resource<StandardInfraConfig, DoneableStandardInfraConfig>>) client.customResources(AdminCrd.standardInfraConfigs(), StandardInfraConfig.class, StandardInfraConfigList.class, DoneableStandardInfraConfig.class).inNamespace(namespace);
    }

    public MixedOperation<AuthenticationService, AuthenticationServiceList, DoneableAuthenticationService,
            Resource<AuthenticationService, DoneableAuthenticationService>> getAuthenticationServiceClient() {
        return getAuthenticationServiceClient(infraNamespace);
    }

    public MixedOperation<AuthenticationService, AuthenticationServiceList, DoneableAuthenticationService,
            Resource<AuthenticationService, DoneableAuthenticationService>> getAuthenticationServiceClient(String namespace) {
        return (MixedOperation<AuthenticationService, AuthenticationServiceList, DoneableAuthenticationService,
                Resource<AuthenticationService, DoneableAuthenticationService>>) client.customResources(AdminCrd.authenticationServices(), AuthenticationService.class, AuthenticationServiceList.class, DoneableAuthenticationService.class).inNamespace(namespace);
    }

    public MixedOperation<AddressSpaceSchema, AddressSpaceSchemaList, DoneableAddressSpaceSchema,
            Resource<AddressSpaceSchema, DoneableAddressSpaceSchema>> getSchemaClient() {
        return getSchemaClient(infraNamespace);
    }

    public MixedOperation<AddressSpaceSchema, AddressSpaceSchemaList, DoneableAddressSpaceSchema,
            Resource<AddressSpaceSchema, DoneableAddressSpaceSchema>> getSchemaClient(String namespace) {
        return (MixedOperation<AddressSpaceSchema, AddressSpaceSchemaList, DoneableAddressSpaceSchema,
                Resource<AddressSpaceSchema, DoneableAddressSpaceSchema>>) client.customResources(CoreCrd.addresseSpaceSchemas(), AddressSpaceSchema.class, AddressSpaceSchemaList.class, DoneableAddressSpaceSchema.class).inNamespace(namespace);
    }

    public MixedOperation<ConsoleService, ConsoleServiceList, DoneableConsoleService,
            Resource<ConsoleService, DoneableConsoleService>> getConsoleServiceClient() {
        return getConsoleServiceClient(infraNamespace);
    }

    public MixedOperation<ConsoleService, ConsoleServiceList, DoneableConsoleService,
            Resource<ConsoleService, DoneableConsoleService>> getConsoleServiceClient(String namespace) {
        return (MixedOperation<ConsoleService, ConsoleServiceList, DoneableConsoleService,
                Resource<ConsoleService, DoneableConsoleService>>) client.customResources(AdminCrd.consoleServices(), ConsoleService.class, ConsoleServiceList.class, DoneableConsoleService.class).inNamespace(namespace);
    }

    public MixedOperation<IoTConfig, IoTConfigList, DoneableIoTConfig, Resource<IoTConfig, DoneableIoTConfig>> getIoTConfigClient() {
        return getIoTConfigClient(infraNamespace);
    }

    public MixedOperation<IoTConfig, IoTConfigList, DoneableIoTConfig, Resource<IoTConfig, DoneableIoTConfig>> getIoTConfigClient(String namespace) {
        return (MixedOperation<IoTConfig, IoTConfigList, DoneableIoTConfig, Resource<IoTConfig, DoneableIoTConfig>>) client.customResources(IoTCrd.config(), IoTConfig.class, IoTConfigList.class, DoneableIoTConfig.class).inNamespace(namespace);
    }

    public MixedOperation<IoTProject, IoTProjectList, DoneableIoTProject, Resource<IoTProject, DoneableIoTProject>> getNonNamespacedIoTProjectClient() {
        return getIoTProjectClient(null);
    }

    public MixedOperation<IoTProject, IoTProjectList, DoneableIoTProject, Resource<IoTProject, DoneableIoTProject>> getIoTProjectClient(String namespace) {
        if (namespace == null) {
            return client.customResources(IoTCrd.project(), IoTProject.class, IoTProjectList.class, DoneableIoTProject.class);
        } else {
            return (MixedOperation<IoTProject, IoTProjectList, DoneableIoTProject, Resource<IoTProject, DoneableIoTProject>>) client.customResources(IoTCrd.project(), IoTProject.class, IoTProjectList.class, DoneableIoTProject.class).inNamespace(namespace);
        }
    }

    ///////////////////////////////////////////////////////////////////////////////
    // help methods
    ///////////////////////////////////////////////////////////////////////////////

    public String getApiToken() {
        return environment.getApiToken();
    }

    public Endpoint getEndpoint(String serviceName, String namespace, String port) {
        Service service = client.services().inNamespace(namespace).withName(serviceName).get();
        Objects.requireNonNull(service, () -> String.format("Unable to find service '%s' in namespace '%s'", serviceName, namespace));
        return new Endpoint(service.getSpec().getClusterIP(), getPort(service, port));
    }

    public abstract Endpoint getMasterEndpoint();

    public abstract Endpoint getRestEndpoint();

    public abstract Endpoint getKeycloakEndpoint();

    /**
     * Assumes infra namespace
     *
     * @param name
     * @return
     */
    public abstract Endpoint getExternalEndpoint(String name);

    public abstract Endpoint getExternalEndpoint(String name, String namespace);

    public abstract String getClusterExternalImageRegistry();

    public abstract String getClusterInternalImageRegistry();

    public Map<String, String> getLogsOfTerminatedPods(String namespace) {
        Map<String, String> terminatedPodsLogs = new HashMap<>();
        client.pods().inNamespace(namespace).list().getItems().forEach(pod -> {
            pod.getStatus().getContainerStatuses().forEach(containerStatus -> {
                log.info("pod:'{}', container:'{}' : restart count '{}'",
                        pod.getMetadata().getName(),
                        containerStatus.getName(),
                        containerStatus.getRestartCount());
                if (containerStatus.getRestartCount() > 0) {
                    String name = String.format("%s_%s", pod.getMetadata().getName(), containerStatus.getName());
                    try {
                        String log = client.pods().inNamespace(namespace)
                                .withName(pod.getMetadata().getName())
                                .inContainer(containerStatus.getName())
                                .terminated().getLog();
                        terminatedPodsLogs.put(
                                name,
                                log);
                    } catch (Exception e) {
                        log.warn("Failed to gather terminated log for {} with termination count {} (ignored)", name, containerStatus.getRestartCount());
                    }
                }
            });
        });
        return terminatedPodsLogs;
    }

    public String getOCConsoleRoute() {
        if (OpenShiftVersion.OCP4 == getOcpVersion()) {
            return String.format("https://console-openshift-console.%s", Environment.getInstance().kubernetesDomain()).replaceAll("(?<!(http:|https:))[//]+", "/");
        } else {
            return String.format("%s/console", Environment.getInstance().getApiUrl()).replaceAll("(?<!(http:|https:))[//]+", "/");
        }
    }

    public Map<String, String> getLogsByLables(String namespace, Map<String, String> labels) {
        return getLogs(namespace, client.pods().inNamespace(namespace).withLabels(labels).list().getItems());
    }

    public Map<String, String> getLogsOfAllPods(String namespace) {
        return getLogs(namespace, client.pods().inNamespace(namespace).list().getItems());
    }

    public Map<String, String> getLogs(String namespace, List<Pod> pods) {
        Map<String, String> logs = new HashMap<>();
        try {
            if (pods == null || pods.isEmpty()) {
                log.info("No pods to get logs");
                return logs;
            }
            pods.forEach(pod -> {
                pod.getSpec().getContainers().forEach(container -> {
                    logs.put(pod.getMetadata().getName() + "-" + container.getName(),
                            client.pods().inNamespace(namespace)
                                    .withName(pod.getMetadata().getName())
                                    .inContainer(container.getName())
                                    .getLog());
                });
            });
        } catch (Exception e) {
            log.error("Error getting logs of pods", e);
        }
        return logs;
    }

    public void setDeploymentReplicas(String namespace, String name, int numReplicas) {
        client.apps().deployments().inNamespace(namespace).withName(name).scale(numReplicas, true);
    }

    /**
     * List <strong>all</strong> pods.
     * <p>
     * Compared to {@link #listPods(String)}, this method indeed returns all pods.
     * @param namespace The namespace to list pods in.
     * @return The list of all pods.
     */
    public List<Pod> listAllPods(String namespace) {
        return client.pods().inNamespace(namespace).list().getItems();
    }

    public List<Pod> listPods(String namespace) {
        return client.pods().inNamespace(namespace).list().getItems()
                .stream().filter(pod -> !pod.getMetadata().getName().contains("tenant-cleanup"))
                .collect(Collectors.toList()); //TODO remove until cleaning of this pod will be fixed;
    }

    public List<Pod> listPods() {
        return listPods(infraNamespace);
    }

    /**
     * List <strong>all</strong> pods.
     * <p>
     * @return The list of all pods.
     */
    public List<Pod> listAllPods() {
        return listAllPods(infraNamespace);
    }

    public List<Pod> listPods(Map<String, String> labelSelector) {
        return listPods(infraNamespace, labelSelector);
    }

    public List<Pod> listPods(String namespace, Map<String, String> labelSelector) {
        return client.pods().inNamespace(namespace).withLabels(labelSelector).list().getItems();
    }

    public List<Pod> listPods(String namespace, Map<String, String> labelSelector, Map<String, String> annotationSelector) {
        return client.pods().inNamespace(namespace).withLabels(labelSelector).list().getItems().stream().filter(pod -> {
            for (Map.Entry<String, String> entry : annotationSelector.entrySet()) {
                return pod.getMetadata().getAnnotations() != null
                        && pod.getMetadata().getAnnotations().get(entry.getKey()) != null
                        && pod.getMetadata().getAnnotations().get(entry.getKey()).equals(entry.getValue());
            }
            return true;
        }).collect(Collectors.toList());
    }

    public Pod getPod(String namespace, String name) {
        return client.pods().inNamespace(namespace).withName(name).get();
    }

    public Set<String> listNamespaces() {
        return client.namespaces().list().getItems().stream()
                .map(ns -> ns.getMetadata().getName())
                .collect(Collectors.toSet());
    }

    public List<ConfigMap> listConfigMaps(Map<String, String> labels) {
        return client.configMaps().inNamespace(infraNamespace).withLabels(labels).list().getItems();
    }

    public List<Service> listServices(Map<String, String> labels) {
        return client.services().inNamespace(infraNamespace).withLabels(labels).list().getItems();
    }

    public List<Secret> listSecrets(Map<String, String> labels) {
        return client.secrets().inNamespace(infraNamespace).withLabels(labels).list().getItems();
    }

    public List<Deployment> listDeployments(Map<String, String> labels) {
        return listDeployments(infraNamespace, labels);
    }

    public List<Deployment> listDeployments(String namespace, Map<String, String> labels) {
        return client.apps().deployments().inNamespace(namespace).withLabels(labels).list().getItems();
    }

    public List<StatefulSet> listStatefulSets(Map<String, String> labels) {
        return client.apps().statefulSets().inNamespace(infraNamespace).withLabels(labels).list().getItems();
    }

    public List<ServiceAccount> listServiceAccounts(Map<String, String> labels) {
        return client.serviceAccounts().inNamespace(infraNamespace).withLabels(labels).list().getItems();
    }

    public List<PersistentVolumeClaim> listPersistentVolumeClaims(Map<String, String> labels) {
        return client.persistentVolumeClaims().inNamespace(infraNamespace).withLabels(labels).list().getItems();
    }

    public StorageClass getStorageClass(String name) {
        return client.storage().storageClasses().withName(name).get();
    }

    public ConfigMapList getAllConfigMaps(String namespace) {
        return client.configMaps().inNamespace(namespace).list();
    }

    public void createNamespace(String namespace) {
        createNamespace(namespace, null);
    }

    public void createNamespace(String namespace, Map<String, String> labels) {
        if (!namespaceExists(namespace)) {
            log.info("Following namespace will be created = {}", namespace);
            var builder = new NamespaceBuilder().withNewMetadata().withName(namespace);
            if (labels != null) {
                builder.withLabels(labels);
            }
            Namespace ns = builder.endMetadata().build();
            client.namespaces().create(ns);
        } else {
            log.info("Namespace {} already exists", namespace);
        }
    }

    public void deleteNamespace(String namespace) throws Exception {
        deleteNamespace(namespace, Duration.ofMinutes(5));
    }

    public void deleteNamespace(String namespace, Duration timeout) throws Exception {
        if (verboseLog) {
            log.info("Following namespace will be removed - {}", namespace);
        }
        if (namespaceExists(namespace)) {
            client.namespaces().withName(namespace).cascading(true).delete();

            TestUtils.waitUntilCondition("Namespace will be deleted", phase ->
                    !namespaceExists(namespace), TimeoutBudget.ofDuration(timeout));
        } else {
            log.info("Namespace {} already removed", namespace);
        }
    }

    public boolean namespaceExists(String namespace) {
        return client.namespaces().list().getItems().stream().map(n -> n.getMetadata().getName())
                .collect(Collectors.toList()).contains(namespace);
    }

    public void deletePod(String namespace, Map<String, String> labels) {
        log.info("Delete pods with labels: {}", labels.toString());
        client.pods().inNamespace(namespace).withLabels(labels).withPropagationPolicy("Background").delete();
    }

    /***
     * Delete pod by name
     * @param namespace
     * @param podName
     * @throws Exception
     */
    public void deletePod(String namespace, String podName) {
        client.pods().inNamespace(namespace).withName(podName).cascading(true).delete();
        log.info("Pod {} removed", podName);
    }

    /**
     * Creates pod from resource
     * @param namespace
     * @param resources
     * @throws Exception
     */
    public void createPodFromResource(String namespace, Pod resources) throws Exception {
        if (getPod(namespace, resources.getMetadata().getName()) == null) {
            Pod podRes = client.pods().inNamespace(namespace).create(resources);
            if (verboseLog) {
                log.info("Pod {} created", podRes.getMetadata().getName());
            }
        } else {
            if (verboseLog) {
                log.info("Pod {} already exists", resources.getMetadata().getName());
            }
        }
    }

    /***
     * Creates application from resources
     * @param namespace namespace
     * @param resources deployment resource
     * @throws Exception whe deployment failed
     */
    public void createDeploymentFromResource(String namespace, Deployment resources) throws Exception {
        createDeploymentFromResource(namespace, resources, 2, TimeUnit.MINUTES);
    }

    public void createDeploymentFromResource(String namespace, Deployment resources, long time, TimeUnit unit) throws Exception {
        if (!deploymentExists(namespace, resources.getMetadata().getName())) {
            Deployment depRes = client.apps().deployments().inNamespace(namespace).create(resources);
            Deployment result = client.apps().deployments().inNamespace(namespace)
                    .withName(depRes.getMetadata().getName()).waitUntilReady(time, unit);
            if (verboseLog) {
                log.info("Deployment {} created", result.getMetadata().getName());
            }
        } else {
            if (verboseLog) {
                log.info("Deployment {} already exists", resources.getMetadata().getName());
            }
        }
    }

    /**
     * Create service from resource
     *
     * @param namespace namespace
     * @param resources service resource
     * @return endpoint of new service
     */
    public void createServiceFromResource(String namespace, Service resources) {
        if (!serviceExists(namespace, resources.getMetadata().getName())) {
            Service serRes = client.services().inNamespace(namespace).create(resources);
            if (verboseLog) {
                log.info("Service {} created", serRes.getMetadata().getName());
            }
        } else {
            if (verboseLog) {
                log.info("Service {} already exists", resources.getMetadata().getName());
            }
        }
    }

    /**
     * Creates ingress from resource
     *
     * @param namespace namespace
     * @param resources resources
     */
    public void createIngressFromResource(String namespace, Ingress resources) {
        if (!ingressExists(namespace, resources.getMetadata().getName())) {
            Ingress serRes = client.extensions().ingresses().inNamespace(namespace).create(resources);
            if (verboseLog) {
                log.info("Ingress {} created", serRes.getMetadata().getName());
            }
        } else {
            if (verboseLog) {
                log.info("Ingress {} already exists", resources.getMetadata().getName());
            }
        }
    }

    /**
     * Deletes ingress
     *
     * @param namespace   namespace
     * @param ingressName ingress name
     */
    public void deleteIngress(String namespace, String ingressName) {
        client.extensions().ingresses().inNamespace(namespace).withName(ingressName).cascading(true).delete();
        if (verboseLog) {
            log.info("Ingress {} deleted", ingressName);
        }
    }

    /**
     * Test if ingress already exists
     *
     * @param namespace   namespace
     * @param ingressName name of ingress
     * @return boolean
     */
    public boolean ingressExists(String namespace, String ingressName) {
        return client.extensions().ingresses().inNamespace(namespace).list().getItems().stream()
                .map(ingress -> ingress.getMetadata().getName()).collect(Collectors.toList()).contains(ingressName);
    }

    /**
     * Return host of ingress
     *
     * @param namespace   namespace
     * @param ingressName name of ingress
     * @return string host
     */
    public String getIngressHost(String namespace, String ingressName) {
        return client.extensions().ingresses().inNamespace(namespace).withName(ingressName).get().getSpec().getRules().get(0).getHost();
    }

    /**
     * Create configmap from resource
     *
     * @param namespace kubernetes namespace
     * @param resources configmap resources
     */
    public void createConfigmapFromResource(String namespace, ConfigMap resources) {
        if (!configmapExists(namespace, resources.getMetadata().getName())) {
            client.configMaps().inNamespace(namespace).create(resources);
            log.info("Configmap {} in namespace {} created", resources.getMetadata().getName(), namespace);
        } else {
            log.info("Configmap {} in namespace {} already exists", resources.getMetadata().getName(), namespace);
        }
    }

    /**
     * Delete configmap from resource
     *
     * @param namespace     kubernetes namespace
     * @param configmapName configmap
     */
    public void deleteConfigmap(String namespace, String configmapName) {
        client.configMaps().inNamespace(namespace).withName(configmapName).cascading(true).delete();
        log.info("Configmap {} in namespace {} deleted", configmapName, namespace);
    }

    /**
     * Test if configmap plready exists
     *
     * @param namespace     kubernetes namespace
     * @param configmapName configmap
     * @return boolean
     */
    public boolean configmapExists(String namespace, String configmapName) {
        return client.configMaps().inNamespace(namespace).list().getItems().stream()
                .map(configMap -> configMap.getMetadata().getName()).collect(Collectors.toList()).contains(configmapName);
    }

    /***
     * Deletes deployment by name
     * @param namespace
     * @param appName
     */
    public void deleteDeployment(String namespace, String appName) {
        client.apps().deployments().inNamespace(namespace).withName(appName).cascading(true).delete();
        if (verboseLog) {
            log.info("Deployment {} removed", appName);
        }
    }

    /***
     * Check if deployment exists
     * @param namespace kuberntes namespace name
     * @param appName name of deployment
     * @return true if deployment exists
     */
    public boolean deploymentExists(String namespace, String appName) {
        return client.apps().deployments().inNamespace(namespace).list().getItems().stream()
                .map(deployment -> deployment.getMetadata().getName()).collect(Collectors.toList()).contains(appName);
    }

    /***
     * Delete service by name
     * @param namespace kubernetes namespace
     * @param serviceName service name
     */
    public void deleteService(String namespace, String serviceName) {
        client.services().inNamespace(namespace).withName(serviceName).cascading(true).delete();
        if (verboseLog) {
            log.info("Service {} removed", serviceName);
        }
    }

    /**
     * Test if service already exists
     *
     * @param namespace   namespace
     * @param serviceName service name
     * @return boolean
     */
    public boolean serviceExists(String namespace, String serviceName) {
        return client.services().inNamespace(namespace).list().getItems().stream()
                .map(service -> service.getMetadata().getName()).collect(Collectors.toList()).contains(serviceName);
    }

    /***
     * Returns list of running containers in pod
     * @param podName name of pod
     * @return list of containers
     */
    public List<Container> getContainersFromPod(String namespace, String podName) {
        Objects.requireNonNull(podName);
        return client.pods().inNamespace(namespace).withName(podName).get().getSpec().getContainers();
    }

    /***
     * Returns log of container in pod
     * @param podName name of pod
     * @param containerName name of container in pod
     * @return log
     */
    public String getLog(String namespace, String podName, String containerName) {
        Objects.requireNonNull(podName);
        Objects.requireNonNull(containerName);
        return client.pods().inNamespace(namespace).withName(podName).inContainer(containerName).getLog();
    }

    /***
     * Wait until pod ready
     * @param pod pod instance
     * @throws Exception when pod is not ready in timeout
     */
    public void waitUntilPodIsReady(Pod pod) throws InterruptedException {
        log.info("Waiting until pod: {} is ready", pod.getMetadata().getName());
        client.resource(pod).inNamespace(pod.getMetadata().getNamespace()).waitUntilReady(5, TimeUnit.MINUTES);
    }

    /***
     * Wait pod until condition
     * @param pod pod instance
     * @param condition predicate
     * @param amount timeout amount
     * @param timeUnit time unit of the timeout
     * @throws Exception when pod is not ready in timeout
     */
    public void waitPodUntilCondition(Pod pod, Predicate<Pod> condition, long amount, TimeUnit timeUnit) throws InterruptedException {
        log.info("Waiting pod: {} with predicate", pod.getMetadata().getName());
        client.pods().inNamespace(pod.getMetadata().getNamespace()).withName(pod.getMetadata().getName()).waitUntilCondition(condition, amount, timeUnit);
    }

    /***
     * Get app label value
     * @return app label value
     */
    public String getEnmasseAppLabel() {
        return listPods().get(0).getMetadata().getLabels().get("app");
    }

    public ServiceAccount getServiceAccount(String namespace, String name) {
        return client.serviceAccounts().inNamespace(namespace).withName(name).get();
    }

    /**
     * Creates service account
     *
     * @param name      name of servcie account
     * @param namespace namespace
     * @return full name
     */
    public String createServiceAccount(String name, String namespace) {
        log.info("Create serviceaccount {} in namespace {}", name, namespace);
        client.serviceAccounts().inNamespace(namespace)
                .create(new ServiceAccountBuilder().withNewMetadata().withName(name).endMetadata().build());
        return "system:serviceaccount:" + namespace + ":" + name;
    }

    /**
     * Deletes service account
     *
     * @param name      name
     * @param namespace namesapce
     * @return full name
     */
    public String deleteServiceAccount(String name, String namespace) {
        log.info("Delete serviceaccount {} from namespace {}", name, namespace);
        client.serviceAccounts().inNamespace(namespace).withName(name).cascading(true).delete();
        return "system:serviceaccount:" + namespace + ":" + name;
    }

    /**
     * Returns service account token
     *
     * @param name      name
     * @param namespace namespace
     * @return token
     */
    public String getServiceaccountToken(String name, String namespace) {
        return new String(Base64.getDecoder().decode(client.secrets().inNamespace(namespace).list().getItems().stream()
                .filter(secret -> secret.getMetadata().getName().contains(name + "-token")).collect(Collectors.toList())
                .get(0).getData().get("token")), StandardCharsets.UTF_8);
    }

    /**
     * Creates pvc from resource
     *
     * @param namespace namespace
     * @param resources resources
     */
    public void createPvc(String namespace, PersistentVolumeClaim resources) {
        if (!pvcExists(namespace, resources.getMetadata().getName())) {
            PersistentVolumeClaim serRes = client.persistentVolumeClaims().inNamespace(namespace).create(resources);
            log.info("PVC {} created", serRes.getMetadata().getName());
        } else {
            log.info("PVC {} already exists", resources.getMetadata().getName());
        }
    }

    /**
     * Deletes pvc
     *
     * @param namespace namespace
     * @param pvcName   pvc name
     */
    public void deletePvc(String namespace, String pvcName) {
        client.persistentVolumeClaims().inNamespace(namespace).withName(pvcName).delete();
        log.info("PVC {} deleted", pvcName);
    }

    /**
     * Test if pvc already exists
     *
     * @param namespace namespace
     * @param pvcName   of pvc
     * @return boolean    private static final String OLM_NAMESPACE = "operators";
     */
    public boolean pvcExists(String namespace, String pvcName) {
        return client.persistentVolumeClaims().inNamespace(namespace).list().getItems().stream()
                .map(pvc -> pvc.getMetadata().getName()).collect(Collectors.toList()).contains(pvcName);
    }

    /**
     * Creates pvc from resource
     *
     * @param namespace namespace
     * @param resources resources
     */
    public void createSecret(String namespace, Secret resources) {
        if (!secretExists(namespace, resources.getMetadata().getName())) {
            Secret serRes = client.secrets().inNamespace(namespace).create(resources);
            log.info("Secret {} created", serRes.getMetadata().getName());
        } else {
            log.info("Secret {} already exists", resources.getMetadata().getName());
        }
    }

    /**
     * Deletes pvc
     *
     * @param namespace namespace
     * @param secret    secret name
     */
    public void deleteSecret(String namespace, String secret) {
        client.secrets().inNamespace(namespace).withName(secret).cascading(true).delete();
        log.info("Secret {} deleted", secret);
    }

    /**
     * Test if secret already exists
     *
     * @param namespace namespace
     * @param secret    of pvc
     * @return boolean
     */
    public boolean secretExists(String namespace, String secret) {
        return client.secrets().inNamespace(namespace).list().getItems().stream()
                .map(sec -> sec.getMetadata().getName()).collect(Collectors.toList()).contains(secret);
    }

    public CustomResourceDefinition getCRD(String name) {
        return client.customResourceDefinitions().withName(name).get();
    }

    public PodDisruptionBudget getPodDisruptionBudget(String namespace, String name) {
        return client.policy().podDisruptionBudget().inNamespace(namespace).withName(name).get();
    }

    public void deletePodDisruptionBudget(String namespace, String name) {
        client.policy().podDisruptionBudget().inNamespace(namespace).withName(name).delete();
    }

    public abstract void createExternalEndpoint(String name, String namespace, Service service, ServicePort targetPort);

    public abstract void deleteExternalEndpoint(String namespace, String name);

    public abstract String getOlmNamespace();

    public void awaitPodsReady(String namespace, TimeoutBudget budget) throws InterruptedException {
        List<Pod> unready;
        do {
            unready = new ArrayList<>(listPods(namespace));
            unready.removeIf(p -> TestUtils.isPodReady(p, true));

            if (!unready.isEmpty()) {
                Thread.sleep(1000L);
            }
        } while (!unready.isEmpty() && budget.timeLeft() > 0);

        if (!unready.isEmpty()) {
            fail(String.format(" %d pod(s) still unready in namespace : %s", unready.size(), namespace));
        }
    }

    @FunctionalInterface
    public static interface AfterInput {
        public void afterInput(final OutputStream remoteInput) throws IOException;
    }

    /**
     * Run a remote command, with an input stream as remote side input.
     * <p>
     * <strong>Note:</strong> The remote command must exit by itself in the specified timeout. If you
     * are attached to some kind of "shell", you can use the {@code afterInput} handler so send some
     * "exit" command after the input stream has been transmitted.
     *
     * @param podAccess Access to the pod.
     * @param input The input to stream to the remote side "stdin".
     * @param afterInput Called after all the input has been streamed. May be used for an additional
     *        command. There is no need to flush or close the stream, this will be done automatically.
     * @param timeout The time to wait for the remote side to exit.
     * @param command The command to execute in the pod.
     * @throws IOException If any of the calls throws an {@link IOException}.
     * @throws TimeoutException When waiting for the command times out.
     * @throws InterruptedException If waiting for the command fails.
     * @return The output from the {@code stdout} stream of the application.
     */
    public static String executeWithInput(final PodResource<Pod, DoneablePod> podAccess, final InputStream input, final AfterInput afterInput, final Duration timeout,
            final String... command) throws IOException, InterruptedException, TimeoutException {

        final ByteArrayOutputStream errorChannel = new ByteArrayOutputStream();
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final Semaphore execWait = new Semaphore(0);
        try (
                final ExecWatch exec = podAccess
                        .redirectingInput()
                        .writingOutput(stdout)
                        .writingError(System.err)
                        .writingErrorChannel(errorChannel)
                        .usingListener(new ExecListener() {

                            @Override
                            public void onOpen(Response response) {
                                log.info("Channel opened: {}", response);
                            }

                            @Override
                            public void onFailure(Throwable t, Response response) {
                                log.info("Failed to execute: {}", response, t);
                                execWait.release();
                            }

                            @Override
                            public void onClose(int code, String reason) {
                                log.info("Channel closed - code: {}, reason: '{}'", code, reason);
                                execWait.release();
                            }
                        })
                        .exec(command);) {

            var in = exec.getInput();

            if (input != null) {
                // transfer the main content
                log.info("Send content");
                input.transferTo(in);
            } else {
                log.info("No input to send");
            }

            if (afterInput != null) {
                // ensure the provided code does not close the input stream
                afterInput.afterInput(new CloseShieldOutputStream(in));
                // flush everything, to ensure it was written
                in.flush();
            }

            // wait for remote side to close, or local timeout
            log.info("Wait for channel to close!");
            if (!execWait.tryAcquire(timeout.toMillis() + 1, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Failed to wait for command to finish");
            }
            log.info("Done!");
        }

        // eval error channel

        final JsonObject result = new JsonObject(Buffer.buffer(errorChannel.toByteArray()));
        final String status = result.getString("status");
        if (status == null) {
            throw new IllegalStateException("'status' is missing in response");
        }
        if (!status.equals("Success")) {
            throw new IllegalStateException(String.format("Command failed: ", result.getString("message")));
        }

        final String stdoutString = stdout.toString(StandardCharsets.UTF_8);
        log.info("Output: {}", stdoutString);
        return stdoutString;
    }

    public List<Route> listRoutes(String namespace, Map<String, String> labels) {
        throw new UnsupportedOperationException();
    }

}
