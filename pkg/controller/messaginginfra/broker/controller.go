/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package broker

import (
	"context"
	"fmt"

	logr "github.com/go-logr/logr"

	v1beta2 "github.com/enmasseproject/enmasse/pkg/apis/enmasse/v1beta2"
	"github.com/enmasseproject/enmasse/pkg/controller/messaginginfra/cert"
	"github.com/enmasseproject/enmasse/pkg/controller/messaginginfra/common"
	"github.com/enmasseproject/enmasse/pkg/util"
	"github.com/enmasseproject/enmasse/pkg/util/install"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	resource "k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	intstr "k8s.io/apimachinery/pkg/util/intstr"

	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
)

type BrokerController struct {
	client         client.Client
	scheme         *runtime.Scheme
	certController *cert.CertController
}

func NewBrokerController(client client.Client, scheme *runtime.Scheme, certController *cert.CertController) *BrokerController {
	return &BrokerController{
		client:         client,
		scheme:         scheme,
		certController: certController,
	}
}

func getBrokerLabels(infra *v1beta2.MessagingInfra) map[string]string {
	labels := make(map[string]string, 0)
	labels[common.LABEL_INFRA] = infra.Name
	labels["component"] = "broker"
	labels["app"] = "enmasse"

	return labels
}

/*
 * Reconciles the broker instances for an instance of shared infrastructure.
 *
 * Each instance of a broker is created as a statefulset. If we want to support HA in the future, each statefulset can use replicas to configure HA.
 */
func (b *BrokerController) ReconcileBrokers(ctx context.Context, logger logr.Logger, infra *v1beta2.MessagingInfra) ([]string, error) {
	setDefaultBrokerScalingStrategy(&infra.Spec.Broker)
	logger.Info("Reconciling brokers", "broker", infra.Spec.Broker)

	labels := getBrokerLabels(infra)

	// Update broker condition
	brokersCreated := infra.Status.GetMessagingInfraCondition(v1beta2.MessagingInfraBrokersCreated)

	allHosts := make([]string, 0)
	err := common.WithConditionUpdate(brokersCreated, func() error {
		brokers := appsv1.StatefulSetList{}
		err := b.client.List(ctx, &brokers, client.InNamespace(infra.Namespace), client.MatchingLabels(labels))
		if err != nil {
			return err
		}

		hosts := make(map[string]bool, 0)
		for _, broker := range brokers.Items {
			err := b.reconcileBroker(ctx, logger, infra, &broker)
			if err != nil {
				return err
			}

			hosts[toHost(&broker)] = true
		}

		toCreate := numBrokersToCreate(infra.Spec.Broker.ScalingStrategy, brokers.Items)
		if toCreate > 0 {
			logger.Info("Creating brokers", "toCreate", toCreate)
			for i := 0; i < toCreate; i++ {
				broker := &appsv1.StatefulSet{
					ObjectMeta: metav1.ObjectMeta{Namespace: infra.Namespace, Name: fmt.Sprintf("broker-%s-%s", infra.Name, util.RandomBrokerName())},
				}
				err = b.reconcileBroker(ctx, logger, infra, broker)
				if err != nil {
					return err
				}
				hosts[toHost(broker)] = true
			}

		}

		toDelete := numBrokersToDelete(infra.Spec.Broker.ScalingStrategy, brokers.Items)
		if toDelete > 0 {
			logger.Info("Removing brokers", "toDelete", toDelete)
			for i := len(brokers.Items) - 1; toDelete > 0; i-- {
				err := b.client.Delete(ctx, &brokers.Items[i])
				if err != nil {
					return err
				}
				delete(hosts, toHost(&brokers.Items[i]))
				toDelete--
			}
		}

		// Update discoverable brokers
		for host, _ := range hosts {
			allHosts = append(allHosts, host)
		}
		return nil
	})
	return allHosts, err
}

func toHost(broker *appsv1.StatefulSet) string {
	return fmt.Sprintf("%s-0.%s.%s.svc", broker.Name, broker.Name, broker.Namespace)
}

func (b *BrokerController) reconcileBroker(ctx context.Context, logger logr.Logger, infra *v1beta2.MessagingInfra, statefulset *appsv1.StatefulSet) error {
	logger.Info("Creating broker", "name", statefulset.Name)

	certSecretName := cert.GetCertSecretName(statefulset.Name)

	_, err := controllerutil.CreateOrUpdate(ctx, b.client, statefulset, func() error {
		if err := controllerutil.SetControllerReference(infra, statefulset, b.scheme); err != nil {
			return err
		}

		install.ApplyStatefulSetDefaults(statefulset, "broker", statefulset.Name)
		statefulset.Labels[common.LABEL_INFRA] = infra.Name
		statefulset.Spec.Template.Labels[common.LABEL_INFRA] = infra.Name

		statefulset.Spec.ServiceName = statefulset.Name
		statefulset.Spec.Replicas = int32ptr(1)

		initContainers, err := install.ApplyContainerWithError(statefulset.Spec.Template.Spec.InitContainers, "broker-init", func(container *corev1.Container) error {
			err := install.ApplyContainerImage(container, "broker-plugin", infra.Spec.Broker.InitImage)
			if err != nil {
				return err
			}

			install.ApplyEnvSimple(container, "INFRA_NAME", infra.Name)
			install.ApplyEnvSimple(container, "CERT_DIR", "/etc/enmasse-certs")
			install.ApplyEnvSimple(container, "AMQ_NAME", "data")
			install.ApplyEnvSimple(container, "HOME", "/var/run/artemis")

			// TODO:
			install.ApplyEnvSimple(container, "ADDRESS_SPACE_TYPE", "shared")
			install.ApplyEnvSimple(container, "GLOBAL_MAX_SIZE", "-1")
			install.ApplyEnvSimple(container, "ADDRESS_FULL_POLICY", "FAIL")

			install.ApplyVolumeMountSimple(container, "data", "/var/run/artemis", false)
			install.ApplyVolumeMountSimple(container, "init", "/opt/apache-artemis/custom", false)
			install.ApplyVolumeMountSimple(container, "certs", "/etc/enmasse-certs", false)
			return nil
		})
		if err != nil {
			return err
		}
		statefulset.Spec.Template.Spec.InitContainers = initContainers

		containers, err := install.ApplyContainerWithError(statefulset.Spec.Template.Spec.Containers, "broker", func(container *corev1.Container) error {
			err := install.ApplyContainerImage(container, "broker", infra.Spec.Broker.Image)
			if err != nil {
				return err
			}

			install.ApplyEnvSimple(container, "INFRA_NAME", infra.Name)
			install.ApplyEnvSimple(container, "CERT_DIR", "/etc/enmasse-certs")
			install.ApplyEnvSimple(container, "AMQ_NAME", "data")
			install.ApplyEnvSimple(container, "HOME", "/var/run/artemis")
			container.Command = []string{"/opt/apache-artemis/custom/bin/launch-broker.sh"}

			// TODO:
			install.ApplyEnvSimple(container, "ADDRESS_SPACE_TYPE", "shared")
			install.ApplyEnvSimple(container, "GLOBAL_MAX_SIZE", "-1")
			install.ApplyEnvSimple(container, "ADDRESS_FULL_POLICY", "FAIL")

			install.ApplyVolumeMountSimple(container, "data", "/var/run/artemis", false)
			install.ApplyVolumeMountSimple(container, "init", "/opt/apache-artemis/custom", false)
			install.ApplyVolumeMountSimple(container, "certs", "/etc/enmasse-certs", false)

			container.Ports = []corev1.ContainerPort{
				{
					ContainerPort: 5671,
					Name:          "amqps",
				},
			}

			return nil
		})
		if err != nil {
			return err
		}
		statefulset.Spec.Template.Spec.Containers = containers

		install.ApplyEmptyDirVolume(&statefulset.Spec.Template.Spec, "init")
		install.ApplySecretVolume(&statefulset.Spec.Template.Spec, "certs", certSecretName)

		statefulset.Spec.VolumeClaimTemplates = []corev1.PersistentVolumeClaim{
			corev1.PersistentVolumeClaim{
				ObjectMeta: metav1.ObjectMeta{Name: "data"},
				Spec: corev1.PersistentVolumeClaimSpec{
					AccessModes: []corev1.PersistentVolumeAccessMode{
						corev1.ReadWriteOnce,
					},

					Resources: corev1.ResourceRequirements{
						Requests: map[corev1.ResourceName]resource.Quantity{"storage": *resource.NewScaledQuantity(2, resource.Giga)},
					},
				},
			},
		}
		return nil
	})
	if err != nil {
		return err
	}

	// Reconcile service
	service := &corev1.Service{
		ObjectMeta: metav1.ObjectMeta{Namespace: infra.Namespace, Name: statefulset.Name},
	}
	_, err = controllerutil.CreateOrUpdate(ctx, b.client, service, func() error {
		if err := controllerutil.SetControllerReference(infra, service, b.scheme); err != nil {
			return err
		}
		install.ApplyServiceDefaults(service, "broker", infra.Name)
		service.Spec.ClusterIP = "None"
		service.Spec.Selector = statefulset.Spec.Template.Labels
		service.Spec.Ports = []corev1.ServicePort{
			{
				Port:       5671,
				Protocol:   corev1.ProtocolTCP,
				TargetPort: intstr.FromString("amqps"),
				Name:       "amqps",
			},
		}

		return nil
	})
	if err != nil {
		return err
	}

	_, err = b.certController.ReconcileCert(ctx, logger, infra, statefulset, toHost(statefulset))
	if err != nil {
		return err
	}

	return nil
}

func int32ptr(v int32) *int32 {
	return &v
}

func setDefaultBrokerScalingStrategy(broker *v1beta2.MessagingInfraSpecBroker) {
	// Set static scaler by default
	if broker.ScalingStrategy == nil {
		broker.ScalingStrategy = &v1beta2.MessagingInfraSpecBrokerScalingStrategy{
			Static: &v1beta2.MessagingInfraSpecBrokerScalingStrategyStatic{
				PoolSize: 1,
			},
		}
	}
}

func numBrokersToCreate(strategy *v1beta2.MessagingInfraSpecBrokerScalingStrategy, brokers []appsv1.StatefulSet) int {
	if strategy.Static != nil {
		if int(strategy.Static.PoolSize) > len(brokers) {
			return int(strategy.Static.PoolSize) - len(brokers)
		}
	}
	// Does not normally happen. If it does make sure nothing gets created.
	return 0
}

func numBrokersToDelete(strategy *v1beta2.MessagingInfraSpecBrokerScalingStrategy, brokers []appsv1.StatefulSet) int {
	if strategy.Static != nil {
		if int(strategy.Static.PoolSize) < len(brokers) {
			return len(brokers) - int(strategy.Static.PoolSize)
		}
	}
	// Does not normally happen. If it does make sure nothing gets deleted.
	return 0
}
