/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.mqtt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.PlottableDecodeEvent;
import io.github.dsheirer.preference.mqtt.MqttPreference;
import io.github.dsheirer.protocol.Protocol;
import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.jdesktop.swingx.mapviewer.GeoPosition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishes position reports through an embedded MQTT broker and verifies what a subscriber receives.
 */
public class GpsMqttPublisherTest
{
    private static final String TOPIC = "test/dmr/gps";
    private Server mBroker;
    private String mServerUri;
    private MqttClient mSubscriber;
    private final BlockingQueue<String> mReceived = new LinkedBlockingQueue<>();

    /**
     * Preference stub that doesn't touch the user's stored preferences.
     */
    private class TestPreference extends MqttPreference
    {
        private final Set<Integer> mDestinationIds;

        TestPreference(Set<Integer> destinationIds)
        {
            super(null);
            mDestinationIds = destinationIds;
        }

        @Override public boolean isEnabled() {return true;}
        @Override public String getServer() {return mServerUri;}
        @Override public String getClientId() {return "sdrtrunk-publisher-test";}
        @Override public String getUserName() {return "user";}
        @Override public String getPassword() {return "secret";}
        @Override public String getTopic() {return TOPIC;}
        @Override public Set<Integer> getDestinationIdFilter() {return mDestinationIds;}
    }

    @BeforeEach
    void startBroker() throws Exception
    {
        int port;
        try(ServerSocket socket = new ServerSocket(0))
        {
            port = socket.getLocalPort();
        }

        Properties properties = new Properties();
        properties.setProperty("host", "127.0.0.1");
        properties.setProperty("port", String.valueOf(port));
        properties.setProperty("allow_anonymous", "true");
        properties.setProperty("persistence_enabled", "false");
        mBroker = new Server();
        mBroker.startServer(new MemoryConfig(properties));
        mServerUri = "tcp://127.0.0.1:" + port;

        mSubscriber = GpsMqttPublisher.connect(mServerUri, "sdrtrunk-subscriber-test", "", "");
        mSubscriber.subscribe(TOPIC, 1, (topic, message) ->
                mReceived.add(new String(message.getPayload(), StandardCharsets.UTF_8)));
    }

    @AfterEach
    void stopBroker()
    {
        GpsMqttPublisher.closeQuietly(mSubscriber);
        mBroker.stopServer();
    }

    private static PlottableDecodeEvent position(int from, IdentifierCollection identifiers)
    {
        return PlottableDecodeEvent.plottableBuilder(DecodeEventType.GPS, 1700000000000L)
                .protocol(Protocol.DMR)
                .identifiers(identifiers)
                .location(new GeoPosition(51.5, -0.12))
                .speed(18.52)
                .heading(90)
                .details("LOCATION:51.50000 -0.12000")
                .build();
    }

    private static PlottableDecodeEvent positionToRadio(int from, int to)
    {
        return position(from, new IdentifierCollection(List.of(DMRRadio.createFrom(from), DMRRadio.createTo(to))));
    }

    @Test
    void publishesPositionAsJson() throws Exception
    {
        GpsMqttPublisher publisher = new GpsMqttPublisher(new TestPreference(Set.of()));

        try
        {
            publisher.receive(positionToRadio(2345678, 5057));
            String payload = mReceived.poll(10, TimeUnit.SECONDS);
            assertNotNull(payload, "No MQTT message received");

            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            assertEquals("GPS", json.get("type").getAsString());
            assertEquals(2345678, json.get("from").getAsInt());
            assertEquals(5057, json.get("to").getAsInt());
            assertEquals(51.5, json.get("latitude").getAsDouble(), 0.000001);
            assertEquals(-0.12, json.get("longitude").getAsDouble(), 0.000001);
            assertEquals(18.52, json.get("speed_kph").getAsDouble(), 0.000001);
            assertEquals(90, json.get("heading").getAsDouble(), 0.000001);
            assertEquals("2023-11-14T22:13:20Z", json.get("timestamp").getAsString());
        }
        finally
        {
            publisher.stop();
        }
    }

    @Test
    void filtersOnDestinationId() throws Exception
    {
        GpsMqttPublisher publisher = new GpsMqttPublisher(new TestPreference(Set.of(5057)));

        try
        {
            publisher.receive(positionToRadio(1111111, 999));
            publisher.receive(position(2222222, new IdentifierCollection(List.of(DMRRadio.createFrom(2222222),
                    DMRTalkgroup.create(5057)))));

            String payload = mReceived.poll(10, TimeUnit.SECONDS);
            assertNotNull(payload, "No MQTT message received");
            assertEquals(2222222, JsonParser.parseString(payload).getAsJsonObject().get("from").getAsInt());
            assertNull(mReceived.poll(1, TimeUnit.SECONDS), "Report sent to a filtered ID was published");
        }
        finally
        {
            publisher.stop();
        }
    }

    @Test
    void ignoresNonDmrEvents() throws Exception
    {
        GpsMqttPublisher publisher = new GpsMqttPublisher(new TestPreference(Set.of()));

        try
        {
            PlottableDecodeEvent p25 = PlottableDecodeEvent.plottableBuilder(DecodeEventType.GPS, 1700000000000L)
                    .protocol(Protocol.APCO25)
                    .identifiers(new IdentifierCollection(List.of(DMRRadio.createTo(5057))))
                    .location(new GeoPosition(51.5, -0.12))
                    .build();
            publisher.receive(p25);
            assertNull(mReceived.poll(1, TimeUnit.SECONDS), "Non-DMR event was published");
        }
        finally
        {
            publisher.stop();
        }
    }

    @Test
    void parsesDestinationIds()
    {
        assertEquals(Set.of(5057, 310999), MqttPreference.parseIds(" 5057, 310999 "));
        assertEquals(Set.of(1, 2, 3), MqttPreference.parseIds("1;2 3"));
        assertTrue(MqttPreference.parseIds("").isEmpty());
        assertEquals(Set.of(12), MqttPreference.parseIds("12,abc"));
    }
}
