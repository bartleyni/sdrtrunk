/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.dmr.message.data.block.DataBlock;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.Preamble;
import io.github.dsheirer.module.decode.dmr.message.data.header.PacketSequenceHeader;
import io.github.dsheirer.module.decode.dmr.message.data.header.ProprietaryDataHeader;
import io.github.dsheirer.module.decode.dmr.message.data.header.UDTHeader;
import io.github.dsheirer.sample.Listener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reassembles packet sequences
 */
public class PacketSequenceAssembler
{
    private Listener<IMessage> mMessageListener;
    private PacketSequence mTimeslot1Sequence;
    private PacketSequence mTimeslot2Sequence;
    private static final int MAX_TRACKED_RADIOS = 1000;
    private static final int UDT_FORMAT_NMEA_LOCATION = 5;
    //Most recent UDT format successfully received from each source radio, used to recover sequences with a lost header
    private final Map<Integer,Integer> mUdtFormatBySource = new HashMap<>();

    /**
     * Constructs an instance
     */
    public PacketSequenceAssembler()
    {
    }

    /**
     * Gets the current packet sequence for the specified timeslot, constructing a new sequence as necessary.
     * @param timeslot of the packet sequence
     * @return timeslot sequence
     */
    private PacketSequence getPacketSequence(int timeslot)
    {
        if(timeslot == 1)
        {
            if(mTimeslot1Sequence == null)
            {
                mTimeslot1Sequence = new PacketSequence(1);
            }

            return mTimeslot1Sequence;
        }
        else
        {
            if(mTimeslot2Sequence == null)
            {
                mTimeslot2Sequence = new PacketSequence(2);
            }

            return mTimeslot2Sequence;
        }
    }

    /**
     * Sets the listener to receive packet sequence messages
     * @param listener to receive messages
     */
    public void setMessageListener(Listener<IMessage> listener)
    {
        mMessageListener = listener;
    }

    /**
     * Dispatches the packet sequence for the specified timeslot and sets the sequence to null.
     * @param timeslot to dispatch
     */
    public void dispatchPacketSequence(int timeslot)
    {
        PacketSequence packetSequence = (timeslot == 1 ? mTimeslot1Sequence : mTimeslot2Sequence);

        if(packetSequence != null && mMessageListener != null)
        {
            IMessage message = PacketSequenceMessageFactory.create(packetSequence);

            if(message == null)
            {
                message = recoverHeaderlessUdt(packetSequence);
            }

            if(message instanceof UDTShortMessageService sms)
            {
                verifyIdsFromPreamble(sms, packetSequence);

                if(sms.isCrcValid() && sms.isHeaderValid() && !sms.isHeaderRecovered())
                {
                    if(mUdtFormatBySource.size() >= MAX_TRACKED_RADIOS)
                    {
                        mUdtFormatBySource.clear();
                    }

                    mUdtFormatBySource.put(sms.getSourceId(), sms.getFormatValue());
                }
            }

            if(message != null)
            {
                mMessageListener.receive(message);
            }
        }

        if(timeslot == 1)
        {
            mTimeslot1Sequence = null;
        }
        else
        {
            mTimeslot2Sequence = null;
        }
    }

    /**
     * Finds the most recent CRC valid data preamble in the sequence.
     */
    private static Preamble getValidPreamble(PacketSequence sequence)
    {
        List<Preamble> preambles = sequence.getPreambles();

        for(int x = preambles.size() - 1; x >= 0; x--)
        {
            Preamble preamble = preambles.get(x);

            if(preamble.isValid() && preamble.isDataPreamble())
            {
                return preamble;
            }
        }

        return null;
    }

    /**
     * When a UDT header failed its CRC, but its source and destination match a CRC valid preamble from the same
     * sequence, the identifiers are verified.
     */
    private static void verifyIdsFromPreamble(UDTShortMessageService sms, PacketSequence sequence)
    {
        if(!sms.isHeaderValid() && sequence.hasUDTHeader())
        {
            Preamble preamble = getValidPreamble(sequence);
            UDTHeader header = sequence.getUDTHeader();

            if(preamble != null &&
                    preamble.getSourceAddress().getValue().equals(header.getSourceLLID().getValue()) &&
                    preamble.getTargetAddress().getValue().equals(header.getDestinationLLID().getValue()))
            {
                sms.setIdsVerifiedByPreamble(true);
            }
        }
    }

    /**
     * Attempts to recover a single block UDT short data message (e.g. a GPS report) whose header was lost to bit
     * errors.  Requires a CRC valid data preamble for the source/target addresses and a payload that passes the UDT
     * CRC check.  The UDT format is taken from the source radio's previous report, or NMEA location when unknown.
     * @return recovered message or null
     */
    private IMessage recoverHeaderlessUdt(PacketSequence sequence)
    {
        if(sequence.hasPacketSequenceHeader() || sequence.hasUDTHeader() || sequence.hasProprietaryDataHeader() ||
                sequence.getDataBlocks().size() != 1)
        {
            return null;
        }

        Preamble preamble = getValidPreamble(sequence);

        if(preamble == null)
        {
            return null;
        }

        //Use the source radio's previously received UDT format.  When none is known yet (e.g. the first report after
        //startup), assume NMEA location coded - the recovered message is still only accepted when the payload CRC
        //and the NMEA location range checks both pass.
        Integer format = mUdtFormatBySource.get(preamble.getSourceAddress().getValue());

        if(format == null)
        {
            format = UDT_FORMAT_NMEA_LOCATION;
        }

        DataBlock block = sequence.getDataBlocks().get(0);
        UDTHeader header = UDTHeader.createSubstitute(preamble, format, block.getTimestamp());
        UDTShortMessageService sms = new UDTShortMessageService(header,
                PacketSequenceMessageFactory.getUnconfirmedPayload(sequence.getDataBlocks()));
        sms.setHeaderRecovered(true);
        sms.setIdsVerifiedByPreamble(true);

        //Only accept a recovered message when its content passes the payload CRC (and location range checks)
        if(!sms.isCrcValid() || (sms.isNmeaLocation() && !sms.getNmeaLocation().isValid()))
        {
            return null;
        }

        return sms;
    }

    /**
     * Indicates if the packet sequence for the timeslot has a header and is still waiting for announced data blocks.
     * @param timeslot to check
     * @return true if more data blocks are expected
     */
    public boolean isExpectingDataBlocks(int timeslot)
    {
        PacketSequence sequence = (timeslot == 1 ? mTimeslot1Sequence : mTimeslot2Sequence);
        return sequence != null && (sequence.hasPacketSequenceHeader() || sequence.hasUDTHeader()) &&
                !sequence.isComplete();
    }

    /**
     * Dispatches any partially assembled packet sequences that contain data blocks.  Invoked when the transmission
     * has ended (sync loss) so that sequences are not held until an unrelated later transmission, which is important
     * for simplex/direct mode channels where there are no repeater idle messages to trigger dispatch.
     */
    public void dispatchPendingSequences()
    {
        if(mTimeslot1Sequence != null && mTimeslot1Sequence.hasDataBlocks())
        {
            dispatchPacketSequence(1);
        }

        if(mTimeslot2Sequence != null && mTimeslot2Sequence.hasDataBlocks())
        {
            dispatchPacketSequence(2);
        }
    }

    /**
     * Processes a packet sequence preamble.
     *
     * Note: DMR systems can transmit several preamble messages prior to the actual packet sequence.
     * @param preamble for a packet sequence
     */
    public void process(Preamble preamble)
    {
        int timeslot = preamble.getTimeslot();

        PacketSequence packetSequence = getPacketSequence(timeslot);

        //If we already have headers or data blocks for the current sequence, then this is a new sequence
        if(packetSequence.hasPacketSequenceHeader() ||
           packetSequence.hasProprietaryDataHeader() ||
           packetSequence.hasDataBlocks())
        {
            dispatchPacketSequence(timeslot);
            packetSequence = getPacketSequence(timeslot);
        }

        packetSequence.addPreamble(preamble);
    }

    /**
     * Processes a packet sequence header message.
     *
     * Note: a DMR packet sequence will have at least one header, but can also have a second header.
     * @param header to process
     */
    public void process(PacketSequenceHeader header)
    {
        int timeslot = header.getTimeslot();

        PacketSequence packetSequence = getPacketSequence(timeslot);

        //If we already have headers or data blocks for the current sequence, then this is a new sequence
        if(packetSequence.hasPacketSequenceHeader() ||
            packetSequence.hasProprietaryDataHeader() ||
            packetSequence.hasDataBlocks())
        {
            dispatchPacketSequence(timeslot);
            packetSequence = getPacketSequence(timeslot);
        }

        packetSequence.setPacketSequenceHeader(header);
    }

    /**
     * Process a UDT header.
     * @param header
     */
    public void process(UDTHeader header)
    {
        int timeslot = header.getTimeslot();

        PacketSequence packetSequence = getPacketSequence(timeslot);

        //If we already have headers or data blocks for the current sequence, then this is a new sequence
        if(packetSequence.hasPacketSequenceHeader() || packetSequence.hasProprietaryDataHeader() ||
                packetSequence.hasDataBlocks())
        {
            dispatchPacketSequence(timeslot);
            packetSequence = getPacketSequence(timeslot);
        }

        packetSequence.setUDTHeader(header);
    }

    /**
     * Processes a proprietary packet sequence header.
     */
    public void process(ProprietaryDataHeader proprietaryHeader)
    {
        int timeslot = proprietaryHeader.getTimeslot();

        PacketSequence packetSequence = getPacketSequence(timeslot);

        //If we already have headers or data blocks for the current sequence, then this is a new sequence
        if(packetSequence.hasProprietaryDataHeader() || packetSequence.hasDataBlocks())
        {
            dispatchPacketSequence(timeslot);
            packetSequence = getPacketSequence(timeslot);
        }

        packetSequence.setProprietaryHeader(proprietaryHeader);
    }

    /**
     * Processes a data block.
     * @param dataBlock to process
     */
    public void process(DataBlock dataBlock)
    {
        int timeslot = dataBlock.getTimeslot();
        PacketSequence packetSequence = getPacketSequence(timeslot);
        packetSequence.addDataBlock(dataBlock);

        if(packetSequence.isComplete())
        {
            dispatchPacketSequence(timeslot);
        }
    }
}
