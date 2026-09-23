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

import com.google.common.eventbus.Subscribe;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.event.PlottableDecodeEvent;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.mqtt.MqttPreference;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.jdesktop.swingx.mapviewer.GeoPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes decoded DMR GPS position reports (DMR APRS via ETSI UDT NMEA or Motorola LRRP, and in-call GPS) to an
 * MQTT broker as JSON, optionally filtered by the destination (TO) radio or talkgroup ID.  DMR emergency alarms are
 * published to a separate alarm topic, with the alarming radio's last known position when available.
 *
 * Decode events are received on decoder threads, so publishing is handed to a single background thread with a
 * bounded queue.  When the broker is slow or unreachable, excess position reports are discarded rather than
 * blocking or accumulating.
 */
public class GpsMqttPublisher implements Listener<IDecodeEvent>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(GpsMqttPublisher.class);
    private static final int QUEUE_CAPACITY = 200;
    private static final long RECONNECT_INTERVAL_MS = 30000;
    private static final int CONNECTION_TIMEOUT_SECONDS = 10;
    private static final int QOS = 1;

    private final MqttPreference mPreference;
    private static final Gson GSON = new Gson();
    private static final int TEST_SOURCE_ID = 2345678;
    private static final int TEST_DESTINATION_ID = 5057;
    private static final int MAX_TRACKED_RADIOS = 5000;
    private final ThreadPoolExecutor mExecutor;
    private final Map<Integer,LastPosition> mLastPositions = new ConcurrentHashMap<>();
    private volatile Settings mSettings;

    //Accessed only from the executor thread
    private MqttClient mClient;
    private Settings mClientSettings;
    private long mLastConnectAttempt;

    /**
     * Snapshot of the MQTT preference settings
     */
    private record Settings(boolean enabled, String server, String clientId, String userName, String password,
                            String topic, Set<Integer> destinationIds, boolean alarmEnabled, String alarmTopic) {}

    /**
     * Most recent position reported by a radio, included with any emergency alarm from that radio.
     */
    private record LastPosition(double latitude, double longitude, long timestamp) {}

    /**
     * Constructs an instance
     * @param preference for MQTT settings
     */
    public GpsMqttPublisher(MqttPreference preference)
    {
        mPreference = preference;
        mSettings = loadSettings();
        mExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "sdrtrunk mqtt gps publisher");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.DiscardPolicy());
        MyEventBus.getGlobalEventBus().register(this);
    }

    private Settings loadSettings()
    {
        return new Settings(mPreference.isEnabled(), mPreference.getServer(), mPreference.getClientId(),
                mPreference.getUserName(), mPreference.getPassword(), mPreference.getTopic(),
                mPreference.getDestinationIdFilter(), mPreference.isAlarmEnabled(), mPreference.getAlarmTopic());
    }

    /**
     * Reloads the settings and drops the current broker connection whenever the MQTT preferences are updated.
     */
    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.MQTT)
        {
            mSettings = loadSettings();
            mExecutor.execute(this::disconnect);
        }
    }

    @Override
    public void receive(IDecodeEvent decodeEvent)
    {
        Settings settings = mSettings;

        if(!settings.enabled())
        {
            return;
        }

        if(decodeEvent instanceof PlottableDecodeEvent event && isDmrPosition(event))
        {
            //Track the last position for every radio (regardless of filter) so it can accompany an emergency alarm
            rememberPosition(event);

            if(matchesDestination(event.getIdentifierCollection(), settings.destinationIds()))
            {
                String json = toJson(event, false);
                mExecutor.execute(() -> publish(settings, settings.topic(), json));
            }
        }
        //Emergency alarms are not subject to the destination ID filter so that no alarm is missed
        else if(settings.alarmEnabled() && decodeEvent.getProtocol() == Protocol.DMR &&
                decodeEvent.getEventType() == DecodeEventType.EMERGENCY)
        {
            String json = toAlarmJson(decodeEvent, getLastPosition(decodeEvent.getIdentifierCollection()), false);
            mExecutor.execute(() -> publish(settings, settings.alarmTopic(), json));
        }
    }

    /**
     * Records the position event as the most recent position for the reporting (FROM) radio.
     */
    private void rememberPosition(PlottableDecodeEvent event)
    {
        Integer radio = getFromRadioId(event.getIdentifierCollection());

        if(radio != null)
        {
            if(mLastPositions.size() >= MAX_TRACKED_RADIOS && !mLastPositions.containsKey(radio))
            {
                mLastPositions.clear();
            }

            mLastPositions.put(radio, new LastPosition(event.getLocation().getLatitude(),
                    event.getLocation().getLongitude(), event.getTimeStart()));
        }
    }

    /**
     * Last known position for the FROM radio in the identifier collection, or null.
     */
    private LastPosition getLastPosition(IdentifierCollection identifiers)
    {
        Integer radio = getFromRadioId(identifiers);
        return radio != null ? mLastPositions.get(radio) : null;
    }

    /**
     * Integer value of the FROM identifier, or null.
     */
    private static Integer getFromRadioId(IdentifierCollection identifiers)
    {
        if(identifiers != null && identifiers.getFromIdentifier() != null &&
                identifiers.getFromIdentifier().getValue() instanceof Integer radio)
        {
            return radio;
        }

        return null;
    }

    /**
     * Indicates if the event is a DMR position report with a location.
     */
    private static boolean isDmrPosition(PlottableDecodeEvent event)
    {
        return event.getProtocol() == Protocol.DMR && event.getLocation() != null &&
                (event.getEventType() == DecodeEventType.GPS || event.getEventType() == DecodeEventType.LRRP);
    }

    /**
     * Indicates if any destination (TO) radio or talkgroup ID matches the filter.  An empty filter matches all.
     */
    static boolean matchesDestination(IdentifierCollection identifiers, Set<Integer> destinationIds)
    {
        if(destinationIds.isEmpty())
        {
            return true;
        }

        if(identifiers != null)
        {
            for(Identifier identifier: identifiers.getIdentifiers(IdentifierClass.USER, Role.TO))
            {
                if(identifier.getValue() instanceof Integer id && destinationIds.contains(id))
                {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Creates an example DMR APRS position report JSON payload, marked as a test message, for verifying the broker
     * and any downstream consumers.
     * @param destinationIds from the filter.  The first ID is used as the destination so that the example matches
     * the filter, otherwise a default APRS gateway ID is used.
     * @return JSON payload
     */
    public static String createTestMessage(Set<Integer> destinationIds)
    {
        int destination = destinationIds == null || destinationIds.isEmpty() ? TEST_DESTINATION_ID :
                destinationIds.iterator().next();

        PlottableDecodeEvent event = PlottableDecodeEvent.plottableBuilder(DecodeEventType.GPS, System.currentTimeMillis())
                .protocol(Protocol.DMR)
                .identifiers(new IdentifierCollection(List.of(DMRRadio.createFrom(TEST_SOURCE_ID),
                        DMRRadio.createTo(destination))))
                .location(new GeoPosition(51.50073, -0.12463))
                .speed(18.52)
                .heading(90)
                .details("SDRTRUNK TEST MESSAGE - EXAMPLE DMR APRS POSITION REPORT")
                .build();
        event.setTimeslot(1);

        return toJson(event, true);
    }

    /**
     * Creates an example DMR emergency alarm JSON payload, marked as a test message, including an example last
     * known position.
     * @return JSON payload
     */
    public static String createTestAlarm()
    {
        long now = System.currentTimeMillis();
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.EMERGENCY, now)
                .protocol(Protocol.DMR)
                .identifiers(new IdentifierCollection(List.of(DMRRadio.createFrom(TEST_SOURCE_ID),
                        DMRTalkgroup.create(9))))
                .details("SDRTRUNK TEST MESSAGE - EXAMPLE DMR EMERGENCY ALARM")
                .build();
        event.setTimeslot(1);

        return toAlarmJson(event, new LastPosition(51.50073, -0.12463, now - 60000), true);
    }

    /**
     * Creates the JSON payload for an emergency alarm.
     * @param event emergency event
     * @param lastPosition of the alarming radio, or null when unknown
     * @param test true to mark the payload as a test message
     */
    private static String toAlarmJson(IDecodeEvent event, LastPosition lastPosition, boolean test)
    {
        JsonObject json = new JsonObject();

        if(test)
        {
            json.addProperty("test", true);
        }

        json.addProperty("timestamp", Instant.ofEpochMilli(event.getTimeStart()).toString());
        json.addProperty("epoch_ms", event.getTimeStart());
        json.addProperty("protocol", event.getProtocol().toString());
        json.addProperty("type", event.getEventType().name());

        IdentifierCollection identifiers = event.getIdentifierCollection();

        if(identifiers != null)
        {
            addIdentifier(json, "from", identifiers.getFromIdentifier());
            addIdentifier(json, "to", identifiers.getToIdentifier());
        }

        json.addProperty("timeslot", event.getTimeslot());

        IChannelDescriptor channel = event.getChannelDescriptor();

        if(channel != null && channel.getDownlinkFrequency() > 0)
        {
            json.addProperty("frequency", channel.getDownlinkFrequency());
        }

        if(event.getDetails() != null)
        {
            json.addProperty("details", event.getDetails());
        }

        if(lastPosition != null)
        {
            JsonObject position = new JsonObject();
            position.addProperty("latitude", lastPosition.latitude());
            position.addProperty("longitude", lastPosition.longitude());
            position.addProperty("timestamp", Instant.ofEpochMilli(lastPosition.timestamp()).toString());
            position.addProperty("age_seconds", Math.max(0, (event.getTimeStart() - lastPosition.timestamp()) / 1000));
            json.add("last_position", position);
        }

        return GSON.toJson(json);
    }

    /**
     * Creates the JSON payload for the position report.
     * @param event to convert
     * @param test true to mark the payload as a test message
     */
    private static String toJson(PlottableDecodeEvent event, boolean test)
    {
        JsonObject json = new JsonObject();

        if(test)
        {
            json.addProperty("test", true);
        }

        json.addProperty("timestamp", Instant.ofEpochMilli(event.getTimeStart()).toString());
        json.addProperty("epoch_ms", event.getTimeStart());
        json.addProperty("protocol", event.getProtocol().toString());
        json.addProperty("type", event.getEventType().name());

        IdentifierCollection identifiers = event.getIdentifierCollection();

        if(identifiers != null)
        {
            addIdentifier(json, "from", identifiers.getFromIdentifier());
            addIdentifier(json, "to", identifiers.getToIdentifier());
        }

        json.addProperty("latitude", event.getLocation().getLatitude());
        json.addProperty("longitude", event.getLocation().getLongitude());
        json.addProperty("speed_kph", event.getSpeed());
        json.addProperty("heading", event.getHeading());
        json.addProperty("timeslot", event.getTimeslot());

        IChannelDescriptor channel = event.getChannelDescriptor();

        if(channel != null && channel.getDownlinkFrequency() > 0)
        {
            json.addProperty("frequency", channel.getDownlinkFrequency());
        }

        if(event.getDetails() != null)
        {
            json.addProperty("details", event.getDetails());
        }

        return GSON.toJson(json);
    }

    private static void addIdentifier(JsonObject json, String name, Identifier identifier)
    {
        if(identifier != null)
        {
            if(identifier.getValue() instanceof Number number)
            {
                json.addProperty(name, number);
            }
            else
            {
                json.addProperty(name, identifier.toString());
            }
        }
    }

    /**
     * Publishes the payload, connecting to the broker first when necessary.  Executes on the publisher thread.
     */
    private void publish(Settings settings, String topic, String json)
    {
        //Discard reports queued before the settings changed or publishing was disabled
        if(settings != mSettings || !settings.enabled())
        {
            return;
        }

        if(mClient == null || !mClient.isConnected() || mClientSettings != settings)
        {
            long now = System.currentTimeMillis();

            if(mLastConnectAttempt > 0 && mClientSettings == settings && (now - mLastConnectAttempt) < RECONNECT_INTERVAL_MS)
            {
                return; //Broker unavailable - discard until the next reconnect attempt
            }

            disconnect();
            mLastConnectAttempt = now;
            mClientSettings = settings;

            try
            {
                mClient = connect(settings.server(), settings.clientId(), settings.userName(), settings.password());
                LOGGER.info("Connected to MQTT broker [" + settings.server() + "] for DMR GPS publishing");
            }
            catch(Exception e)
            {
                LOGGER.warn("Unable to connect to MQTT broker [" + settings.server() + "] - " + e.getMessage() +
                        " - retrying in " + (RECONNECT_INTERVAL_MS / 1000) + " seconds");
                mClient = null;
                return;
            }
        }

        try
        {
            mClient.publish(topic, json.getBytes(StandardCharsets.UTF_8), QOS, false);
        }
        catch(MqttException e)
        {
            LOGGER.warn("Error publishing DMR report to MQTT topic [" + topic + "] - " + e.getMessage());
            disconnect();
        }
    }

    /**
     * Creates and connects an MQTT client.
     * @param server URI, e.g. tcp://host:1883
     * @param clientId for the connection
     * @param userName optional, may be empty
     * @param password optional, may be empty
     * @return connected client
     * @throws MqttException if the connection fails
     */
    public static MqttClient connect(String server, String clientId, String userName, String password)
            throws MqttException
    {
        MqttClient client = new MqttClient(server, clientId, new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setConnectionTimeout(CONNECTION_TIMEOUT_SECONDS);

        if(userName != null && !userName.isBlank())
        {
            options.setUserName(userName);
            options.setPassword(password == null ? new char[0] : password.toCharArray());
        }

        try
        {
            client.connect(options);
        }
        catch(MqttException e)
        {
            closeQuietly(client);
            throw e;
        }

        return client;
    }

    /**
     * Disconnects and closes the current client.  Executes on the publisher thread.
     */
    private void disconnect()
    {
        if(mClient != null)
        {
            closeQuietly(mClient);
            mClient = null;
        }

        mClientSettings = null;
        mLastConnectAttempt = 0;
    }

    /**
     * Disconnects and closes the client, ignoring any errors.
     */
    public static void closeQuietly(MqttClient client)
    {
        try
        {
            if(client.isConnected())
            {
                client.disconnect(1000);
            }
        }
        catch(Exception e)
        {
            //Ignore
        }

        try
        {
            client.close();
        }
        catch(Exception e)
        {
            //Ignore
        }
    }

    /**
     * Stops the publisher and disconnects from the broker.
     */
    public void stop()
    {
        MyEventBus.getGlobalEventBus().unregister(this);
        mExecutor.execute(this::disconnect);
        mExecutor.shutdown();

        try
        {
            mExecutor.awaitTermination(2, TimeUnit.SECONDS);
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
