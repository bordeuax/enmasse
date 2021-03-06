/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package router

import (
	"encoding/json"
	v1beta2 "github.com/enmasseproject/enmasse/pkg/apis/enmasse/v1beta2"
)

type routerConfig struct {
	entities [][]interface{}
}

func generateConfig(router *v1beta2.MessagingInfraSpecRouter) routerConfig {
	return routerConfig{
		entities: [][]interface{}{
			[]interface{}{
				// Generic configuration settings
				"router",
				map[string]interface{}{
					"workerThreads":           4, // TODO: Set from variable
					"timestampsInUTC":         true,
					"defaultDistribution":     "unavailable",
					"mode":                    "interior",
					"id":                      "${HOSTNAME}",
					"allowResumableLinkRoute": false,
				},
			},
			[]interface{}{
				// Internal TLS profile used by all internal components
				"sslProfile",
				map[string]interface{}{
					"name":           "infra_tls",
					"privateKeyFile": "/etc/enmasse-certs/tls.key",
					"certFile":       "/etc/enmasse-certs/tls.crt",
					"caCertFile":     "/etc/enmasse-certs/ca.crt",
				},
			},
			[]interface{}{
				// Listener for inter-router traffic. Should not be used by other services.
				"listener",
				map[string]interface{}{
					"host":             "0.0.0.0",
					"port":             55672,
					"requireSsl":       true,
					"role":             "inter-router",
					"saslMechanisms":   "EXTERNAL",
					"sslProfile":       "infra_tls",
					"authenticatePeer": true,
				},
			},
			[]interface{}{
				// Listener for internal management commands.
				"listener",
				map[string]interface{}{
					"host":             "0.0.0.0",
					"port":             55671,
					"requireSsl":       true,
					"saslMechanisms":   "EXTERNAL",
					"sslProfile":       "infra_tls",
					"authenticatePeer": true,
				},
			},
			[]interface{}{
				// Localhost listener for admin access
				"listener",
				map[string]interface{}{
					"host":             "0.0.0.0",
					"port":             7777,
					"authenticatePeer": false,
				},
			},
			[]interface{}{
				// Localhost listener for liveness probe and metrics
				"listener",
				map[string]interface{}{
					"host":             "127.0.0.1",
					"port":             7778,
					"authenticatePeer": false,
					"http":             true,
					"metrics":          true,
					"healthz":          true,
					"websockets":       false,
					"httpRootDir":      "invalid",
				},
			},
		},
	}
}

func serializeConfig(config *routerConfig) ([]byte, error) {
	return json.Marshal(config.entities)
}
