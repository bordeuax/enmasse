/*
 * Copyright 2019-2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package util

import (
	"context"
	"encoding/json"
	"fmt"
	userapiv1 "github.com/openshift/api/user/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/version"
	"k8s.io/client-go/kubernetes"
	"net/url"
	"os"
	"regexp"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"strconv"
	"strings"
	"time"

	"sigs.k8s.io/controller-runtime/pkg/client/config"

	routev1 "github.com/openshift/client-go/route/clientset/versioned/typed/route/v1"
	logf "sigs.k8s.io/controller-runtime/pkg/runtime/log"
)

var (
	openshift  *bool
	openshift4 *bool
	apis       = make(map[string]bool, 0)
	log        = logf.Log.WithName("util")
)

const ConnectsTo = "app.openshift.io/connects-to"
const EnMasseOpenshiftEnvVar = "ENMASSE_OPENSHIFT"
const EnMasseOpenshift4EnvVar = "ENMASSE_OPENSHIFT4"

var UserGVK = userapiv1.GroupVersion.WithKind("User")

func IsOpenshift() bool {

	if openshift == nil {
		b := detectOpenshift()
		openshift = &b
	}

	return *openshift

}

// detect if we are running on openshift 4
func IsOpenshift4() bool {
	if openshift4 == nil {
		b := detectOpenshift4()
		openshift4 = &b
	}

	return *openshift4
}

func HasApi(gvk schema.GroupVersionKind) bool {
	if has, ok := apis[gvk.String()]; ok {
		return has
	}

	has := detectApi(gvk)
	apis[gvk.String()] = has
	return has
}

func detectApi(gvk schema.GroupVersionKind) bool {

	name := fmt.Sprintf("ENMASSE_HAS_API_%s_%s_%s", strings.ToUpper(gvk.Group), strings.ToUpper(gvk.Version), strings.ToUpper(gvk.Kind))

	value, ok := os.LookupEnv(name)
	if ok {
		log.Info("Set by env-var '" + name + "': " + value)
		return strings.ToLower(value) == "true"
	}

	cfg, err := config.GetConfig()
	if err != nil {
		log.Error(err, "Error getting config")
		return false
	}

	cli, err := client.New(cfg, client.Options{})
	if err != nil {
		log.Error(err, "Failed to create new client")
		return false
	}

	namespace, err := GetInfrastructureNamespace()
	if err != nil {
		log.Info("Infra namespace not defined, cannot detect api")
		return false
	}

	var probeApi func() error
	if gvk == UserGVK {
		// special case the User API as listing user will not normally be permitted (or possible)
		get := unstructured.Unstructured{}
		get.SetGroupVersionKind(gvk)
		probeApi = func() error {
			err := cli.Get(context.Background(), client.ObjectKey{Namespace: namespace, Name: "~"}, &get)
			log.V(2).Info(fmt.Sprintf("Body: %v", get))
			return err
		}
	} else {
		list := unstructured.UnstructuredList{}
		list.SetGroupVersionKind(gvk)

		probeApi = func() error {
			err := cli.List(context.Background(), &list, client.Limit(1), client.InNamespace(namespace))
			log.V(2).Info(fmt.Sprintf("Body: %v", list))
			return err
		}

	}

	get := unstructured.Unstructured{}
	get.SetGroupVersionKind(gvk)

	retries := 10
	for retries > 0 {

		log.Info("Start probing API", "groupVersionKind", gvk)
		err = probeApi()
		log.Info("Request error", "error", err)

		if err == nil {
			return true
		}

		se, ok := err.(*errors.StatusError)
		if ok {
			code := se.Status().Code
			log.Info(fmt.Sprintf("Response code: %d", code))
			if code != 503 {
				return code >= 200 && code < 300
			}
		}

		retries -= 1
		time.Sleep(10 * time.Second)

	}

	return false

}

func detectOpenshift() bool {

	log.Info("Detect if openshift is running")

	value, ok := os.LookupEnv(EnMasseOpenshiftEnvVar)
	if ok {
		log.Info("Set by env-var '" + EnMasseOpenshiftEnvVar + "': " + value)
		return strings.ToLower(value) == "true"
	}

	return HasApi(UserGVK)
}

func detectOpenshift4() bool {

	log.Info("Detect if openshift 4 is running")

	value, ok := os.LookupEnv(EnMasseOpenshift4EnvVar)
	if ok {
		log.Info("Set by env-var '" + EnMasseOpenshift4EnvVar + "': " + value)
		return strings.ToLower(value) == "true"
	}

	if !IsOpenshift() {
		// we not running on any version of OpenShift
		return false
	}

	cfg, err := config.GetConfig()
	if err != nil {
		log.Error(err, "Error getting config")
		return false
	}

	cli, err := kubernetes.NewForConfig(cfg)
	if err != nil {
		log.Error(err, "Error creating client")
		return false
	}

	var v *version.Info
	retries := 10
	for retries > 0 {
		v, err = cli.ServerVersion()
		if err == nil && v != nil {
			break
		}
		retries--
	}

	if err != nil || v == nil {
		log.Error(err, "Error getting kubernetes version")
		return false
	}

	log.Info("Version Info", "version", v, "major", v.Major, "minor", v.Minor)

	m, err := regexp.MatchString(`^\d+\+$`, v.Minor)
	if err != nil {
		panic(fmt.Sprintf("Failed to compile regexp for version check: %v", err))
	}
	if !m {
		log.Error(err, "Unknown kubernetes version format")
		return false
	}

	minor, err := strconv.ParseInt(strings.TrimRight(v.Minor, "+"), 10, 32)
	if err != nil {
		log.Error(err, "Error parsing kubernetes version")
		return false
	}

	return v.Major == "1" && minor > 11

}

func OpenshiftUri() (*url.URL, bool, error) {

	data, err := WellKnownOauthMetadata()
	if err != nil {
		log.Error(err, "Error getting well-known OAuth metadata: %v")
		return nil, false, err
	}

	openshiftUrl := data["issuer"].(string)
	rewritten := false
	// When oc cluster is run without a  --public-hostname= argument, openshiftUrl will refer to a loopback, which
	// cannot be used from within a pod.  This works around this problem.
	if openshiftUrl == "" || strings.Contains(openshiftUrl, "https://localhost:8443") || strings.Contains(openshiftUrl, "https://127.0.0.1:8443") {
		openshiftUrl = fmt.Sprintf("https://%s:%s", GetEnvOrDefault("KUBERNETES_SERVICE_HOST", "172.30.0.1"), GetEnvOrDefault("KUBERNETES_SERVICE_PORT", "443"))
		rewritten = true
	}

	u, err := url.Parse(openshiftUrl)
	if err != nil {
		return nil, false, err
	}
	return u, rewritten, nil

}

func WellKnownOauthMetadata() (map[string]interface{}, error) {

	cfg, err := config.GetConfig()
	if err != nil {
		log.Error(err, "Error getting config: %v")
		return nil, err
	}

	cli, err := routev1.NewForConfig(cfg)
	if err != nil {
		return nil, err
	}

	result := cli.RESTClient().Get().AbsPath("/.well-known/oauth-authorization-server").Do()
	if err := result.Error(); err != nil {
		return nil, err
	}
	ret, err := result.Raw()
	if err != nil {
		return nil, err
	}
	data := make(map[string]interface{})
	err = json.Unmarshal(ret, &data)
	if err != nil {
		return nil, err
	}

	return data, nil
}
