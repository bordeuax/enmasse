/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.listener;

import io.enmasse.systemtest.EnmasseInstallType;
import io.enmasse.systemtest.Environment;
import io.enmasse.systemtest.bases.ThrowableRunner;
import io.enmasse.systemtest.info.TestInfo;
import io.enmasse.systemtest.logs.CustomLogger;
import io.enmasse.systemtest.logs.GlobalLogCollector;
import io.enmasse.systemtest.manager.IsolatedIoTManager;
import io.enmasse.systemtest.manager.IsolatedResourcesManager;
import io.enmasse.systemtest.manager.SharedIoTManager;
import io.enmasse.systemtest.manager.SharedResourceManager;
import io.enmasse.systemtest.messaginginfra.ResourceManager;
import io.enmasse.systemtest.operator.EnmasseOperatorManager;
import io.enmasse.systemtest.platform.KubeCMDClient;
import io.enmasse.systemtest.platform.Kubernetes;
import io.enmasse.systemtest.platform.cluster.KubeClusterManager;
import io.enmasse.systemtest.utils.TestUtils;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.slf4j.Logger;

/**
 * This class implements a variety of junit callbacks and orchestates the full lifecycle of the operator installation
 * used for the test suite
 */
public class JunitCallbackListener implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler,
        AfterEachCallback, BeforeEachCallback, BeforeAllCallback, AfterAllCallback {
    private static final Logger LOGGER = CustomLogger.getLogger();
    private static Environment env = Environment.getInstance();
    private Kubernetes kubernetes = Kubernetes.getInstance();
    private TestInfo testInfo = TestInfo.getInstance();
    private IsolatedResourcesManager isolatedResourcesManager = IsolatedResourcesManager.getInstance();
    private SharedResourceManager sharedResourcesManager = SharedResourceManager.getInstance();
    private SharedIoTManager sharedIoTManager = SharedIoTManager.getInstance();
    private IsolatedIoTManager isolatedIoTManager = IsolatedIoTManager.getInstance();
    private EnmasseOperatorManager operatorManager = EnmasseOperatorManager.getInstance();
    private static Exception beforeAllException; //TODO remove it after upgrade to surefire plugin 3.0.0-M5

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        testInfo.setCurrentTestClass(context);
        ResourceManager.getInstance().setClassResources();
        KubeClusterManager.getInstance().setClassConfigurations();
        try { //TODO remove it after upgrade to surefire plugin 3.0.0-M5
            handleCallBackError("Callback before all", context, () -> {
                if (testInfo.isUpgradeTest()) {
                    if (operatorManager.isEnmasseBundleDeployed()) {
                        operatorManager.deleteEnmasseBundle();
                    }
                    LOGGER.info("Enmasse is not installed because next test is {}", context.getDisplayName());
                } else if (testInfo.isOLMTest()) {
                    LOGGER.info("Test is OLM");
                    if (operatorManager.isEnmasseOlmDeployed()) {
                        operatorManager.deleteEnmasseOlm();
                    }
                    if (operatorManager.isEnmasseBundleDeployed()) {
                        operatorManager.deleteEnmasseBundle();
                    }
                    operatorManager.installEnmasseOlm(testInfo.getOLMInstallationType());
                } else if (env.installType() == EnmasseInstallType.OLM) {
                    if (!operatorManager.isEnmasseOlmDeployed()) {
                        operatorManager.installEnmasseOlm();
                    }
                    if (!operatorManager.areExamplesApplied()) {
                        operatorManager.installExamplesBundleOlm();
                        operatorManager.waitUntilOperatorReadyOlm();
                    }
                } else if (testInfo.isTestSharedInfra()) {
                    if (operatorManager.isEnmasseBundleDeployed()) {
                        operatorManager.deleteEnmasseBundle();
                    } else if (operatorManager.isEnmasseOlmDeployed()) {
                        operatorManager.deleteEnmasseOlm();
                    }
                    operatorManager.installEnmasseSharedInfraBundle();
                } else {
                    if (!operatorManager.isEnmasseBundleDeployed()) {
                        operatorManager.installEnmasseBundle();
                    }
                    if (testInfo.isClassIoT()) {
                        operatorManager.installIoTOperator();
                    }
                }
            });
        } catch (Exception ex) {
            beforeAllException = ex; //TODO remove it after upgrade to surefire plugin 3.0.0-M5
        }
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) throws Exception {
        beforeAllException = null; //TODO remove it after upgrade to surefire plugin 3.0.0-M5
        handleCallBackError("Callback after all", extensionContext, () -> {
            if (!env.skipCleanup()) {
                ResourceManager.getInstance().deleteClassResources();
                KubeClusterManager.getInstance().restoreClassConfigurations();
            }
            if (env.skipCleanup() || env.skipUninstall()) {
                LOGGER.info("Skip cleanup/uninstall is set, enmasse and iot operators won't be deleted");
            } else if (testInfo.isOLMTest()) {
                LOGGER.info("Test is OLM");
                if (operatorManager.isEnmasseOlmDeployed()) {
                    operatorManager.deleteEnmasseOlm();
                }
            } else if (env.installType() == EnmasseInstallType.BUNDLE) {
                if (testInfo.isEndOfIotTests()) {
                    operatorManager.removeIoT();
                }
                if (operatorManager.isEnmasseOlmDeployed()) {
                    operatorManager.deleteEnmasseOlm();
                }
            }
        });
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        testInfo.setCurrentTest(context);
        ResourceManager.getInstance().setMethodResources();
        KubeClusterManager.getInstance().setMethodConfigurations();
        logPodsInInfraNamespace();
        if (beforeAllException != null) {
            throw beforeAllException;
        }
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) throws Exception {
        handleCallBackError("Callback after each", extensionContext, () -> {
            LOGGER.info("Teardown section: ");
            ResourceManager.getInstance().deleteMethodResources();
            KubeClusterManager.getInstance().restoreMethodConfigurations();
            if (testInfo.isTestShared()) {
                tearDownSharedResources();
            } else if (testInfo.isTestIoT()) {
                isolatedIoTManager.tearDown(testInfo.getActualTest());
            } else {
                tearDownCommonResources();
            }
        });
    }

    private void tearDownCommonResources() throws Exception {
        LOGGER.info("Admin resource manager teardown");
        isolatedResourcesManager.tearDown(testInfo.getActualTest());
        isolatedResourcesManager.unsetReuseAddressSpace();
        isolatedResourcesManager.deleteAddressspacesFromList();
    }

    private void tearDownSharedResources() throws Exception {
        if (testInfo.isAddressSpaceDeleteable() || testInfo.getActualTest().getExecutionException().isPresent()) {
            if (testInfo.isTestIoT()) {
                LOGGER.info("Teardown shared IoT!");
                sharedIoTManager.tearDown(testInfo.getActualTest());
            } else {
                LOGGER.info("Teardown shared!");
                sharedResourcesManager.tearDown(testInfo.getActualTest());
            }
        } else if (sharedResourcesManager.getSharedAddressSpace() != null) {
            sharedResourcesManager.tearDownShared();
        }
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        saveKubernetesState("Test execution", context, throwable);
    }

    @Override
    public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        saveKubernetesState("Test before all", context, throwable);
    }

    @Override
    public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        saveKubernetesState("Test before each", context, throwable);
    }

    @Override
    public void handleAfterEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        saveKubernetesState("Test after each", context, throwable);
    }

    @Override
    public void handleAfterAllMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        saveKubernetesState("Test after all", context, throwable);
    }

    private void handleCallBackError(String description, ExtensionContext context, ThrowableRunner runnable) throws Exception {
        try {
            runnable.run();
        } catch (Exception ex) {
            try {
                saveKubernetesState(description, context, ex);
            } catch (Throwable ignored) {
            }
            LOGGER.error("Exception captured in Junit callback", ex);
            throw ex;
        }
    }

    private void saveKubernetesState(String description, ExtensionContext extensionContext, Throwable throwable) throws Throwable {
        LOGGER.error("Test failed at {}", description);
        logPodsInInfraNamespace();
        if (env.isSkipSaveState()) {
            throw throwable;
        }
        GlobalLogCollector.saveInfraState(TestUtils.getFailedTestLogsPath(extensionContext));
        throw throwable;
    }

    private void logPodsInInfraNamespace() {
        KubeCMDClient.runOnCluster("get", "pods", "-n", kubernetes.getInfraNamespace(), "-o", "wide");
    }

}