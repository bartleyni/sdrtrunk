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

package io.github.dsheirer.gui.preference.mqtt;

import io.github.dsheirer.mqtt.GpsMqttPublisher;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.mqtt.MqttPreference;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.MqttClient;

/**
 * Preference settings for publishing DMR GPS/APRS position reports to an MQTT broker.
 */
public class MqttPreferenceEditor extends HBox
{
    private final MqttPreference mMqttPreference;
    private GridPane mEditorPane;
    private CheckBox mEnabledCheckBox;
    private TextField mServerTextField;
    private TextField mClientIdTextField;
    private TextField mUserNameTextField;
    private PasswordField mPasswordField;
    private TextField mTopicTextField;
    private TextField mDestinationIdTextField;
    private Button mSaveButton;
    private Button mTestButton;
    private Button mSendTestMessageButton;
    private Label mStatusLabel;

    /**
     * Constructs an instance
     * @param userPreferences
     */
    public MqttPreferenceEditor(UserPreferences userPreferences)
    {
        mMqttPreference = userPreferences.getMqttPreference();
        HBox.setHgrow(getEditorPane(), Priority.ALWAYS);
        getChildren().add(getEditorPane());
    }

    private GridPane getEditorPane()
    {
        if(mEditorPane == null)
        {
            mEditorPane = new GridPane();
            mEditorPane.setPadding(new Insets(10, 10, 10, 10));
            mEditorPane.setHgap(10);
            mEditorPane.setVgap(10);

            int row = 0;
            mEditorPane.add(new Label("MQTT - DMR GPS/APRS Position Reports"), 0, row++, 2, 1);
            mEditorPane.add(getEnabledCheckBox(), 1, row++);

            row = addRow("Server:", getServerTextField(), row);
            row = addRow("Client ID:", getClientIdTextField(), row);
            row = addRow("Username:", getUserNameTextField(), row);
            row = addRow("Password:", getPasswordField(), row);
            row = addRow("Topic:", getTopicTextField(), row);
            row = addRow("Sent To IDs:", getDestinationIdTextField(), row);

            HBox buttons = new HBox(10, getSaveButton(), getTestButton(), getSendTestMessageButton());
            mEditorPane.add(buttons, 1, row++);
            mEditorPane.add(getStatusLabel(), 1, row++);

            Label notes = new Label("Publishes DMR position reports (ETSI UDT NMEA, Motorola LRRP and in-call GPS) as " +
                    "JSON.\nSent To IDs: comma separated DMR radio or talkgroup IDs that the position report is sent " +
                    "to (e.g. an APRS gateway ID).\nLeave blank to publish all position reports.\nServer examples: " +
                    "tcp://192.168.1.10:1883 or ssl://broker.example.com:8883\nNote: the password is saved " +
                    "unencrypted in the sdrtrunk user preferences.");
            notes.setWrapText(true);
            mEditorPane.add(notes, 0, row++, 2, 1);
        }

        return mEditorPane;
    }

    private int addRow(String text, javafx.scene.Node node, int row)
    {
        Label label = new Label(text);
        GridPane.setHalignment(label, HPos.RIGHT);
        GridPane.setHgrow(node, Priority.ALWAYS);
        mEditorPane.add(label, 0, row);
        mEditorPane.add(node, 1, row);
        return row + 1;
    }

    private CheckBox getEnabledCheckBox()
    {
        if(mEnabledCheckBox == null)
        {
            mEnabledCheckBox = new CheckBox("Enable MQTT Publishing");
            mEnabledCheckBox.setSelected(mMqttPreference.isEnabled());
        }

        return mEnabledCheckBox;
    }

    private TextField getServerTextField()
    {
        if(mServerTextField == null)
        {
            mServerTextField = new TextField(mMqttPreference.getServer());
            mServerTextField.setPromptText(MqttPreference.DEFAULT_SERVER);
        }

        return mServerTextField;
    }

    private TextField getClientIdTextField()
    {
        if(mClientIdTextField == null)
        {
            mClientIdTextField = new TextField(mMqttPreference.getClientId());
            mClientIdTextField.setPromptText(MqttPreference.DEFAULT_CLIENT_ID);
        }

        return mClientIdTextField;
    }

    private TextField getUserNameTextField()
    {
        if(mUserNameTextField == null)
        {
            mUserNameTextField = new TextField(mMqttPreference.getUserName());
            mUserNameTextField.setPromptText("(optional)");
        }

        return mUserNameTextField;
    }

    private PasswordField getPasswordField()
    {
        if(mPasswordField == null)
        {
            mPasswordField = new PasswordField();
            mPasswordField.setText(mMqttPreference.getPassword());
            mPasswordField.setPromptText("(optional)");
        }

        return mPasswordField;
    }

    private TextField getTopicTextField()
    {
        if(mTopicTextField == null)
        {
            mTopicTextField = new TextField(mMqttPreference.getTopic());
            mTopicTextField.setPromptText(MqttPreference.DEFAULT_TOPIC);
        }

        return mTopicTextField;
    }

    private TextField getDestinationIdTextField()
    {
        if(mDestinationIdTextField == null)
        {
            mDestinationIdTextField = new TextField(mMqttPreference.getDestinationIdFilterText());
            mDestinationIdTextField.setPromptText("e.g. 5057, 310999  (blank = all)");
        }

        return mDestinationIdTextField;
    }

    private Label getStatusLabel()
    {
        if(mStatusLabel == null)
        {
            mStatusLabel = new Label();
            mStatusLabel.setWrapText(true);
        }

        return mStatusLabel;
    }

    private Button getSaveButton()
    {
        if(mSaveButton == null)
        {
            mSaveButton = new Button("Save");
            mSaveButton.setOnAction(event -> save());
        }

        return mSaveButton;
    }

    private Button getTestButton()
    {
        if(mTestButton == null)
        {
            mTestButton = new Button("Test Connection");
            mTestButton.setOnAction(event -> testConnection());
        }

        return mTestButton;
    }

    private Button getSendTestMessageButton()
    {
        if(mSendTestMessageButton == null)
        {
            mSendTestMessageButton = new Button("Send Test Message");
            mSendTestMessageButton.setOnAction(event -> sendTestMessage());
        }

        return mSendTestMessageButton;
    }

    /**
     * Validates the entered values.
     * @return error message or null when valid.
     */
    private String validate()
    {
        String server = getServerTextField().getText().trim();

        if(!server.isEmpty() && !server.matches("(?i)(tcp|ssl|ws|wss)://.+"))
        {
            return "Server must start with tcp://, ssl://, ws:// or wss://";
        }

        String topic = getTopicTextField().getText();

        if(topic.contains("+") || topic.contains("#"))
        {
            return "Topic cannot contain the MQTT wildcard characters + or #";
        }

        String ids = getDestinationIdTextField().getText().trim();

        if(!ids.isEmpty() && !ids.matches("\\d+([,;\\s]+\\d+)*"))
        {
            return "Sent To IDs must be numeric IDs separated by commas";
        }

        return null;
    }

    private void save()
    {
        String error = validate();

        if(error != null)
        {
            getStatusLabel().setText("Not saved: " + error);
            return;
        }

        mMqttPreference.update(getEnabledCheckBox().isSelected(), getServerTextField().getText(),
                getClientIdTextField().getText(), getUserNameTextField().getText(), getPasswordField().getText(),
                getTopicTextField().getText(), getDestinationIdTextField().getText());

        //Refresh the fields to show any defaults that were applied
        getServerTextField().setText(mMqttPreference.getServer());
        getClientIdTextField().setText(mMqttPreference.getClientId());
        getTopicTextField().setText(mMqttPreference.getTopic());
        getStatusLabel().setText("Saved" + (mMqttPreference.isEnabled() ? " - publishing enabled" : " - publishing disabled"));
    }

    /**
     * Tests the entered broker settings on a background thread using a separate client ID so that an active
     * publisher connection is not disconnected by the broker.
     */
    private void testConnection()
    {
        runBrokerTest(false);
    }

    /**
     * Publishes an example DMR APRS position report, marked as a test message, to the entered topic using the
     * entered broker settings.
     */
    private void sendTestMessage()
    {
        runBrokerTest(true);
    }

    /**
     * Connects to the broker using the entered (not necessarily saved) settings on a background thread, optionally
     * publishes a test message, and reports the outcome in the status label.
     * @param publishTestMessage true to publish an example position report after connecting
     */
    private void runBrokerTest(boolean publishTestMessage)
    {
        String error = validate();

        if(error != null)
        {
            getStatusLabel().setText("Test failed: " + error);
            return;
        }

        String server = getServerTextField().getText().isBlank() ? MqttPreference.DEFAULT_SERVER :
                getServerTextField().getText().trim();
        String clientId = (getClientIdTextField().getText().isBlank() ? MqttPreference.DEFAULT_CLIENT_ID :
                getClientIdTextField().getText().trim()) + "-test";
        String userName = getUserNameTextField().getText();
        String password = getPasswordField().getText();
        String topic = getTopicTextField().getText().isBlank() ? MqttPreference.DEFAULT_TOPIC :
                getTopicTextField().getText().trim();
        String payload = GpsMqttPublisher.createTestMessage(
                MqttPreference.parseIds(getDestinationIdTextField().getText()));

        getTestButton().setDisable(true);
        getSendTestMessageButton().setDisable(true);
        getStatusLabel().setText((publishTestMessage ? "Sending test message to " : "Testing connection to ") +
                server + " ...");

        Thread thread = new Thread(() -> {
            String result;

            try
            {
                MqttClient client = GpsMqttPublisher.connect(server, clientId, userName, password);

                try
                {
                    if(publishTestMessage)
                    {
                        client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, false);
                        result = "Test message sent to topic [" + topic + "]";
                    }
                    else
                    {
                        result = "Connection successful";
                    }
                }
                finally
                {
                    GpsMqttPublisher.closeQuietly(client);
                }
            }
            catch(Exception e)
            {
                result = (publishTestMessage ? "Send failed: " : "Connection failed: ") + e.getMessage();
            }

            String status = result;
            Platform.runLater(() -> {
                getStatusLabel().setText(status);
                getTestButton().setDisable(false);
                getSendTestMessageButton().setDisable(false);
            });
        }, "sdrtrunk mqtt connection test");
        thread.setDaemon(true);
        thread.start();
    }
}
