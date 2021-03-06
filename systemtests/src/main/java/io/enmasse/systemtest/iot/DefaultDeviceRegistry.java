/*
 * Copyright 2019-2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.systemtest.iot;

import io.enmasse.iot.model.v1.DeviceConnectionServiceConfig;
import io.enmasse.iot.model.v1.DeviceConnectionServiceConfigBuilder;
import io.enmasse.iot.model.v1.DeviceRegistryServiceConfig;
import io.enmasse.iot.model.v1.DeviceRegistryServiceConfigBuilder;
import io.enmasse.iot.model.v1.ExternalInfinispanDeviceConnectionServer;
import io.enmasse.iot.model.v1.ExternalInfinispanDeviceConnectionServerBuilder;
import io.enmasse.iot.model.v1.ExternalInfinispanDeviceRegistryServer;
import io.enmasse.iot.model.v1.ExternalInfinispanDeviceRegistryServerBuilder;
import io.enmasse.iot.model.v1.ExternalJdbcDeviceConnectionServer;
import io.enmasse.iot.model.v1.ExternalJdbcDeviceConnectionServerBuilder;
import io.enmasse.iot.model.v1.ExternalJdbcRegistryServer;
import io.enmasse.iot.model.v1.ExternalJdbcRegistryServerBuilder;
import io.enmasse.iot.model.v1.Mode;
import io.enmasse.iot.model.v1.ServicesConfig;
import io.enmasse.iot.model.v1.ServicesConfigBuilder;
import io.enmasse.systemtest.Endpoint;
import io.enmasse.systemtest.platform.apps.SystemtestsKubernetesApps;

public final class DefaultDeviceRegistry {

    private DefaultDeviceRegistry() {}

    public static ExternalInfinispanDeviceConnectionServer externalInfinispanConnectionServer(final Endpoint infinispanEndpoint) {
        var builder = new ExternalInfinispanDeviceConnectionServerBuilder()
                .withNewServer()
                .withHost(infinispanEndpoint.getHost())
                .withPort(infinispanEndpoint.getPort())
                .endServer();

        // credentials aligned with 'templates/iot/examples/infinispan/manual'
        builder = builder
                .editOrNewServer()
                .withUsername("app")
                .withPassword("test12")
                .withSaslRealm("ApplicationRealm")
                .withSaslServerName("hotrod")
                .endServer();

        return builder.build();
    }

    public static ExternalInfinispanDeviceRegistryServer externalInfinispanRegistryServer(final Endpoint infinispanEndpoint) {
        var builder = new ExternalInfinispanDeviceRegistryServerBuilder()
                .withNewServer()
                .withHost(infinispanEndpoint.getHost())
                .withPort(infinispanEndpoint.getPort())
                .endServer();

        // credentials aligned with 'templates/iot/examples/infinispan/manual'
        builder = builder
                .editOrNewServer()
                .withUsername("app")
                .withPassword("test12")
                .withSaslRealm("ApplicationRealm")
                .withSaslServerName("hotrod")
                .endServer();

        return builder.build();
    }

    public static ExternalJdbcRegistryServer externalPostgresRegistryServer(final Endpoint jdbcEndpoint, final Mode mode) {
        var builder = new ExternalJdbcRegistryServerBuilder()
                .withNewManagement()
                .withNewConnection()
                .withUrl(String.format("jdbc:postgresql://%s:%s/device-registry", jdbcEndpoint.getHost(), jdbcEndpoint.getPort()))
                .endConnection()
                .endManagement();

        // credentials aligned with 'templates/iot/examples/postgresql/deploy'
        builder = builder
                .editOrNewManagement()
                .editOrNewConnection()
                .withUsername("registry")
                .withPassword("user12")
                .endConnection()
                .endManagement();

        builder = builder
                .withMode(mode);

        return builder.build();
    }

    public static ExternalJdbcRegistryServer externalH2RegistryServer(final Endpoint jdbcEndpoint) {
        var builder = new ExternalJdbcRegistryServerBuilder()
                .withNewManagement()
                .withNewConnection()
                .withUrl(String.format("jdbc:h2:tcp://%s:%s//data/device-registry", jdbcEndpoint.getHost(), jdbcEndpoint.getPort()))
                .endConnection()
                .endManagement();

        // credentials aligned with 'templates/iot/examples/h2/deploy'
        builder = builder
                .editOrNewManagement()
                .editOrNewConnection()
                .withUsername("registry")
                .withPassword("user12")
                .endConnection()
                .endManagement();

        builder = builder
                .withMode(Mode.TABLE)

                .addNewExtension()
                .withNewContainer()
                .withName("ext-add-h2-driver")
                .withImage("quay.io/enmasse/h2-extension:1.4.200-1")
                .withImagePullPolicy("IfNotPresent")
                .addNewVolumeMount()
                .withName("extensions")
                .withMountPath("/ext")
                .endVolumeMount()
                .endContainer()

                .endExtension();

        return builder.build();
    }

    /**
     * Get instance for the default type of registry.
     *
     * @return A new configuration, for a storage which is already deployed.
     * @throws Exception In case the deployment of the backend failed.
     */
    public static ServicesConfig newDefaultInstance() throws Exception {
       return newPostgresTreeBased();
    }

    /**
     * Delete the server which got created by {@link #newDefaultInstance()}.
     */
    public static void deleteDefaultServer() throws Exception {
        // align with newDefaultInstance
        SystemtestsKubernetesApps.deletePostgresqlServer();
    }

    public static ServicesConfig newPostgresBased(final Mode mode) throws Exception {
        var jdbcEndpoint = SystemtestsKubernetesApps.deployPostgresqlServer(mode);

        return new ServicesConfigBuilder()
                .withDeviceConnection(newPostgresBasedConnection(jdbcEndpoint))
                .withDeviceRegistry(newPostgresBasedRegistry(jdbcEndpoint, mode))
                .build();
    }

    public static ServicesConfig newPostgresTreeBased() throws Exception {
        return newPostgresBased(Mode.JSON_TREE);
    }

    public static ServicesConfig newPostgresFlatBased() throws Exception {
        return newPostgresBased(Mode.JSON_FLAT);
    }

    public static ServicesConfig newPostgresTableBased() throws Exception {
        return newPostgresBased(Mode.TABLE);
    }

    public static ServicesConfig newH2Based() throws Exception {
        var jdbcEndpoint = SystemtestsKubernetesApps.deployH2Server();

        return new ServicesConfigBuilder()
                .withDeviceConnection(newH2BasedConnection(jdbcEndpoint))
                .withDeviceRegistry(newH2BasedRegistry(jdbcEndpoint))
                .build();
    }

    public static ServicesConfig newInfinispanBased() throws Exception {
        var infinispanEndpoint = SystemtestsKubernetesApps.deployInfinispanServer();

        return new ServicesConfigBuilder()

                .withNewDeviceConnection()
                .withNewInfinispan()
                .withNewServer()
                .withExternal(externalInfinispanConnectionServer(infinispanEndpoint))
                .endServer()
                .endInfinispan()
                .endDeviceConnection()

                .withNewDeviceRegistry()
                .withNewInfinispan()
                .withNewServer()
                .withExternal(externalInfinispanRegistryServer(infinispanEndpoint))
                .endServer()
                .endInfinispan()
                .endDeviceRegistry()

                .build();

    }

    public static DeviceRegistryServiceConfig newPostgresBasedRegistry(final Endpoint jdbcEndpoint, final Mode mode) throws Exception {
        return new DeviceRegistryServiceConfigBuilder()
                .withNewJdbc()
                .withNewServer()

                .withExternal(externalPostgresRegistryServer(jdbcEndpoint, mode))

                .endServer()
                .endJdbc()
                .build();
    }

    public static DeviceRegistryServiceConfig newH2BasedRegistry(final Endpoint jdbcEndpoint) throws Exception {
        return new DeviceRegistryServiceConfigBuilder()
                .withNewJdbc()
                .withNewServer()

                .withExternal(externalH2RegistryServer(jdbcEndpoint))

                .endServer()
                .endJdbc()
                .build();
    }


    private static ExternalJdbcDeviceConnectionServer externalPostgresConnectionServer(final Endpoint jdbcEndpoint) {
        var builder = new ExternalJdbcDeviceConnectionServerBuilder()
                .withUrl(String.format("jdbc:postgresql://%s:%s/device-registry", jdbcEndpoint.getHost(), jdbcEndpoint.getPort()));

        // credentials aligned with 'templates/iot/examples/postgresql/deploy'
        builder = builder
                .withUsername("registry")
                .withPassword("user12");

        return builder.build();
    }

    private static ExternalJdbcDeviceConnectionServer externalH2ConnectionServer(final Endpoint jdbcEndpoint) {
        var builder = new ExternalJdbcDeviceConnectionServerBuilder()
                .withUrl(String.format("jdbc:h2:tcp://%s:%s//data/device-registry", jdbcEndpoint.getHost(), jdbcEndpoint.getPort()));

        // credentials aligned with 'templates/iot/examples/h2/deploy'
        builder = builder
                .withUsername("registry")
                .withPassword("user12");

        builder = builder

                .addNewExtension()
                .withNewContainer()
                .withName("ext-add-h2-driver")
                .withImage("quay.io/enmasse/h2-extension:1.4.200-1")
                .withImagePullPolicy("IfNotPresent")
                .addNewVolumeMount()
                .withName("extensions")
                .withMountPath("/ext")
                .endVolumeMount()
                .endContainer()

                .endExtension();

        return builder.build();
    }

    /*
    public static DeviceRegistryServiceConfig newInfinispanBased() throws Exception {
        var infinispanEndpoint = SystemtestsKubernetesApps.deployInfinispanServer();
        return new DeviceRegistryServiceConfigBuilder()
                .withNewInfinispan()
                .withNewServer()

                .withExternal(externalInfinispanRegistryServer(infinispanEndpoint))

                .endServer()
                .endInfinispan()
                .build();
    }

    public static DeviceRegistryServiceConfig newFileBased() {
        return new DeviceRegistryServiceConfigBuilder()
                .withNewFile()
                .withNumberOfDevicesPerTenant(100_000)
                .endFile()
                .build();
    }
    */

    public static DeviceConnectionServiceConfig newPostgresBasedConnection(final Endpoint jdbcEndpoint) throws Exception {
        return new DeviceConnectionServiceConfigBuilder()
                .withNewJdbc()
                .withNewServer()

                .withExternal(externalPostgresConnectionServer(jdbcEndpoint))

                .endServer()
                .endJdbc()
                .build();
    }

    public static DeviceConnectionServiceConfig newH2BasedConnection(final Endpoint jdbcEndpoint) throws Exception {
        return new DeviceConnectionServiceConfigBuilder()
                .withNewJdbc()
                .withNewServer()

                .withExternal(externalH2ConnectionServer(jdbcEndpoint))

                .endServer()
                .endJdbc()
                .build();
    }


}
