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

package io.github.dsheirer.module.decode.dmr.message.data.packet;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.dmr.message.CACH;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.block.DataBlock1_2Rate;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.Preamble;
import io.github.dsheirer.module.decode.dmr.message.data.header.UDTHeader;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests packet sequence assembly of Unified Data Transport (UDT) short data, as used for DMR GPS/APRS position
 * reports on simplex channels where there are no repeater idle messages to trigger dispatch.
 */
public class PacketSequenceAssemblerTest
{
    private static final int SOURCE = 2345678;
    private static final int DESTINATION = 234999;

    private static UDTHeader udtHeader(int blocks, long timestamp)
    {
        return udtHeader(blocks, timestamp, SOURCE, DESTINATION);
    }

    private static UDTHeader udtHeader(int blocks, long timestamp, int source, int destination)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.load(3, 1, 0);           //UDT format high bit
        message.load(12, 4, 5);          //UDT format 5 = NMEA location coded
        message.load(16, 24, destination);
        message.load(40, 24, source);
        message.load(70, 2, blocks - 1); //UAB = appended blocks - 1
        CorrectedBinaryMessage slotTypeMessage = new CorrectedBinaryMessage(24);
        return new UDTHeader(DMRSyncPattern.DIRECT_DATA_TIMESLOT_1, message, CACH.getCACH(new CorrectedBinaryMessage(288)),
                new SlotType(slotTypeMessage), timestamp, 1);
    }

    private static DataBlock1_2Rate block(long timestamp)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.load(1, 1, 1);           //North
        message.load(11, 7, 51);         //Latitude degrees
        message.load(38, 8, 0);          //Longitude degrees
        message.load(46, 6, 7);          //Longitude minutes
        return new DataBlock1_2Rate(DMRSyncPattern.DIRECT_DATA_TIMESLOT_1, message,
                CACH.getCACH(new CorrectedBinaryMessage(288)), new SlotType(new CorrectedBinaryMessage(24)), timestamp, 1);
    }

    @Test
    void eachUdtReportIsDispatchedImmediately()
    {
        List<IMessage> dispatched = new ArrayList<>();
        PacketSequenceAssembler assembler = new PacketSequenceAssembler();
        assembler.setMessageListener(dispatched::add);

        //Three GPS reports, 30 seconds apart, with nothing in between (simplex - no idle messages)
        for(int report = 0; report < 3; report++)
        {
            long timestamp = 1000 + report * 30000L;
            assembler.process(udtHeader(1, timestamp));
            assembler.process(block(timestamp + 60));
            assertEquals(report + 1, dispatched.size(), "Report " + (report + 1) + " was not dispatched when complete");
        }

        for(IMessage message: dispatched)
        {
            assertTrue(message instanceof UDTShortMessageService sms && sms.isNmeaLocation());
            UDTShortMessageService sms = (UDTShortMessageService)message;
            assertTrue(sms.getNmeaLocation().isValid());
            assertEquals(51.0, sms.getNmeaLocation().getLatitude(), 0.0001);
        }
    }

    /**
     * Creates a single block UDT NMEA message from a captured Ailunce HD2 data block (hex, 12 bytes).
     */
    private static UDTShortMessageService captured(String hex)
    {
        CorrectedBinaryMessage payload = new CorrectedBinaryMessage(96);

        for(int x = 0; x < 12; x++)
        {
            payload.load(x * 8, 8, Integer.parseInt(hex.substring(x * 2, x * 2 + 2), 16));
        }

        return new UDTShortMessageService(udtHeader(1, 1000), payload);
    }

    @Test
    void capturedHd2PositionsPassCrc()
    {
        //Blocks captured from an Ailunce HD2 (radio 908) GPS report, logged by sdrtrunk
        for(String hex: new String[]{"500CCC0F2808B4010087148B", "500CCC0F4008B40200871F81"})
        {
            UDTShortMessageService sms = captured(hex);
            assertTrue(sms.isCrcValid(), "CRC should pass for " + hex);
            assertTrue(sms.getNmeaLocation().isValid());
            assertEquals(51.2016, sms.getNmeaLocation().getLatitude(), 0.0001);
            assertEquals(-2.1902, sms.getNmeaLocation().getLongitude(), 0.0001);
        }
    }

    @Test
    void corruptedHd2PositionsFailCrc()
    {
        //Captured blocks with bit errors that decoded to wrong positions (-2.4568, -18.2568, etc.)
        for(String hex: new String[]{"500CCC0F3C09B40240820C97", "500CCC0B4008B401803C6D5D", "550CCC0F1448F40000932750"})
        {
            UDTShortMessageService sms = captured(hex);
            assertFalse(sms.isCrcValid(), "CRC should fail for corrupted block " + hex);
            assertTrue(sms.getSMS().contains("CRC ERROR"));
        }
    }

    private static CorrectedBinaryMessage hex(String hex)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(hex.length() * 4);

        for(int x = 0; x < hex.length() / 2; x++)
        {
            message.load(x * 8, 8, Integer.parseInt(hex.substring(x * 2, x * 2 + 2), 16));
        }

        return message;
    }

    private static DataBlock1_2Rate capturedBlock(String hexBlock, long timestamp)
    {
        return new DataBlock1_2Rate(DMRSyncPattern.DIRECT_DATA_TIMESLOT_1, hex(hexBlock),
                CACH.getCACH(new CorrectedBinaryMessage(288)), new SlotType(new CorrectedBinaryMessage(24)), timestamp, 1);
    }

    /**
     * CSBK data preamble captured from the Ailunce HD2: FM:908 TO:2345.
     */
    private static Preamble capturedPreamble(long timestamp)
    {
        Preamble preamble = new Preamble(DMRSyncPattern.DIRECT_DATA_TIMESLOT_1, hex("BD00801F00092900038CB2CF"),
                CACH.getCACH(new CorrectedBinaryMessage(288)), new SlotType(new CorrectedBinaryMessage(24)), timestamp, 1);
        preamble.checkCRC();
        return preamble;
    }

    @Test
    void recoversReportWhenHeaderBlockCountCorrupted()
    {
        //18:40:00 - header CRC error decoded 2 appended blocks, but only 1 (intact) block was transmitted
        UDTShortMessageService sms = new UDTShortMessageService(udtHeader(2, 1000, 908, 2345),
                hex("500CCC0F7808B402C0836B9F"));
        assertTrue(sms.isCrcValid(), "CRC should validate against the single received block");
        assertTrue(sms.getNmeaLocation().isValid());
        assertEquals(51.2016, sms.getNmeaLocation().getLatitude(), 0.0001);
        assertEquals(-2.1902, sms.getNmeaLocation().getLongitude(), 0.0001);
    }

    @Test
    void recoversReportWhenHeaderLost()
    {
        List<IMessage> dispatched = new ArrayList<>();
        PacketSequenceAssembler assembler = new PacketSequenceAssembler();
        assembler.setMessageListener(dispatched::add);

        //A good report from radio 908 teaches the assembler that radio's UDT format
        assembler.process(capturedPreamble(900));
        assembler.process(udtHeader(1, 1000, 908, 2345));
        assembler.process(capturedBlock("500CCC0F2808B4010087148B", 1060));
        assertEquals(1, dispatched.size());

        //18:41:00 - preambles OK, header lost to bit errors, intact data block
        assertTrue(capturedPreamble(30000).isValid(), "Captured preamble should pass its CRC");
        assembler.process(capturedPreamble(30000));
        assembler.process(capturedBlock("500CCC0F6408B4054080D493", 31000));
        assembler.dispatchPendingSequences(); //end of transmission

        assertEquals(2, dispatched.size(), "Report with a lost header should be recovered");
        UDTShortMessageService sms = (UDTShortMessageService)dispatched.get(1);
        assertTrue(sms.isHeaderRecovered());
        assertTrue(sms.isHeaderValid(), "IDs come from a CRC valid preamble");
        assertEquals(908, sms.getSourceId());
        assertEquals(51.2016, sms.getNmeaLocation().getLatitude(), 0.0001);
    }

    @Test
    void doesNotRecoverCorruptedHeaderlessBlock()
    {
        List<IMessage> dispatched = new ArrayList<>();
        PacketSequenceAssembler assembler = new PacketSequenceAssembler();
        assembler.setMessageListener(dispatched::add);

        assembler.process(capturedPreamble(900));
        assembler.process(udtHeader(1, 1000, 908, 2345));
        assembler.process(capturedBlock("500CCC0F2808B4010087148B", 1060));

        //Corrupted block (fails CRC) with no header must not be turned into a report
        assembler.process(capturedPreamble(30000));
        assembler.process(capturedBlock("500CCC0F3C09B40240820C97", 31000));
        assembler.dispatchPendingSequences();
        assertEquals(1, dispatched.size());
    }

    @Test
    void multiBlockUdtWaitsForAllBlocks()
    {
        List<IMessage> dispatched = new ArrayList<>();
        PacketSequenceAssembler assembler = new PacketSequenceAssembler();
        assembler.setMessageListener(dispatched::add);

        assembler.process(udtHeader(2, 1000));
        assembler.process(block(1060));
        assertEquals(0, dispatched.size(), "Dispatched before all appended blocks were received");
        assembler.process(block(1120));
        assertEquals(1, dispatched.size());
    }

    @Test
    void incompleteSequenceDispatchedAtEndOfTransmission()
    {
        List<IMessage> dispatched = new ArrayList<>();
        PacketSequenceAssembler assembler = new PacketSequenceAssembler();
        assembler.setMessageListener(dispatched::add);

        //Header announces 2 blocks but the second block is lost to a fade
        assembler.process(udtHeader(2, 1000));
        assembler.process(block(1060));
        assertEquals(0, dispatched.size());

        assembler.dispatchPendingSequences();
        assertEquals(1, dispatched.size(), "Partial sequence should be dispatched when the transmission ends");
    }
}
