/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.systemtest.iot;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.enmasse.systemtest.Endpoint;
import io.enmasse.systemtest.UserCredentials;
import io.enmasse.systemtest.mqtt.MqttClientFactory;
import io.enmasse.systemtest.time.TimeoutBudget;
import io.enmasse.systemtest.time.WaitPhase;
import io.enmasse.systemtest.utils.TestUtils;
import io.vertx.core.buffer.Buffer;

public class MqttAdapterClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MqttAdapterClient.class);
    private IMqttAsyncClient adapterClient;

    private MqttAdapterClient(final IMqttAsyncClient adapterClient) {
        this.adapterClient = adapterClient;
    }

    @Override
    public void close() throws Exception {
        this.adapterClient.close();
    }

    public boolean sendQos0(MessageSendTester.Type type, Buffer json, Duration timeout) {
        return send(0, type, json, timeout);
    }

    public boolean sendQos1(MessageSendTester.Type type, Buffer json, Duration timeout) {
        return send(1, type, json, timeout);
    }

    public boolean send(int qos, final MessageSendTester.Type type, final Buffer json, final Duration timeout) {
        final MqttMessage message = new MqttMessage(json.getBytes());
        message.setQos(qos);
        try {
            final IMqttDeliveryToken token = this.adapterClient.publish(type.type().address(), message);
            if (qos <= 0) {
                return true; // we know nothing with QoS 0
            }
            token.waitForCompletion(timeout.toMillis());
        } catch (Exception e) {
            log.info("Failed to send MQTT message", e);
            return false;
        }
        return true;
    }

    public static MqttAdapterClient create(final Endpoint endpoint, final String deviceId, final String deviceAuthId, final String tenantId, final String devicePassword)
            throws Exception {

        final MqttConnectOptions mqttOptions = new MqttConnectOptions();
        mqttOptions.setAutomaticReconnect(true);
        mqttOptions.setConnectionTimeout(60);
        // do not reject due to "inflight" messages. Note: this will allocate an array of that size.
        mqttOptions.setMaxInflight(16 * 1024);
        mqttOptions.setHttpsHostnameVerificationEnabled(false);

        var adapterClient = new MqttClientFactory(null,
                new UserCredentials(deviceAuthId + "@" + tenantId, devicePassword))
                        .build()
                        .clientId(deviceId)
                        .endpoint(endpoint)
                        .mqttConnectionOptions(mqttOptions)
                        .createAsync();

        try {
            TestUtils.waitUntilCondition("Successfully connect to mqtt adapter", phase -> {
                try {
                    adapterClient.connect().waitForCompletion(10_000);
                    return true;
                } catch (MqttException mqttException) {
                    if (phase == WaitPhase.LAST_TRY) {
                        log.error("Error waiting to connect mqtt adapter", mqttException);
                    }
                    return false;
                }
            }, new TimeoutBudget(1, TimeUnit.MINUTES));
        } catch (Exception e) {
            try {
                adapterClient.close();
            } catch (Exception e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }

        log.info("Connection to mqtt adapter succeeded");

        return new MqttAdapterClient(adapterClient);
    }
}
