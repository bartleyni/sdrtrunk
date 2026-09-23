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

package io.github.dsheirer.preference.mqtt;

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * User preferences for publishing decoded DMR GPS/APRS position reports and emergency alarms to an MQTT broker.
 */
public class MqttPreference extends Preference
{
    private final static Logger mLog = LoggerFactory.getLogger(MqttPreference.class);
    public static final String DEFAULT_SERVER = "tcp://localhost:1883";
    public static final String DEFAULT_CLIENT_ID = "sdrtrunk";
    public static final String DEFAULT_TOPIC = "sdrtrunk/dmr/gps";
    public static final String DEFAULT_ALARM_TOPIC = "sdrtrunk/dmr/alarm";
    private static final String ENABLED = "mqtt.enabled";
    private static final String SERVER = "mqtt.server";
    private static final String CLIENT_ID = "mqtt.client.id";
    private static final String USER_NAME = "mqtt.user.name";
    private static final String PASSWORD = "mqtt.user.authorization";
    private static final String TOPIC = "mqtt.topic";
    private static final String DESTINATION_ID_FILTER = "mqtt.destination.id.filter";
    private static final String ALARM_ENABLED = "mqtt.alarm.enabled";
    private static final String ALARM_TOPIC = "mqtt.alarm.topic";

    private Preferences mPreferences = Preferences.userNodeForPackage(MqttPreference.class);

    /**
     * Constructs an instance.
     * @param updateListener to receive notifications when preferences are updated.
     */
    public MqttPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.MQTT;
    }

    /**
     * Indicates if publishing to MQTT is enabled
     */
    public boolean isEnabled()
    {
        return mPreferences.getBoolean(ENABLED, false);
    }

    /**
     * MQTT server URI, e.g. tcp://host:1883 or ssl://host:8883
     */
    public String getServer()
    {
        return mPreferences.get(SERVER, DEFAULT_SERVER);
    }

    /**
     * MQTT client ID
     */
    public String getClientId()
    {
        return mPreferences.get(CLIENT_ID, DEFAULT_CLIENT_ID);
    }

    /**
     * MQTT user name, or empty when the broker does not require authentication.
     */
    public String getUserName()
    {
        return mPreferences.get(USER_NAME, "");
    }

    /**
     * MQTT password, or empty when the broker does not require authentication.
     */
    public String getPassword()
    {
        return mPreferences.get(PASSWORD, "");
    }

    /**
     * MQTT topic to publish GPS position reports to
     */
    public String getTopic()
    {
        return mPreferences.get(TOPIC, DEFAULT_TOPIC);
    }

    /**
     * Indicates if DMR emergency alarms/calls are published (to the alarm topic)
     */
    public boolean isAlarmEnabled()
    {
        return mPreferences.getBoolean(ALARM_ENABLED, true);
    }

    /**
     * MQTT topic to publish DMR emergency alarms/calls to
     */
    public String getAlarmTopic()
    {
        return mPreferences.get(ALARM_TOPIC, DEFAULT_ALARM_TOPIC);
    }

    /**
     * Destination (TO) ID filter as entered by the user: a comma separated list of radio or talkgroup IDs.  An empty
     * filter publishes position reports sent to any destination.
     */
    public String getDestinationIdFilterText()
    {
        return mPreferences.get(DESTINATION_ID_FILTER, "");
    }

    /**
     * Parsed destination (TO) ID filter.
     * @return set of IDs, or an empty set to publish position reports sent to any destination.
     */
    public Set<Integer> getDestinationIdFilter()
    {
        return parseIds(getDestinationIdFilterText());
    }

    /**
     * Parses a comma, semicolon or whitespace separated list of integer IDs, ignoring any invalid entries.
     * @param text to parse
     * @return parsed IDs
     */
    public static Set<Integer> parseIds(String text)
    {
        if(text == null || text.isBlank())
        {
            return Collections.emptySet();
        }

        Set<Integer> ids = new TreeSet<>();

        for(String token: text.trim().split("[,;\\s]+"))
        {
            try
            {
                ids.add(Integer.parseInt(token));
            }
            catch(NumberFormatException nfe)
            {
                mLog.warn("Ignoring invalid MQTT destination ID filter value [" + token + "]");
            }
        }

        return ids;
    }

    /**
     * Updates and stores all MQTT settings and notifies listeners once.
     */
    public void update(boolean enabled, String server, String clientId, String userName, String password,
                       String topic, String destinationIdFilter, boolean alarmEnabled, String alarmTopic)
    {
        mPreferences.putBoolean(ENABLED, enabled);
        mPreferences.put(SERVER, clean(server, DEFAULT_SERVER));
        mPreferences.put(CLIENT_ID, clean(clientId, DEFAULT_CLIENT_ID));
        mPreferences.put(USER_NAME, clean(userName, ""));
        mPreferences.put(PASSWORD, password == null ? "" : password);
        mPreferences.put(TOPIC, clean(topic, DEFAULT_TOPIC));
        mPreferences.put(DESTINATION_ID_FILTER, clean(destinationIdFilter, ""));
        mPreferences.putBoolean(ALARM_ENABLED, alarmEnabled);
        mPreferences.put(ALARM_TOPIC, clean(alarmTopic, DEFAULT_ALARM_TOPIC));
        notifyPreferenceUpdated();
    }

    /**
     * Trims the value and substitutes the default when the value is null or blank.
     */
    private static String clean(String value, String defaultValue)
    {
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }
}
