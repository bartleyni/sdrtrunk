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
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.dmr.message.CACH;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.block.DataBlock1_2Rate;
import io.github.dsheirer.module.decode.dmr.message.data.header.DataHeader;
import io.github.dsheirer.module.decode.dmr.message.data.header.UnconfirmedDataHeader;
import io.github.dsheirer.module.decode.dmr.message.data.packet.DMRPacketMessage;
import io.github.dsheirer.module.decode.dmr.message.data.packet.PacketSequenceMessageFactory;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.module.decode.ip.IPacket;
import io.github.dsheirer.module.decode.ip.mototrbo.tms.TMSPacket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unconfirmed packet CRC-32 validation and recovery of data blocks with a corrupted slot type, using data
 * blocks captured from an Ailunce HD2 sending text messages.
 */
public class DMRPacketCrcTest
{
    //"!ad" text message to 2345 (4 blocks) - received intact
    private static final String[] AD_BLOCKS = {"4500002C000F0000401155FE", "0C00038C0C0009290FA70FA7",
            "00184EA7000EE00090040D00", "0A002100610064008BD2B092"};

    //"test" group text message to 1234 (6 blocks, 8 pad octets).  Block 4 was lost in the first attempt and received
    //in the second attempt - the text is identical so the complete packet is reconstructed from both attempts.
    private static final String[] TEST_BLOCKS = {"4500003C0000000040118553", "0C00038CE10004D20FA70FA7",
            "0028F6CB001EA00081040D00", "0A0074006500730074002200", "23002300200020002A002A00",
            "0000000000000000A78BEE42"};

    private static CorrectedBinaryMessage hex(String hex)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(hex.length() * 4);

        for(int x = 0; x < hex.length() / 2; x++)
        {
            message.load(x * 8, 8, Integer.parseInt(hex.substring(x * 2, x * 2 + 2), 16));
        }

        return message;
    }

    private static CorrectedBinaryMessage packet(String... blocks)
    {
        return hex(String.join("", blocks));
    }

    private static SlotType slotType(int dataType)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(24);
        message.load(4, 4, 1);          //Color code 1
        message.load(8, 4, dataType);
        return new SlotType(message);
    }

    private static CACH cach()
    {
        return CACH.getCACH(new CorrectedBinaryMessage(288));
    }

    @Test
    void validatesCapturedPackets()
    {
        assertTrue(PacketSequenceMessageFactory.isUnconfirmedPacketCrcValid(packet(AD_BLOCKS)));
        assertTrue(PacketSequenceMessageFactory.isUnconfirmedPacketCrcValid(packet(TEST_BLOCKS)));

        //First attempt as received - block 4 missing
        assertFalse(PacketSequenceMessageFactory.isUnconfirmedPacketCrcValid(packet(TEST_BLOCKS[0], TEST_BLOCKS[1],
                TEST_BLOCKS[2], TEST_BLOCKS[4], TEST_BLOCKS[5])));

        //Single bit error
        CorrectedBinaryMessage corrupted = packet(AD_BLOCKS);
        corrupted.flip(100);
        assertFalse(PacketSequenceMessageFactory.isUnconfirmedPacketCrcValid(corrupted));
    }

    /**
     * Unconfirmed IP data header: group 1234 from 908, 6 blocks to follow, 8 pad octets.
     */
    private static UnconfirmedDataHeader header()
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.load(0, 1, 1);          //Group destination
        message.load(4, 4, 2);          //DPF unconfirmed data
        message.load(8, 4, 4);          //SAP IP packet data
        message.load(12, 4, 8);         //Pad octets (low bits)
        message.load(16, 24, 1234);
        message.load(40, 24, 908);
        message.load(64, 1, 1);         //Final fragment
        message.load(65, 7, 6);         //Blocks to follow
        return new UnconfirmedDataHeader(DMRSyncPattern.MOBILE_STATION_DATA, message, cach(), slotType(6), 1000, 1);
    }

    private static DataBlock1_2Rate block(String hex, long timestamp)
    {
        return new DataBlock1_2Rate(DMRSyncPattern.MOBILE_STATION_DATA, hex(hex), cach(), slotType(7), timestamp, 1);
    }

    @Test
    void recoversBlockWithCorruptedSlotTypeAndDecodesText()
    {
        DMRMessageProcessor processor = new DMRMessageProcessor(new DecodeConfigDMR(), new DMRCrcMaskManager(false));
        List<IMessage> messages = new ArrayList<>();
        processor.setMessageListener(messages::add);

        processor.receive(header());

        //Block 1 (IP header) arrives with its slot type corrupted to VOICE HEADER and fails its own CRC - as captured
        DataHeader misclassified = new DataHeader(DMRSyncPattern.MOBILE_STATION_DATA, hex(TEST_BLOCKS[0]), cach(),
                slotType(1), 1060, 1);
        misclassified.setValid(false);
        processor.receive(misclassified);

        for(int x = 1; x < TEST_BLOCKS.length; x++)
        {
            processor.receive(block(TEST_BLOCKS[x], 1060 + x * 60));
        }

        DMRPacketMessage packet = messages.stream().filter(m -> m instanceof DMRPacketMessage)
                .map(m -> (DMRPacketMessage)m).findFirst().orElse(null);
        assertNotNull(packet, "Packet should be assembled including the recovered block");
        assertTrue(packet.isValid(), "Recovered packet should pass its CRC-32");

        IPacket udp = packet.getPacket().getPayload();
        assertTrue(udp != null && udp.getPayload() instanceof TMSPacket, "Expected TMS text message");
        assertEquals("test\"##  **", ((TMSPacket)udp.getPayload()).getTextMessage());
    }

    @Test
    void ipWrappedTextMessageProducesTextMessageEvent()
    {
        DMRMessageProcessor processor = new DMRMessageProcessor(new DecodeConfigDMR(), new DMRCrcMaskManager(false));
        List<IMessage> messages = new ArrayList<>();
        processor.setMessageListener(messages::add);
        processor.receive(header());

        for(int x = 0; x < TEST_BLOCKS.length; x++)
        {
            processor.receive(block(TEST_BLOCKS[x], 1060 + x * 60));
        }

        DMRPacketMessage packet = messages.stream().filter(m -> m instanceof DMRPacketMessage)
                .map(m -> (DMRPacketMessage)m).findFirst().orElse(null);
        assertNotNull(packet);

        io.github.dsheirer.controller.channel.Channel channel = new io.github.dsheirer.controller.channel.Channel("test");
        channel.setDecodeConfiguration(new DecodeConfigDMR());
        DMRDecoderState state = new DMRDecoderState(channel, 1, null);
        List<io.github.dsheirer.module.decode.event.IDecodeEvent> events = new ArrayList<>();
        state.addDecodeEventListener(events::add);
        state.receive(packet);

        io.github.dsheirer.module.decode.event.IDecodeEvent text = events.stream()
                .filter(e -> e.getEventType() == io.github.dsheirer.module.decode.event.DecodeEventType.TEXT_MESSAGE)
                .findFirst().orElse(null);
        assertNotNull(text, "IP wrapped TMS text should produce a TEXT_MESSAGE event (published to MQTT)");
        assertTrue(text.getDetails().contains("test"));
    }

    @Test
    void packetWithMissingBlockIsInvalid()
    {
        DMRMessageProcessor processor = new DMRMessageProcessor(new DecodeConfigDMR(), new DMRCrcMaskManager(false));
        List<IMessage> messages = new ArrayList<>();
        processor.setMessageListener(messages::add);

        processor.receive(header());

        //Block 4 lost
        for(int x = 0; x < TEST_BLOCKS.length; x++)
        {
            if(x != 3)
            {
                processor.receive(block(TEST_BLOCKS[x], 1060 + x * 60));
            }
        }

        //End of transmission
        processor.receive(new io.github.dsheirer.message.SyncLossMessage(3000, 9600,
                io.github.dsheirer.protocol.Protocol.DMR, 0));

        DMRPacketMessage packet = messages.stream().filter(m -> m instanceof DMRPacketMessage)
                .map(m -> (DMRPacketMessage)m).findFirst().orElse(null);
        assertNotNull(packet);
        assertFalse(packet.isValid(), "Packet with a missing block must be flagged invalid");
    }
}
