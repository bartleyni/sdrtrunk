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

package io.github.dsheirer.module.decode.dmr;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.dmr.message.CACH;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.GroupVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.voice.VoiceAMessage;
import io.github.dsheirer.module.decode.dmr.message.voice.VoiceEMBMessage;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncDetectMode;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncModeMonitor;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests handheld (simplex / direct mode) call handling in the DMR decoder state and sync mode monitor.
 */
public class DMRDecoderStateSimplexTest
{
    private static final DMRSyncPattern[] DIRECT_SUPERFRAME = {DMRSyncPattern.DIRECT_VOICE_TIMESLOT_1,
            DMRSyncPattern.DIRECT_VOICE_FRAME_B, DMRSyncPattern.DIRECT_VOICE_FRAME_C, DMRSyncPattern.DIRECT_VOICE_FRAME_D,
            DMRSyncPattern.DIRECT_VOICE_FRAME_E, DMRSyncPattern.DIRECT_VOICE_FRAME_F};

    private final List<IDecodeEvent> mEvents = new ArrayList<>();

    private DMRDecoderState createDecoderState(boolean simplex)
    {
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setSimplexMode(simplex);
        config.setIgnoreCRCChecksums(true);
        Channel channel = new Channel("simplex test");
        channel.setDecodeConfiguration(config);
        DMRDecoderState state = new DMRDecoderState(channel, 1, null);
        state.addDecodeEventListener(mEvents::add);
        return state;
    }

    /**
     * Sends a direct mode voice superframe (6 bursts, 60 ms apart) starting at the timestamp.
     * @return timestamp of the last burst
     */
    private static long sendSuperframe(DMRDecoderState state, long start)
    {
        long timestamp = start;

        for(DMRSyncPattern pattern: DIRECT_SUPERFRAME)
        {
            CorrectedBinaryMessage message = new CorrectedBinaryMessage(288);
            CACH cach = CACH.getCACH(message);

            if(pattern == DMRSyncPattern.DIRECT_VOICE_TIMESLOT_1)
            {
                state.receive(new VoiceAMessage(pattern, message, cach, timestamp, 1));
            }
            else
            {
                state.receive(new VoiceEMBMessage(pattern, message, cach, timestamp, 1));
            }

            timestamp += 60;
        }

        return timestamp - 60;
    }

    /**
     * Creates a standard group voice channel user full link control message.
     */
    private static GroupVoiceChannelUser groupVoice(int source, int talkgroup, boolean emergency, long timestamp)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.load(16, 8, emergency ? 0x80 : 0x00); //Service options
        message.load(24, 24, talkgroup);
        message.load(48, 24, source);
        return new GroupVoiceChannelUser(message, timestamp, 1);
    }

    /**
     * Distinct call events (by identity) of the specified type, in order of first broadcast.
     */
    private Set<IDecodeEvent> calls()
    {
        Set<IDecodeEvent> calls = new LinkedHashSet<>();

        for(IDecodeEvent event: mEvents)
        {
            if(event.getEventType() == DecodeEventType.CALL || event.getEventType() == DecodeEventType.CALL_GROUP)
            {
                calls.add(event);
            }
        }

        return calls;
    }

    private static Object fromRadio(IDecodeEvent event)
    {
        Identifier from = event.getIdentifierCollection().getIdentifier(IdentifierClass.USER, Form.RADIO, Role.FROM);
        return from != null ? from.getValue() : null;
    }

    @Test
    void voiceGapStartsNewCall()
    {
        DMRDecoderState state = createDecoderState(false);
        long last = sendSuperframe(state, 1000);
        sendSuperframe(state, last + 2000); //2 second gap, no terminator

        List<IDecodeEvent> calls = new ArrayList<>(calls());
        assertEquals(2, calls.size(), "Voice gap should split into two calls");
        assertEquals(last, calls.get(0).getTimeStart() + calls.get(0).getDuration(),
                "First call should end at its last voice burst");
    }

    @Test
    void continuousVoiceIsOneCall()
    {
        DMRDecoderState state = createDecoderState(false);
        long last = sendSuperframe(state, 1000);
        sendSuperframe(state, last + 60);
        assertEquals(1, calls().size());
    }

    @Test
    void newTalkerStartsNewCall()
    {
        DMRDecoderState state = createDecoderState(true);
        state.receive(groupVoice(1001, 9, false, 1000));
        long last = sendSuperframe(state, 1000);
        state.receive(groupVoice(2002, 9, false, last + 60));
        sendSuperframe(state, last + 60);

        List<IDecodeEvent> calls = new ArrayList<>(calls());
        assertEquals(2, calls.size(), "Change of talker should split into two calls");
        assertEquals(1001, fromRadio(calls.get(0)));
        assertEquals(2002, fromRadio(calls.get(1)));
    }

    @Test
    void callEventUpdatedOncePerSuperframe()
    {
        DMRDecoderState state = createDecoderState(false);
        long last = sendSuperframe(state, 1000);
        sendSuperframe(state, last + 60);

        long callBroadcasts = mEvents.stream().filter(e -> e.getEventType() == DecodeEventType.CALL).count();
        assertEquals(2, callBroadcasts, "Call event should be broadcast once per superframe, not per burst");
    }

    @Test
    void emergencyReportedOncePerCall()
    {
        DMRDecoderState state = createDecoderState(true);
        state.receive(groupVoice(1001, 9, true, 1000));
        long last = sendSuperframe(state, 1000);
        state.receive(groupVoice(1001, 9, true, last));

        long emergencies = mEvents.stream().filter(e -> e.getEventType() == DecodeEventType.EMERGENCY).count();
        assertEquals(1, emergencies);
    }

    @Test
    void automaticSyncModeLockIsReleased()
    {
        DMRSyncModeMonitor monitor = new DMRSyncModeMonitor();

        for(int x = 0; x < 12; x++)
        {
            monitor.detected(DMRSyncPattern.DIRECT_VOICE_TIMESLOT_1);
        }

        assertEquals(DMRSyncDetectMode.DIRECT_ONLY, monitor.getMode());
        monitor.releaseAutomaticLock();
        assertEquals(DMRSyncDetectMode.AUTOMATIC, monitor.getMode());

        //Explicit locks (e.g. trunked traffic channels) are never released
        monitor.setMode(DMRSyncDetectMode.BASE_ONLY);
        monitor.releaseAutomaticLock();
        assertEquals(DMRSyncDetectMode.BASE_ONLY, monitor.getMode());
    }
}
